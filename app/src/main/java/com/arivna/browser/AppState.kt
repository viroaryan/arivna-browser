package com.arivna.browser

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Message
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.net.URLEncoder

class AppState(val activity: MainActivity) {
    // declared first: the init block below saves the session while it builds the first tab
    private val sessionHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var sessionQueued = false
    private val sessionFile = java.io.File(activity.filesDir, "session.json")
    val prefs = Prefs(activity)
    val bookmarks = BookmarksStore(activity)
    val history = HistoryStore(activity)
    val downloads = DownloadsStore(activity)
    val favicons = FaviconCache(activity)
    val tabs = mutableStateListOf<Tab>()
    /** Most recently closed normal tabs, newest first (max 25); filled by close(), used by the tab tray, History and the "Tab closed - Undo" snackbar. */
    val closedTabs = mutableStateListOf<ClosedTab>()
    fun reopenClosedTab(c: ClosedTab) { closedTabs.remove(c); newTab(url = c.url) }
    private fun rememberClosed(tab: Tab) {
        if (tab.isPrivate || tab.showHome || !tab.url.startsWith("http")) return
        closedTabs.add(0, ClosedTab(tab.url, tab.title))
        while (closedTabs.size > 25) closedTabs.removeAt(closedTabs.lastIndex)
    }
    var activeId by mutableLongStateOf(-1)
    var screen by mutableStateOf("browser")
    var dialog by mutableStateOf<Dlg?>(null)
    var menu by mutableStateOf(false)
    var shortcutEditor by mutableStateOf(false)
    var searchEngineEditor by mutableStateOf(false)
    var shortcutEditing by mutableStateOf<Shortcut?>(null)
    var searchRequest by mutableIntStateOf(0)
    var filterUpdating by mutableStateOf(false)
    var filterUpdateStatus by mutableStateOf("")
    var findText by mutableStateOf("")
    var findCount by mutableStateOf("")
    var findOpen by mutableStateOf(false)
    /** Live voice-search overlay state (null when not listening). */
    var voice by mutableStateOf<VoiceUi?>(null)
    val downloadTracker = DownloadTracker(activity).also { t -> t.onFinished = { id, uri, size, name -> downloads.finish(id, uri, size, name) } }
    /** The tab whose page is in element-picker mode, if any. */
    var pickingTab by mutableStateOf<Tab?>(null)
    /** An element the user tapped, waiting for them to confirm the block rule. */
    var picked by mutableStateOf<PickedElement?>(null)
    var customView: View? by mutableStateOf(null)
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    val current: Tab? get() = tabs.firstOrNull { it.id == activeId }

