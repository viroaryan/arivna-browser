package com.arivna.browser

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.widget.Toast
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import androidx.webkit.ProfileStore
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.atomic.AtomicInteger

internal fun sameSiteHost(from: String, to: String): Boolean {
    val f = from.trim().lowercase().removePrefix("www.")
    val t = to.trim().lowercase().removePrefix("www.")
    if (f.isEmpty() || t.isEmpty()) return false
    if (f == t || f.endsWith(".$t") || t.endsWith(".$f")) return true

    val fromLabel = registrableLabel(f) ?: return false
    val toLabel = registrableLabel(t) ?: return false

    val commonWords = setOf("news", "shop", "blog", "home", "site", "online", "portal", "login", "store", "video")
    return fromLabel.length >= 4 && fromLabel !in commonWords && fromLabel == toLabel
}

internal fun registrableLabel(host: String): String? {
    val clean = host.trim().lowercase().removePrefix("www.")
    if (clean.isEmpty()) return null
    val parts = clean.split('.').filter { it.isNotEmpty() }
    if (parts.size < 2) return clean
    val multiPartTlds = setOf("co.uk", "co.in", "com.au", "com.br", "co.jp", "co.nz", "com.tr", "co.za", "org.uk", "gov.in")
    val isMulti = multiPartTlds.any { clean.endsWith(".$it") }
    val labelIndex = if (isMulti) parts.size - 3 else parts.size - 2
    return if (labelIndex >= 0) parts[labelIndex] else null
}

class PageError(val code: Int, val description: String, val url: String, val sslDetail: String? = null) {
    companion object {
        const val SSL = -2001
        const val CRASH = -2002
    }
}

object UserAgents {
    private var default: String? = null
    private fun base(ctx: Context): String = default ?: WebSettings.getDefaultUserAgent(ctx).also { default = it }

    /** Looks like Chrome for Android, so sites don't serve a degraded "WebView" experience. */
    fun mobile(ctx: Context): String = base(ctx).replace("; wv)", ")").replace(Regex("Version/\\d+\\.\\d+ "), "")

    fun desktop(ctx: Context): String {
        val major = Regex("Chrome/(\\d+)").find(base(ctx))?.groupValues?.get(1) ?: "130"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$major.0.0.0 Safari/537.36"
    }
}

/** Private tabs get their own WebView profile (separate cookies & storage) where the device supports it. */
object PrivateProfile {
    private const val NAME = "arivna_private"
    val supported: Boolean by lazy {
        try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (_: Throwable) { false }
    }

    fun apply(wv: WebView) {
        if (!supported) return
        try {
            ProfileStore.getInstance().getOrCreateProfile(NAME)
            WebViewCompat.setProfile(wv, NAME)
        } catch (_: Throwable) {
        }
    }

    fun cookies(): CookieManager {
        if (supported) {
            try { return ProfileStore.getInstance().getOrCreateProfile(NAME).cookieManager } catch (_: Throwable) { }
        }
        return CookieManager.getInstance()
    }

    fun wipe() {
        if (!supported) return
        try { ProfileStore.getInstance().deleteProfile(NAME) } catch (_: Throwable) { }
    }
}

class Tab(val id: Long, val isPrivate: Boolean, private val app: AppState) {
    var url by mutableStateOf("")
    var title by mutableStateOf("")
    var favicon by mutableStateOf<Bitmap?>(null)
    var progress by mutableIntStateOf(100)
    var loading by mutableStateOf(false)
    /** Monotonic start time used to reclaim a background renderer that never finishes loading. */
    @Volatile var loadStartedAt = 0L
    var canGoBack by mutableStateOf(false)
    var canGoForward by mutableStateOf(false)
    var showHome by mutableStateOf(true)
    var blocked by mutableIntStateOf(0)
    /** Reader view: true when the page looks like an article; [toggleReader] switches it on/off. */
    var readerAvailable by mutableStateOf(false)
    var readerOn by mutableStateOf(false)
    fun toggleReader() {
        if (readerOn) reload() else webView?.evaluateJavascript(READER_ON, null)
        readerOn = !readerOn
    }
    var desktop by mutableStateOf(app.prefs.desktopDefault)
    var thumbnail by mutableStateOf<Bitmap?>(null)
    private var thumbDirty = true
    var error by mutableStateOf<PageError?>(null)
    var pendingSslHandler: SslErrorHandler? = null

    fun proceedSsl() {
        val h = pendingSslHandler
        pendingSslHandler = null
        error = null
        loading = true
        h?.proceed()
    }

    fun cancelSsl() {
        val h = pendingSslHandler
        pendingSslHandler = null
        h?.cancel()
        goBack()
    }

    /** Most recent sub-resource requests on this tab, for the Shield request log. */
    val requestLog = RequestLog()
    @Volatile var pageHost = ""
    @Volatile var pageUrl = ""
    var parentId = -1L
    /** A just-created window whose first destination must be classified before it is shown. */
    @Volatile var popupPending = false
    /** First user-intended host accepted in this popup; later unrelated automatic hops are popunder ads. */
    @Volatile private var popupTrustedHost = ""
    @Volatile private var popupAcceptedAt = 0L
    @Volatile private var intendedUrl = ""
    @Volatile private var intendedAt = 0L
    var homeBehind = true
    var pendingUrl: String? = null
    /** Wall-clock time this tab was last in front; saved with the session, so old tabs can auto-close and the least recently used give up their WebView first. */
    @Volatile var lastActive = System.currentTimeMillis()
    /** Page, back/forward list and scroll position of a tab whose WebView was released to save memory. */
    private var savedState: android.os.Bundle? = null

    var webView: WebView? = null
        private set
    private var container: SwipeRefreshLayout? = null
    private val handlers = ArrayList<ScriptHandler>()
    private val main = Handler(Looper.getMainLooper())
    private val pendingBlocked = AtomicInteger(0)

    val host: String get() = Uri.parse(url).host?.lowercase() ?: ""
    val displayTitle: String get() = when {
        showHome -> if (isPrivate) "Private tab" else "New tab"
        title.isNotBlank() -> title
        else -> url
    }

    fun ensureWebView(): WebView = webView ?: createWebView().also { webView = it }

    /** The view placed on screen: the WebView wrapped in a pull-to-refresh layout. */
    fun view(ctx: Context): View {
        val wv = ensureWebView()
        val c = container ?: SwipeRefreshLayout(ctx).also { srl ->
            (wv.parent as? ViewGroup)?.removeView(wv)
            srl.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            srl.setOnRefreshListener { reload() }
            container = srl
        }
        c.isEnabled = app.prefs.pullToRefresh
        c.setColorSchemeColors(app.accentColor().toArgb())
        (c.parent as? ViewGroup)?.removeView(c)
        return c
    }

    fun load(target: String) {
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        pendingUrl = null
        showHome = false
        error = null
        url = target
        loadStartedAt = SystemClock.elapsedRealtime()
        val wv = ensureWebView()
        val headers = if (app.prefs.dnt) mapOf("DNT" to "1", "Sec-GPC" to "1") else emptyMap()
        wv.loadUrl(target, headers)
        // Some ad/analytics streams never reach onPageFinished. They must not defeat the
        // four-renderer memory budget after the tab has gone to the background.
        main.postDelayed({ app.trimTabs() }, STALLED_LOAD_GRACE_MS + 250L)
    }

    fun activate() {
        main.removeCallbacks(thumbnailCapture)
        lastActive = System.currentTimeMillis()
        pendingUrl?.let { load(it) }
        webView?.onResume()
    }

    fun deactivate() {
        lastActive = System.currentTimeMillis()
        requestThumbnail()
        webView?.onPause()
    }

    /**
     * Frees the WebView (tens of MB) of a background tab. The page, history and scroll position are kept,
     * so the tab comes back where it was as soon as it is opened again.
     */
    fun discard() {
        val wv = webView ?: return
        if (showHome) { destroy(); return }
        captureThumbnail()
        if (loading) {
            // A half-loaded WebView can return a non-empty Bundle that restoreState accepts,
            // even though it contains no usable committed page. Reopening such a tab then
            // shows a permanent blank/stuck page. Reload its last URL instead.
            savedState = null
            if (url.isNotEmpty()) pendingUrl = url
        } else {
            val state = android.os.Bundle()
            try { wv.saveState(state) } catch (_: Throwable) { }
            savedState = if (state.isEmpty) null else state
            if (savedState == null && url.isNotEmpty()) pendingUrl = url
        }
        loading = false
        destroy()
    }