    init {
        Shield.init(activity, prefs)
        WebView.setWebContentsDebuggingEnabled(prefs.remoteDebug)
        ServiceWorkerController.getInstance().setServiceWorkerClient(object : ServiceWorkerClient() {
            override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? {
                if (!Shield.enabled) return null
                val source = request.requestHeaders.entries.firstOrNull { it.key.equals("Referer", true) }?.value
                val sourceHost = source?.let { Uri.parse(it).host } ?: current?.pageHost.orEmpty()
                if (Shield.isAllowlisted(sourceHost)) return null
                val matched = Shield.check(request.url, sourceHost, "other") ?: return null
                Shield.onBlocked()
                return Shield.emptyResponse()
            }
        })
        if (!restoreSession()) newTab()
        // ON_START fires on launch and on every return to the foreground
        activity.lifecycle.addObserver(LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) prefs.lastStop = System.currentTimeMillis()
            if (e == Lifecycle.Event.ON_START) {
                autoCloseOldTabs()
                if (prefs.openingScreen == "after4h" && prefs.lastStop > 0 && System.currentTimeMillis() - prefs.lastStop > 4 * HOUR_MS) showHomeTab()
            }
            if (e == Lifecycle.Event.ON_START && prefs.autoUpdateFilters && System.currentTimeMillis() - prefs.lastFilterUpdate > Shield.UPDATE_INTERVAL_MS) refreshFilters()
        })
    }
    fun accentColor(): Color = AccentPreset.of(prefs.accent).accent
    fun pageBackground(incognito: Boolean): Int = if (incognito) AndroidColor.rgb(31, 30, 29) else AndroidColor.rgb(250, 249, 245)
    fun newTab(incognito: Boolean = false, url: String? = null, activate: Boolean = true): Tab {
        trimTabs()
        if (activate) current?.deactivate()
        val tab = Tab(System.nanoTime(), incognito, this)
        tabs.add(tab)
        if (activate) { activeId = tab.id; screen = "browser" }
        if (url != null) tab.load(url)
        saveSession()
        return tab
    }
    /**
     * Leaves private browsing and lands on a normal home page. An empty private tab is closed;
     * private tabs that hold pages stay available from the Tabs screen.
     */
    fun exitPrivate() {
        val priv = current?.takeIf { it.isPrivate }
        val home = tabs.lastOrNull { !it.isPrivate && it.showHome } ?: newTab(false, activate = false)
        select(home)
        if (priv != null && priv.showHome) close(priv)
    }
    private fun showHomeTab() = select(tabs.lastOrNull { !it.isPrivate && it.showHome } ?: newTab(false, activate = false))

    /** Settings > Tabs > Close tabs: normal tabs not opened for a day / week / month are closed. */
    private fun autoCloseOldTabs() {
        val limit = when (prefs.closeTabs) { "day" -> 24 * HOUR_MS; "week" -> 7 * 24 * HOUR_MS; "month" -> 30 * 24 * HOUR_MS; else -> return }
        val now = System.currentTimeMillis()
        tabs.filter { !it.isPrivate && it.id != activeId && now - it.lastActive > limit }.forEach(::close)
    }
    fun select(tab: Tab) { current?.deactivate(); activeId = tab.id; screen = "browser"; tab.activate(); trimTabs(); saveSession() }

    // ----- tab memory and session -----


    /**
     * Keeps the tab in front plus the three most recently used live. A page that is still
     * making network requests gets a short grace period, but cannot pin a renderer forever
     * (a common failure mode on ad-heavy pages with never-ending requests).
     */
    fun trimTabs() {
        val now = android.os.SystemClock.elapsedRealtime()
        tabs.filter {
            it.webView != null && it.id != activeId &&
                (!it.loading || now - it.loadStartedAt >= STALLED_LOAD_GRACE_MS)
        }
            .sortedByDescending { it.lastActive }
            .drop(MAX_LIVE_TABS - 1)
            .forEach { it.discard() }
    }

    /** Under memory pressure: every tab but the one in front releases its WebView. */
    fun discardBackgroundTabs() {
        tabs.filter { it.webView != null && it.id != activeId && !it.loading }.forEach { it.discard() }
    }

    /** Remembers the open tabs (not private ones) so they are back after the system closes the app. */
    fun saveSession(now: Boolean = false) {
        if (now) { sessionHandler.removeCallbacksAndMessages(null); sessionQueued = false; writeSession(); return }
        if (sessionQueued) return
        sessionQueued = true
        sessionHandler.postDelayed({ sessionQueued = false; writeSession() }, 2000)
    }

    private fun writeSession() {
        val normal = tabs.filter { !it.isPrivate }.takeLast(MAX_SAVED_TABS)
        if (normal.isEmpty()) return
        val arr = org.json.JSONArray()
        normal.forEach { t -> arr.put(org.json.JSONObject().put("u", if (t.showHome) "" else t.url).put("t", t.title).put("h", t.showHome).put("a", t.lastActive)) }
        val activeIndex = normal.indexOfFirst { it.id == activeId }.let { if (it < 0) normal.lastIndex else it }
        val text = org.json.JSONObject().put("active", activeIndex).put("tabs", arr).toString()
        Io.disk.execute { writeAtomic(sessionFile, text) }
    }

    private fun restoreSession(): Boolean {
        try {
            if (!sessionFile.exists()) return false
            val o = org.json.JSONObject(sessionFile.readText())
            val arr = o.getJSONArray("tabs")
            if (arr.length() == 0) return false
            var base = System.nanoTime()
            for (i in 0 until arr.length()) {
                val e = arr.getJSONObject(i)
                val tab = Tab(base++, false, this)
                tab.lastActive = e.optLong("a", tab.lastActive)
                if (!e.optBoolean("h", true) && e.optString("u").startsWith("http")) {
                    tab.showHome = false
                    tab.url = e.getString("u")
                    tab.title = e.optString("t")
                    tab.pendingUrl = tab.url
                }
                tabs.add(tab)
            }
            val active = tabs[o.optInt("active", tabs.lastIndex).coerceIn(0, tabs.lastIndex)]
            activeId = active.id
            if (prefs.openingScreen == "home") showHomeTab() else active.activate()
            return true
        } catch (_: Exception) {
            tabs.clear()
            return false
        }
    }
    fun close(tab: Tab) {
        val index = tabs.indexOf(tab)
        rememberClosed(tab)
        tabs.remove(tab); tab.destroy()
        if (tabs.none { it.isPrivate }) {
            PrivateProfile.wipe()
            if (!PrivateProfile.supported) CookieManager.getInstance().removeSessionCookies(null)
        }
        if (activeId == tab.id) { activeId = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))?.id ?: newTab().id; current?.activate() }
        saveSession()
    }
    fun navigate(text: String) {
        val url = parseNavInput(text, prefs.engine.searchUrl)
        if (url.isNotEmpty()) {
            if (current?.isPrivate != true && url == prefs.engine.searchUrl.replace("%s", URLEncoder.encode(text.trim(), "UTF-8"))) recordSearch(text)
            current?.load(url)
            screen = "browser"
        }
    }
    fun handleIntent(intent: Intent?) {
        // Tapping a download notification opens the Downloads screen.
        intent?.getStringExtra("open")?.let { target ->
            intent.removeExtra("open")
            screen = target
            return
        }
        val text = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")
            else -> null
        }
        if (!text.isNullOrBlank()) { if (intent?.getBooleanExtra("newtab", false) == true) newTab(url = text) else navigate(text) }
    }
    fun handleSpecialUrl(tab: Tab, uri: Uri, gesture: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "http" || scheme == "https") {
            if (gesture && prefs.openLinksInApps == "always") appIntent(uri.toString())?.let { launchExternal(it); return true }
            return false
        }
        if (scheme in setOf("about", "data", "blob")) return false
        if (scheme in setOf("javascript", "file", "content")) return true
        if (scheme == "intent") {
            return try {
                val deepLink = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME).apply {
                    addCategory(Intent.CATEGORY_BROWSABLE)
                    component = null
                    selector = null
                }
                val fallback = deepLink.getStringExtra("browser_fallback_url")
                if (gesture && prefs.openLinksInApps != "never" && deepLink.resolveActivity(activity.packageManager) != null) launchExternal(deepLink)
                else if (fallback != null && Uri.parse(fallback).scheme in listOf("http", "https")) tab.load(fallback)
                else if (!deepLink.`package`.isNullOrBlank()) tab.load("https://play.google.com/store/apps/details?id=${Uri.encode(deepLink.`package`)}")
                true
            } catch (_: Exception) { true }
        }
        if (gesture) launchExternal(Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE), if (scheme in setOf("tel", "mailto", "sms")) "always" else prefs.openLinksInApps)
        return true
    }

    /** Starts another app as Settings > Open links in apps says: always, ask first, or never. */
    private fun launchExternal(intent: Intent, policy: String = prefs.openLinksInApps) {
        val go = { try { activity.startActivity(intent) } catch (_: Exception) { } }
        when (policy) {
            "never" -> {}
            "ask" -> AlertDialog.Builder(activity).setTitle("Open in another app?").setMessage(intent.dataString)
                .setPositiveButton("Open") { _, _ -> go() }.setNegativeButton("Cancel", null).show()
            else -> go()
        }
    }
    fun onCreateWindow(parent: Tab, gesture: Boolean, result: Message): Boolean {
        // Keep the popup quarantined off-screen until its first URL is classified. This avoids
        // tab flashing and input lag when an ad page fires several popunders from one tap.
        val tab = newTab(parent.isPrivate, activate = false)
        tab.parentId = parent.id
        tab.popupPending = prefs.blockPopups
        tab.homeBehind = false
        tab.showHome = false
        tab.loading = true
        val transport = result.obj as WebView.WebViewTransport
        transport.webView = tab.ensureWebView()
        result.sendToTarget()
        activity.window.decorView.postDelayed({
            if (tab.popupPending && tabs.contains(tab)) {
                tab.popupPending = false
                Shield.onPopupBlocked()
                close(tab)
            }
        }, 1_200)
        return true
    }
    fun closeTabOf(view: WebView) { tabs.firstOrNull { it.webView == view }?.let(::close) }
    fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        customView = view
        fullCallback = callback
        activity.runOnUiThread {
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            insets.hide(WindowInsetsCompat.Type.systemBars())
            insets.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    fun exitFullscreen() {
        customView = null
        fullCallback?.onCustomViewHidden()
        fullCallback = null
        activity.runOnUiThread {
            val insets = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            insets.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    /** A per-site exception ("host|kind|ask or block" lines) wins over the default from Settings > Site settings > Permissions. */
    private fun permBlocked(host: String, kind: String): Boolean =
        (prefs.sitePermsRaw.lines().map { it.split('|') }.firstOrNull { it.size == 3 && it[0] == host && it[1] == kind }?.get(2)
            ?: when (kind) { "camera" -> prefs.permCamera; "microphone" -> prefs.permMicrophone; else -> prefs.permLocation }) == "block"
    fun onPermissionRequest(request: PermissionRequest) {
        val host = request.origin.host.orEmpty()
        val kinds = request.resources.mapNotNull { when (it) { PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "camera"; PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "microphone"; else -> null } }
        if (kinds.any { permBlocked(host, it) }) request.deny() else activity.runOnUiThread { activity.askWebPermission(request) }
    }
    fun onGeolocationRequest(origin: String, callback: GeolocationPermissions.Callback) {
        if (permBlocked(Uri.parse(origin).host.orEmpty(), "location")) callback.invoke(origin, false, false)
        else activity.runOnUiThread { activity.requestGeolocation(origin, callback) }
    }
    fun onFindResult(active: Int, count: Int, done: Boolean) { findCount = if (done) "${if (count == 0) 0 else active + 1}/$count" else "" }
    fun onLongPress(tab: Tab, webView: WebView): Boolean {
        val hit = webView.hitTestResult ?: return false
        val candidate = hit.extra ?: return false
        if (hit.type !in setOf(WebView.HitTestResult.SRC_ANCHOR_TYPE, WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_TYPE, WebView.HitTestResult.EMAIL_TYPE, WebView.HitTestResult.PHONE_TYPE)) return false
        dialog = Dlg.Link(candidate)
        return true
    }
    fun onTabCrashed() {
        Toast.makeText(activity, "Page crashed — tap Try again to restore it", Toast.LENGTH_LONG).show()
    }
    fun copy(text: String) {
        (activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Arivna link", text))
        Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }
    fun closeOtherTabs(keep: Tab) { tabs.toList().filter { it !== keep }.forEach(::close); select(keep) }
    /** Closes every tab of one kind; ends up on a normal home page if nothing is left. */
    fun closeTabs(private: Boolean) {
        val doomed = tabs.filter { it.isPrivate == private }
        val keepActive = tabs.firstOrNull { it.isPrivate != private && it.id == activeId }
        doomed.forEach { rememberClosed(it); it.destroy(); tabs.remove(it) }
        if (tabs.none { it.isPrivate }) {
            PrivateProfile.wipe()
            if (!PrivateProfile.supported) CookieManager.getInstance().removeSessionCookies(null)
        }
        if (keepActive == null) {
            val next = tabs.lastOrNull() ?: newTab(false, activate = false)
            select(next)
        }
    }
    fun clearBrowsingData() {
        deleteBrowsingData(tabs = false, history = true, cookies = true, cache = true, perms = false, downloads = false)
        Toast.makeText(activity, "Browsing data cleared", Toast.LENGTH_SHORT).show()
    }
    fun refreshFilters() {
        if (filterUpdating) return
        filterUpdating = true
        filterUpdateStatus = "Updating filter lists…"
        Shield.updateLists(activity) { success, message ->
            filterUpdating = false
            filterUpdateStatus = message
            if (success) { prefs.lastFilterUpdate = System.currentTimeMillis(); tabs.forEach { it.refreshProtection() } }
        }
    }
    fun refreshShieldTabs() { tabs.forEach { it.installScripts(); if (!it.showHome) it.reload() } }

    /** Ends picker mode and closes the confirmation, leaving the page as it is. */
    fun cancelPicker() {
        picked = null
        pickingTab?.stopElementPicker()
    }

    /** Saves the picked element as a rule, hides it on the page now, and recompiles the engine. */
    fun blockPicked(element: PickedElement, selector: String) {
        val added = UserFilters.add(activity, element.host, selector)
        picked = null
        element.tab.stopElementPicker()
        if (!added) {
            Toast.makeText(activity, "That rule is already saved or isn't valid", Toast.LENGTH_SHORT).show()
            return
        }
        element.tab.webView?.evaluateJavascript(UserFilters.hideNowScript(selector), null)
        Shield.rebuild {
            Toast.makeText(activity, "Element blocked on ${element.host}", Toast.LENGTH_SHORT).show()
        }
    }
    fun onDownloadRequested(tab: Tab, url: String, agent: String, disposition: String, mime: String, length: Long) {
        try {
            val name = URLUtil.guessFileName(url, disposition, mime)
            activity.ensureNotificationPermission()
            val id = downloadTracker.start(url, name, mime, agent, tab.cookieManager().getCookie(url), tab.url.takeIf { it.startsWith("http") })
            if (!tab.isPrivate) downloads.add(DownloadItem(System.nanoTime(), url, name, mime, System.currentTimeMillis(), length, id))
            closeBlankAfterDownload(tab)
        } catch (_: Exception) { }
    }

    /**
     * A download that starts from a tab with no page of its own (a popup or a direct file link) would leave
     * that tab as a blank white page. Close such a popup and go back to the page that opened it, or
     * show the home page for a plain new tab.
     */
    private fun closeBlankAfterDownload(tab: Tab) {
        val wv = tab.webView ?: return
        val history = wv.copyBackForwardList()
        val committed = history.currentItem?.url.orEmpty()
        val blank = history.size == 0 || committed.isBlank() || committed == "about:blank" || committed == wv.originalUrl && !wv.canGoBack() && tab.title.isBlank()
        if (!blank) return
        activity.window.decorView.post {
            if (tab !in tabs) return@post
            val parent = tabs.firstOrNull { it.id == tab.parentId }
            when {
                parent != null -> { select(parent); close(tab) }
                tab.id == activeId -> tab.goHome()
            }
        }
    }
}



private const val HOUR_MS = 3_600_000L

fun AppState.saveAsPdf(tab: Tab) {
    val wv = tab.webView ?: return
    val name = tab.title.ifBlank { tab.host }.ifBlank { "page" }
    (activity.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(name, wv.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
}

fun AppState.addToHomeScreen(tab: Tab) {
    if (tab.showHome || !ShortcutManagerCompat.isRequestPinShortcutSupported(activity)) {
        Toast.makeText(activity, "Can't add this page to the home screen", Toast.LENGTH_SHORT).show()
        return
    }
    val open = Intent(activity, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(tab.url)).putExtra("newtab", true)
    val icon = tab.favicon?.let(IconCompat::createWithBitmap) ?: IconCompat.createWithResource(activity, R.mipmap.ic_launcher)
    val label = tab.title.ifBlank { tab.host }
    ShortcutManagerCompat.requestPinShortcut(activity, ShortcutInfoCompat.Builder(activity, "page_${tab.url.hashCode()}").setShortLabel(label).setIcon(icon).setIntent(open).build(), null)
}

/** An intent for the first installed app, other than browsers and Arivna, that opens [url]. */
private fun AppState.appIntent(url: String): Intent? {
    if (!url.startsWith("http")) return null
    val pm = activity.packageManager
    val browsers = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER), 0).map { it.activityInfo.packageName }
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
    val app = pm.queryIntentActivities(view, PackageManager.MATCH_DEFAULT_ONLY)
        .firstOrNull { it.activityInfo.packageName !in browsers && it.activityInfo.packageName != activity.packageName } ?: return null
    return view.setPackage(app.activityInfo.packageName)
}

fun AppState.canOpenInApp(tab: Tab): Boolean = !tab.showHome && appIntent(tab.url) != null
fun AppState.openInApp(tab: Tab) { appIntent(tab.url)?.let { try { activity.startActivity(it) } catch (_: Exception) { } } }

/** Delete-on-quit; the activity calls this from onDestroy when it is finishing. */
fun AppState.onQuitCleanup() {
    if (prefs.deleteOnQuit) deleteBrowsingData(prefs.quitTabs, prefs.quitHistory, prefs.quitCookies, prefs.quitCache, prefs.quitPerms, prefs.quitDownloads)
}
fun AppState.quit() { onQuitCleanup(); activity.finishAndRemoveTask() }

/** Deletes the chosen kinds of browsing data; used by Settings > Delete browsing data and delete-on-quit. */
fun AppState.deleteBrowsingData(tabs: Boolean, history: Boolean, cookies: Boolean, cache: Boolean, perms: Boolean, downloads: Boolean) {
    if (cache) {
        val live = this.tabs.firstNotNullOfOrNull { it.webView }
        if (live != null) live.clearCache(true) else WebView(activity).run { clearCache(true); destroy() }
    }
    if (history) {
        this.history.clear()
        prefs.recentSearchesRaw = ""
        this.tabs.forEach { it.webView?.clearHistory() }
    }
    if (cookies) {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        WebViewDatabase.getInstance(activity).clearHttpAuthUsernamePassword()
    }
    if (perms) { prefs.sitePermsRaw = ""; GeolocationPermissions.getInstance().clearAll() }
    if (downloads) this.downloads.clear()
    if (tabs) { closeTabs(true); closeTabs(false); closedTabs.clear() }
}

fun AppState.recentSearches(): List<String> = prefs.recentSearchesRaw.lines().filter { it.isNotBlank() }
fun AppState.recordSearch(q: String) {
    val t = q.trim().replace('\n', ' ')
    if (t.isNotEmpty()) prefs.recentSearchesRaw = (listOf(t) + recentSearches().filter { !it.equals(t, true) }).take(20).joinToString("\n")
}

/** Typed-prefix completions (host/path, no scheme) from the newest history pages; empty when Autocomplete URLs is off. */
fun AppState.suggestUrls(prefix: String): List<String> {
    val p = prefix.trim().lowercase()
    if (p.isEmpty() || !prefs.autocompleteUrls) return emptyList()
    return history.recent.map { it.url.substringAfter("://").removePrefix("www.").trimEnd('/') }.filter { it.startsWith(p) }.distinct()
}

data class ClosedTab(val url: String, val title: String, val time: Long = System.currentTimeMillis())