    fun reload() {
        error = null
        val wv = webView
        if (wv == null || wv.url == null) { if (url.isNotEmpty()) load(url); return }
        wv.reload()
    }

    fun stop() { webView?.stopLoading(); loading = false; container?.isRefreshing = false }

    fun find(text: String) { webView?.findAllAsync(text) }
    fun findNext(forward: Boolean) { webView?.findNext(forward) }
    fun clearFind() { webView?.clearMatches() }

    fun goBack(): Boolean {
        val wv = webView
        if (!showHome && error != null && wv != null && wv.canGoBack()) { error = null; wv.goBack(); return true }
        if (!showHome && wv != null && wv.canGoBack()) { wv.goBack(); return true }
        if (!showHome && parentId >= 0) {
            app.tabs.firstOrNull { it.id == parentId }?.let { parent ->
                app.select(parent)
                app.close(this)
                return true
            }
        }
        if (!showHome && homeBehind) { goHome(); return true }
        return false
    }

    fun goForward() {
        val wv = webView ?: return
        if (showHome && wv.url != null) { showHome = false; refreshNav(); return }
        if (wv.canGoForward()) wv.goForward()
    }

    fun goHome() {
        webView?.stopLoading()
        destroy()
        showHome = true
        homeBehind = true
        error = null
        url = ""
        title = ""
        favicon = null
        blocked = 0
        loading = false
        refreshNav()
    }

    fun refreshNav() {
        val wv = webView
        canGoBack = !showHome && ((wv?.canGoBack() == true) || homeBehind || parentId >= 0)
        canGoForward = wv?.canGoForward() == true
    }

    fun setDesktopMode(on: Boolean) {
        desktop = on
        webView?.let { wv ->
            wv.settings.userAgentString = if (on) UserAgents.desktop(wv.context) else UserAgents.mobile(wv.context)
            wv.settings.loadWithOverviewMode = true
            wv.settings.useWideViewPort = true
            if (!showHome) wv.reload()
        }
    }

    fun applySettings() {
        val wv = webView ?: return
        wv.settings.javaScriptEnabled = app.prefs.javascript
        // automatic sizing leaves the text at 100%, which WebView scales by the system font size itself
        wv.settings.textZoom = if (app.prefs.autoFontSize) 100 else app.prefs.textZoom
        wv.settings.mediaPlaybackRequiresUserGesture = app.prefs.autoplay != "allow"
        WebView.setWebContentsDebuggingEnabled(app.prefs.remoteDebug)
        cookieManager().setAcceptThirdPartyCookies(wv, !isPrivate && !app.prefs.block3pCookies)
        container?.isEnabled = app.prefs.pullToRefresh
        installScripts()
        applyNightMode()
    }

    fun cookieManager(): CookieManager = if (isPrivate) PrivateProfile.cookies() else CookieManager.getInstance()

    fun onBlocked() {
        Shield.onBlocked()
        if (pendingBlocked.getAndIncrement() == 0) {
            main.postDelayed({ blocked += pendingBlocked.getAndSet(0) }, 300)
        }
    }

    private val thumbnailCapture = Runnable { captureThumbnail() }

    /** Lets the navigation animation finish before the relatively expensive WebView draw. */
    fun requestThumbnail() {
        main.removeCallbacks(thumbnailCapture)
        main.postDelayed(thumbnailCapture, 900L)
    }

    /** Draws the page into a small bitmap, but only when it changed (page load or scroll) since the last one. */
    fun captureThumbnail() {
        val wv = webView ?: return
        if (showHome || wv.width <= 0 || wv.height <= 0 || (thumbnail != null && !thumbDirty)) return
        try {
            val scale = 0.25f
            val bmp = Bitmap.createBitmap((wv.width * scale).toInt().coerceAtLeast(1), (wv.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            c.translate(-wv.scrollX.toFloat(), -wv.scrollY.toFloat())
            wv.draw(c)
            thumbnail = bmp
            thumbDirty = false
        } catch (_: Throwable) {
        }
    }

    fun installScripts() {
        val wv = webView ?: return
        if (!DOC_START) return
        handlers.forEach { try { it.remove() } catch (_: Throwable) { } }
        handlers.clear()
        try {
            handlers += WebViewCompat.addDocumentStartJavaScript(wv, NAV_INTENT_SCRIPT, setOf("*"))
            if (app.prefs.zoomAllSites) handlers += WebViewCompat.addDocumentStartJavaScript(wv, ZOOM_SCRIPT, setOf("*"))
            if (app.prefs.autoplay == "blockAll") handlers += WebViewCompat.addDocumentStartJavaScript(wv, NO_AUTOPLAY_SCRIPT, setOf("*"))
        } catch (_: Throwable) { }
        if (!Shield.enabled) return
        try {
            if (Shield.ready) handlers += WebViewCompat.addDocumentStartJavaScript(wv, Shield.documentScript(), setOf("*"))
            if (Shield.youtube && !Shield.isAllowlisted("www.youtube.com")) {
                handlers += WebViewCompat.addDocumentStartJavaScript(wv, Shield.YOUTUBE_SCRIPT, Shield.YOUTUBE_ORIGINS)
            }
        } catch (_: Throwable) {
        }
    }

    /** Shows the element picker on this page. Tapping an element reports its selector via [onPicked]. */
    fun startElementPicker() {
        if (showHome) return
        // Rules only take effect while ad blocking is on, and the picker's hook comes from the shield script.
        if (!Shield.enabled || !Shield.ready) {
            Toast.makeText(app.activity, "Turn on Block ads and trackers to block elements", Toast.LENGTH_SHORT).show()
            return
        }
        app.pickingTab = this
        webView?.evaluateJavascript(ElementPicker.START, null)
    }

    fun stopElementPicker() {
        if (app.pickingTab === this) app.pickingTab = null
        webView?.evaluateJavascript(ElementPicker.STOP, null)
    }

    /** Called on the WebView thread by the picker. */
    fun onPicked(selector: String) {
        main.post {
            if (app.pickingTab !== this) return@post
            val site = pageHost.ifEmpty { this@Tab.host }
            app.picked = PickedElement(site, selector, this@Tab)
        }
    }

    fun recordNavigationIntent(target: String) {
        val uri = try { Uri.parse(target) } catch (_: Throwable) { return }
        if (uri.scheme != "http" && uri.scheme != "https") return
        intendedUrl = target
        intendedAt = SystemClock.elapsedRealtime()
    }

    private fun intendedDestination(target: Uri): Boolean {
        if (SystemClock.elapsedRealtime() - intendedAt > 4_000) return false
        val expected = try { Uri.parse(intendedUrl) } catch (_: Throwable) { return false }
        val a = expected.host?.lowercase().orEmpty()
        val b = target.host?.lowercase().orEmpty()
        return a.isNotEmpty() && sameSiteHost(a, b)
    }

    private fun continueIntendedNavigation() {
        if (SystemClock.elapsedRealtime() - intendedAt > 4_000) return
        val target = intendedUrl
        if (target.isBlank()) return
        main.postDelayed({
            val currentUrl = pageUrl.substringBefore('#')
            val wantedHost = Uri.parse(target).host?.lowercase().orEmpty()
            if (wantedHost.isNotEmpty() && currentUrl != target.substringBefore('#')) {
                load(target)
            }
        }, 180)
    }

    fun refreshProtection() {
        installScripts()
        if (!showHome && Shield.ready && Shield.enabled && !Shield.isAllowlisted(pageHost)) {
            webView?.evaluateJavascript(Shield.documentScript(), null)
            injectPageScripts(full = true)
        }
    }

    private fun injectPageScripts(full: Boolean) {
        val wv = webView ?: return
        if (!DOC_START) wv.evaluateJavascript(NAV_INTENT_SCRIPT, null)
        val h = pageHost
        if (Shield.enabled && !Shield.isAllowlisted(h)) {
            if (full && !DOC_START) {
                if (Shield.ready) wv.evaluateJavascript(Shield.documentScript(), null)
                if (Shield.youtube && h.endsWith("youtube.com")) wv.evaluateJavascript(Shield.YOUTUBE_SCRIPT, null)
            }
        }
        if (app.prefs.nightMode) applyNightMode() // a fresh page has no night style to remove
    }

    fun applyNightMode() {
        val wv = webView ?: return
        val js = if (app.prefs.nightMode) NIGHT_ON else NIGHT_OFF
        wv.evaluateJavascript(js, null)
    }

    // ---------- WebView ----------

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        val ctx = app.activity
        val wv = WebView(ctx)
        if (isPrivate) PrivateProfile.apply(wv)
        val s = wv.settings
        // Private tabs use a disposable, isolated WebView profile. Keep modern web platform
        // storage enabled inside that profile so OAuth, SPAs, media players and Cloudflare pages
        // still work; the entire profile is deleted after the last private tab closes.
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.saveFormData = !isPrivate
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = true
        s.builtInZoomControls = true
        s.displayZoomControls = false
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.setGeolocationEnabled(true)
        s.allowFileAccess = false
        s.allowContentAccess = true
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.safeBrowsingEnabled = true
        s.userAgentString = if (desktop) UserAgents.desktop(ctx) else UserAgents.mobile(ctx)
        if (isPrivate) s.cacheMode = WebSettings.LOAD_NO_CACHE
        wv.setBackgroundColor(app.pageBackground(isPrivate))
        wv.isScrollbarFadingEnabled = true
        wv.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        val bridge = ShieldBridge(this)
        wv.addJavascriptInterface(bridge, "__arivnaShield")
        wv.addJavascriptInterface(NavBridge(this), "__arivnaNav")
        wv.webViewClient = Client()
        wv.webChromeClient = Chrome()
        wv.setDownloadListener { dUrl, ua, cd, mime, len -> app.onDownloadRequested(this, dUrl, ua, cd, mime, len) }
        wv.setFindListener { active, count, done -> app.onFindResult(active, count, done) }
        wv.setOnLongClickListener { app.onLongPress(this, wv) }
        wv.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    thumbDirty = true
                }
            }
            false
        }
        webView = wv
        applySettings()
        // A tab that was discarded to save memory comes back where it was.
        savedState?.let { st ->
            savedState = null
            val restored = try { wv.restoreState(st) != null } catch (_: Throwable) { false }
            if (!restored && url.isNotEmpty()) wv.loadUrl(url)
        }
        return wv
    }

    fun destroy() {
        main.removeCallbacks(thumbnailCapture)
        pendingSslHandler?.cancel()
        pendingSslHandler = null
        handlers.forEach { try { it.remove() } catch (_: Throwable) { } }
        handlers.clear()
        val wv = webView ?: return
        webView = null
        (container?.parent as? ViewGroup)?.removeView(container)
        container?.removeAllViews()
        container = null
        (wv.parent as? ViewGroup)?.removeView(wv)
        try {
            wv.stopLoading()
            if (isPrivate) {
                wv.clearHistory(); wv.clearCache(true); wv.clearFormData(); wv.loadUrl("about:blank")
            }
            wv.destroy()
        } catch (_: Throwable) { }
    }

    private inner class Client : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val u = request.url
            if (request.isForMainFrame) {
                val parent = app.tabs.firstOrNull { it.id == parentId }
                if (parent != null) {
                    if (popupPending) {
                        popupPending = false
                        val targetHost = u.host?.lowercase().orEmpty()
                        val intended = parent.intendedDestination(u)
                        val brandRelated = sameSiteHost(parent.host, targetHost)
                        if (!intended && !brandRelated && app.prefs.blockPopups) {
                            onBlocked()
                            Shield.onPopupBlocked()
                            parent.continueIntendedNavigation()
                            main.post { app.close(this@Tab) }
                            return Shield.emptyResponse()
                        }
                        popupTrustedHost = targetHost
                        popupAcceptedAt = SystemClock.elapsedRealtime()
                        main.post { app.select(this@Tab) }
                    }
                    val matched = Shield.checkNavigation(u, parent.host, popup = true)
                    if (matched != null) {
                        onBlocked()
                        main.post { if (app.current?.id == id) goBack() }
                        return Shield.emptyResponse()
                    }
                }
                pageHost = u.host?.lowercase() ?: ""
                pageUrl = u.toString()
                return null
            }
            if (!Shield.enabled) return null
            val ph = pageHost
            if (Shield.isAllowlisted(ph)) return null
            val liveLog = if (app.screen == "requestlog") requestLog else null
            return Shield.intercept(u, pageUrl.ifEmpty { "https://$ph/" }, requestType(request), request.method ?: "GET", liveLog) { onBlocked() }
        }

        /** Best-effort resource type: WebView doesn't expose it, so use Sec-Fetch-Dest and the path. */
        private fun requestType(request: WebResourceRequest): Int {
            val headers = request.requestHeaders
            val dest = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
            when (dest) {
                "script", "serviceworker", "sharedworker", "worker" -> return Shield.T_SCRIPT
                "style" -> return Shield.T_STYLESHEET
                "image" -> return Shield.T_IMAGE
                "font" -> return Shield.T_FONT
                "iframe", "frame" -> return Shield.T_SUBDOCUMENT
                "video", "audio", "track" -> return Shield.T_MEDIA
                "object", "embed" -> return Shield.T_OBJECT
                "report" -> return Shield.T_PING
            }
            val path = request.url.path.orEmpty().lowercase()
            return when {
                path.endsWith(".js") || path.endsWith(".mjs") -> Shield.T_SCRIPT
                path.endsWith(".css") -> Shield.T_STYLESHEET
                IMAGE_EXT.containsMatchIn(path) -> Shield.T_IMAGE
                FONT_EXT.containsMatchIn(path) -> Shield.T_FONT
                MEDIA_EXT.containsMatchIn(path) -> Shield.T_MEDIA
                dest == "empty" || headers.any { it.key.equals("X-Requested-With", true) && it.value == "XMLHttpRequest" } -> Shield.T_XHR
                else -> Shield.T_OTHER
            }
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            // A legitimate target="_blank" may be followed immediately by an ad-network
            // top-level redirect. Keep the intended popup usable, but reject an automatic
            // hop unrelated to both its accepted host and the page that opened it. OAuth
            // callbacks remain valid because they return to one of those trusted sites.
            if (request.isForMainFrame && parentId >= 0 && !request.hasGesture() && app.prefs.blockPopups &&
                popupTrustedHost.isNotEmpty() && SystemClock.elapsedRealtime() - popupAcceptedAt < POPUP_HIJACK_WINDOW_MS) {
                val targetHost = request.url.host?.lowercase().orEmpty()
                val parentHost = app.tabs.firstOrNull { it.id == parentId }?.host.orEmpty()
                if (!sameSiteHost(popupTrustedHost, targetHost) && !sameSiteHost(parentHost, targetHost)) {
                    onBlocked()
                    Shield.onPopupBlocked()
                    return true
                }
            }
            // Do not reject every gesture-less cross-site navigation: OAuth, payment providers,
            // SSO and JavaScript routers legitimately use them. Known ad/redirect destinations are
            // still rejected below by the native filter engine; new windows stay quarantined too.
            if (request.isForMainFrame && Shield.enabled && !Shield.isAllowlisted(pageHost) && request.method.equals("GET", true)) {
                // Clean links: drop tracking parameters such as utm_*, fbclid and gclid.
                Shield.cleanUrl(request.url.toString(), pageUrl)?.let { cleaned ->
                    if (cleaned != request.url.toString()) {
                        view.loadUrl(cleaned)
                        return true
                    }
                }
            }
            if (request.isForMainFrame && Shield.enabled && !Shield.isAllowlisted(pageHost)) {
                val source = app.tabs.firstOrNull { it.id == parentId }?.host ?: pageHost
                val matched = Shield.checkNavigation(request.url, source, parentId >= 0 && view.copyBackForwardList().size == 0)
                if (matched != null) {
                    onBlocked()
                    if (parentId >= 0 && view.copyBackForwardList().size == 0) main.post { goBack() }
                    return true
                }
            }
            return app.handleSpecialUrl(this@Tab, request.url, request.hasGesture())
        }

        override fun onPageStarted(view: WebView, startUrl: String, icon: Bitmap?) {
            if (startUrl == "about:blank" && showHome) return
            // A new page drops the picker; the banner must not outlive it.
            if (app.pickingTab === this@Tab) app.pickingTab = null
            url = startUrl
            showHome = false
            loading = true
            loadStartedAt = SystemClock.elapsedRealtime()
            error = null
            val h = Uri.parse(startUrl).host?.lowercase() ?: ""
            pageHost = h
            pageUrl = startUrl
            pendingBlocked.set(0)
            blocked = 0
            readerAvailable = false
            readerOn = false
            favicon = app.favicons.get(h)
            injectPageScripts(full = true)
            refreshNav()
        }

        override fun onPageCommitVisible(view: WebView, commitUrl: String) {
            injectPageScripts(full = true)
        }

        override fun onPageFinished(view: WebView, finishedUrl: String) {
            loading = false
            thumbDirty = true
            container?.isRefreshing = false
            injectPageScripts(full = false)
            view.evaluateJavascript(READER_CHECK) { readerAvailable = it == "true" }
            refreshNav()
            app.trimTabs()
        }

        override fun doUpdateVisitedHistory(view: WebView, visitedUrl: String, isReload: Boolean) {
            if (visitedUrl == "about:blank") return
            url = visitedUrl
            if (!isReload && !isPrivate) app.history.record(visitedUrl, view.title ?: "")
            if (!isPrivate) app.saveSession()
            refreshNav()
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
            if (!request.isForMainFrame) return
            if (err.errorCode == ERROR_UNSUPPORTED_SCHEME) return
            error = PageError(err.errorCode, err.description?.toString() ?: "", request.url.toString())
            loading = false
            container?.isRefreshing = false
            app.trimTabs()
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, e: SslError) {
            val reason = when (e.primaryError) {
                SslError.SSL_EXPIRED -> "The security certificate has expired."
                SslError.SSL_IDMISMATCH -> "The certificate host name does not match this website."
                SslError.SSL_UNTRUSTED -> "The certificate authority is not trusted."
                SslError.SSL_NOTYETVALID -> "The certificate is not yet valid."
                SslError.SSL_DATE_INVALID -> "The certificate date is invalid."
                else -> "The security certificate is invalid."
            }
            pendingSslHandler = handler
            error = PageError(PageError.SSL, "Your connection is not private. $reason", e.url ?: url, sslDetail = reason)
            loading = false
            container?.isRefreshing = false
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val lastUrl = url
            destroy()
            pendingUrl = null
            url = lastUrl
            error = PageError(PageError.CRASH, "This page used too much memory or crashed.", lastUrl)
            app.onTabCrashed()
            return true
        }
    }

    private inner class Chrome : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            progress = newProgress
            if (newProgress >= 100) loading = false
        }

        override fun onReceivedTitle(view: WebView, t: String?) {
            title = t ?: ""
            if (!isPrivate) app.history.updateTitle(view.url ?: url, title)
        }

        override fun onReceivedIcon(view: WebView, icon: Bitmap?) {
            if (icon == null) return
            favicon = icon
            if (!isPrivate) app.favicons.put(pageHost, icon)
        }

        override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean =
            app.onCreateWindow(this@Tab, isUserGesture, resultMsg)

        override fun onCloseWindow(window: WebView) { app.closeTabOf(window) }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) { app.enterFullscreen(view, callback) }
        override fun onHideCustomView() { app.exitFullscreen() }

        override fun onPermissionRequest(request: PermissionRequest) { app.onPermissionRequest(request) }

        override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
            app.onGeolocationRequest(origin, callback)
        }

        override fun onShowFileChooser(webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean =
            app.activity.openFileChooser(filePathCallback, params)

        override fun onJsAlert(view: WebView, u: String, message: String, result: JsResult): Boolean {
            app.dialog = Dlg.Js(Dlg.Js.ALERT, Uri.parse(u).host ?: "This page", message, null, result)
            return true
        }

        override fun onJsConfirm(view: WebView, u: String, message: String, result: JsResult): Boolean {
            app.dialog = Dlg.Js(Dlg.Js.CONFIRM, Uri.parse(u).host ?: "This page", message, null, result)
            return true
        }

        override fun onJsPrompt(view: WebView, u: String, message: String, defaultValue: String?, result: JsPromptResult): Boolean {
            app.dialog = Dlg.Js(Dlg.Js.PROMPT, Uri.parse(u).host ?: "This page", message, defaultValue ?: "", result)
            return true
        }

        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    }

    companion object {
        private const val NAV_INTENT_SCRIPT = """(function(){if(window.__arivnaNavInstalled)return;window.__arivnaNavInstalled=true;function r(e){try{if(!e.isTrusted)return;var n=e.target&&e.target.closest?e.target.closest('a[href]'):null;if(n&&n.href&&window.__arivnaNav)window.__arivnaNav.navigationIntent(n.href)}catch(_){}}document.addEventListener('pointerdown',r,true);document.addEventListener('click',r,true)})();"""
        private const val POPUP_HIJACK_WINDOW_MS = 15_000L
        private const val NIGHT_ON = "(function(){if(document.getElementById('arivna-night'))return;var s=document.createElement('style');s.id='arivna-night';s.textContent='html{filter:invert(.9) hue-rotate(180deg)!important;background:#111!important}img,video,picture,canvas,iframe,embed,object,svg image,[style*=\"background-image\"]{filter:invert(1) hue-rotate(180deg)!important}';(document.head||document.documentElement).appendChild(s);})();"
        private const val NIGHT_OFF = "(function(){var s=document.getElementById('arivna-night');if(s)s.remove();})();"
        // Lets pages that forbid pinch zoom (maximum-scale, user-scalable=no) be zoomed anyway.
        private const val ZOOM_SCRIPT = """(function(){function f(){var m=document.querySelector('meta[name=viewport]');if(m&&/user-scalable|maximum-scale/i.test(m.content))m.content=m.content.replace(/(user-scalable|maximum-scale)\s*=\s*[^,;]*[,;]?\s*/gi,'')}new MutationObserver(f).observe(document,{subtree:true,childList:true,attributes:true,attributeFilter:['content']});f()})();"""
        // "Block all" autoplay: play() only works while the user has interacted with the page.
        private const val NO_AUTOPLAY_SCRIPT = """(function(){var p=HTMLMediaElement.prototype.play;HTMLMediaElement.prototype.play=function(){return navigator.userActivation&&navigator.userActivation.hasBeenActive?p.apply(this,arguments):Promise.reject(new DOMException('Autoplay blocked','NotAllowedError'))}})();"""
        private const val READER_CHECK = """(function(){var a=document.querySelector('article'),w=0;document.querySelectorAll('p').forEach(function(p){w+=p.innerText.split(/\s+/).length});return !!a&&a.innerText.split(/\s+/).length>200||w>600})()"""
        // Replaces the page with its title and the main text block (the <article>, else the element with most paragraph text).
        private const val READER_ON = """(function(){var best=document.querySelector('article'),m=0;if(!best)document.querySelectorAll('div,section,main').forEach(function(d){var n=0;for(var c=d.firstElementChild;c;c=c.nextElementSibling)if(c.tagName=='P')n+=c.innerText.length;if(n>m){m=n;best=d}});if(!best)return;var x=best.cloneNode(true);x.querySelectorAll('script,style,nav,aside,footer,form,iframe,noscript,button,svg').forEach(function(e){e.remove()});x.querySelectorAll('[style]').forEach(function(e){e.removeAttribute('style')});var t=document.title;document.querySelectorAll('link[rel=stylesheet],style').forEach(function(e){e.remove()});document.body.innerHTML='<main><h1></h1></main>';document.querySelector('h1').textContent=t;document.querySelector('main').appendChild(x);var s=document.createElement('style');s.textContent=':root{color-scheme:light dark}body{margin:0;background:#fbfbfe;color:#15141a;font:19px/1.7 Georgia,serif}main{max-width:40em;margin:0 auto;padding:24px 18px}h1{font:700 1.7em/1.25 sans-serif}img,video{max-width:100%;height:auto}a{color:#6b2bd9}@media(prefers-color-scheme:dark){body{background:#1c1b22;color:#fbfbfe}a{color:#cfa9ff}}';document.head.appendChild(s);var v=document.querySelector('meta[name=viewport]');if(!v){v=document.createElement('meta');v.name='viewport';document.head.appendChild(v)}v.content='width=device-width,initial-scale=1';window.scrollTo(0,0)})();"""
        private val DOC_START by lazy { WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) }
        private val IMAGE_EXT = Regex("\\.(png|jpe?g|gif|webp|svg|ico|avif|bmp)$")
        private val FONT_EXT = Regex("\\.(woff2?|ttf|otf|eot)$")
        private val MEDIA_EXT = Regex("\\.(mp4|mp3|m3u8|webm|m4a|aac|ogg|ts|mpd)$")
    }
}
