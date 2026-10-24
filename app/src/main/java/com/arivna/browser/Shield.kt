package com.arivna.browser

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceResponse
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** JNI surface of the Rust engine in `shield-rs` (libarivna_shield.so). */
object ShieldNative {
    val loaded: Boolean = try {
        System.loadLibrary("arivna_shield"); true
    } catch (_: Throwable) {
        false
    }

    @JvmStatic external fun create(lists: Array<ByteArray>, trusted: BooleanArray): Long
    /** Loads an engine written by [saveCache]; 0 when the file is missing, stale or damaged. */
    @JvmStatic external fun createFromCache(path: String, key: String): Long
    @JvmStatic external fun saveCache(engine: Long, path: String, key: String): Boolean
    @JvmStatic external fun destroy(engine: Long)
    @JvmStatic external fun check(engine: Long, url: String, source: String, type: Int, method: Int): ByteArray?
    @JvmStatic external fun resource(name: String): ByteArray?
    @JvmStatic external fun cleanUrl(engine: Long, url: String, source: String): String?
    @JvmStatic external fun cosmetic(engine: Long, url: String): ByteArray?
    @JvmStatic external fun hidden(engine: Long, url: String, classes: String, ids: String): ByteArray?
    @JvmStatic external fun stats(engine: Long): ByteArray?
    @JvmStatic external fun contentScript(): ByteArray?
}

/** Synchronous bridge used by the document-start content script in every frame. */
class ShieldBridge(private val tab: Tab) {
    @JavascriptInterface
    fun cosmetic(url: String): String = Shield.cosmeticFor(url, tab.pageHost)

    @JavascriptInterface
    fun hidden(url: String, classes: String, ids: String): String = Shield.hiddenFor(url, classes, ids, tab.pageHost)

    /** Called by the element picker when the user taps an element. */
    @JavascriptInterface
    fun pick(selector: String) = tab.onPicked(selector)
}

class NavBridge(private val tab: Tab) {
    @JavascriptInterface
    fun navigationIntent(url: String) = tab.recordNavigationIntent(url)
}

data class ShieldInfo(
    val domains: Int = 0,
    val paths: Int = 0,
    val cosmetic: Int = 0,
    val updated: String = "",
    val loading: Boolean = true,
    val buildMs: Int = 0,
    /** Time to load the engine from the binary cache (0 when it was built from the lists). */
    val loadMs: Int = 0,
    val cached: Boolean = false,
)

data class ShieldStats(val total: Long = 0, val popups: Long = 0)

class Verdict(val blocked: Boolean, val important: Boolean, val exception: Boolean, val filter: String, val redirect: String)

object Shield {
    // Request types, mirrored from shield-rs/src/request.rs
    const val T_DOCUMENT = 1
    const val T_SUBDOCUMENT = 1 shl 1
    const val T_STYLESHEET = 1 shl 2
    const val T_SCRIPT = 1 shl 3
    const val T_IMAGE = 1 shl 4
    const val T_FONT = 1 shl 5
    const val T_OBJECT = 1 shl 6
    const val T_XHR = 1 shl 7
    const val T_PING = 1 shl 8
    const val T_MEDIA = 1 shl 9
    const val T_WEBSOCKET = 1 shl 10
    const val T_OTHER = 1 shl 11
    const val T_POPUP = 1 shl 12

    @Volatile var enabled = true
    @Volatile var strict = false
    @Volatile var youtube = true
    @Volatile var gpc = true
    @Volatile var allow: Set<String> = emptySet()
    @Volatile var ready = false

    @Volatile private var engine = 0L
    @Volatile private var builtStrict: Boolean? = null
    private var appCtx: Context? = null

    private val totalBlocked = AtomicLong()
    private val popupsBlocked = AtomicLong()
    private val resources = ConcurrentHashMap<String, Pair<String, ByteArray>>()
    private val main = Handler(Looper.getMainLooper())
    private lateinit var statsFile: File
    private val retiredEngines = ConcurrentLinkedQueue<Pair<Long, Long>>()

    private fun retireEngine(ptr: Long) {
        if (ptr == 0L) return
        retiredEngines.add(ptr to SystemClock.elapsedRealtime())
        Io.disk.execute { cleanupRetiredEngines() }
    }

    private fun cleanupRetiredEngines() {
        val now = SystemClock.elapsedRealtime()
        val it = retiredEngines.iterator()
        while (it.hasNext()) {
            val (ptr, retiredAt) = it.next()
            if (now - retiredAt >= 15_000) {
                it.remove()
                try { ShieldNative.destroy(ptr) } catch (_: Throwable) { }
            }
        }
    }

    val info = mutableStateOf(ShieldInfo())
    val stats = mutableStateOf(ShieldStats())
    val revision = mutableIntStateOf(0)

    /** Lists compiled into the engine; uBlock Origin's own lists are trusted for `trusted-*` scriptlets. */
    private val STANDARD = listOf(
        "arivna-builtin", "ublock-filters", "ublock-privacy", "ublock-badware", "ublock-quick-fixes", "ublock-unbreak",
        "easylist", "easyprivacy", "peterlowe", "urlhaus", "adguard-mobile", "adguard-tracking", UserFilters.NAME,
    )
    /** Lists compiled as trusted: uBlock Origin's own, Arivna's built-in rules and the user's picker rules. */
    private fun trustedList(name: String) = name.startsWith("ublock-") || name == "arivna-builtin" || name == UserFilters.NAME
    private val STRICT = listOf("ublock-cookies", "easylist-cookies")
    private const val TAG = "Shield"
    /** Bump when the set of lists or how they are compiled changes, so stale engine caches are dropped. */
    private const val CACHE_REV = 2
    private const val UBO = "https://raw.githubusercontent.com/uBlockOrigin/uAssets/master/filters"
    private val SOURCES = mapOf(
        "ublock-filters" to "$UBO/filters.txt",
        "ublock-privacy" to "$UBO/privacy.txt",
        "ublock-badware" to "$UBO/badware.txt",
        "ublock-quick-fixes" to "$UBO/quick-fixes.txt",
        "ublock-unbreak" to "$UBO/unbreak.txt",
        "ublock-cookies" to "$UBO/annoyances-cookies.txt",
        "easylist" to "https://easylist.to/easylist/easylist.txt",
        "easyprivacy" to "https://easylist.to/easylist/easyprivacy.txt",
        "peterlowe" to "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=hosts&showintro=1&mimetype=plaintext",
        "urlhaus" to "https://malware-filter.gitlab.io/malware-filter/urlhaus-filter-ag-online.txt",
        "adguard-mobile" to "https://filters.adtidy.org/extension/ublock/filters/11.txt",
        "adguard-tracking" to "https://filters.adtidy.org/extension/ublock/filters/17.txt",
        "easylist-cookies" to "https://secure.fanboy.co.nz/fanboy-cookiemonster_ubo.txt",
    )

    val YOUTUBE_ORIGINS = setOf("https://www.youtube.com", "https://m.youtube.com", "https://youtube.com", "https://music.youtube.com")

    private val contentJs: String by lazy {
        if (!ShieldNative.loaded) "" else ShieldNative.contentScript()?.toString(Charsets.UTF_8) ?: ""
    }

    fun init(ctx: Context, prefs: Prefs) {
        appCtx = ctx.applicationContext
        enabled = prefs.shield
        strict = prefs.shieldStrict
        youtube = prefs.youtubeAds
        gpc = prefs.dnt
        allow = prefs.allowlist
        if (prefs.filterSchemaVersion < 4) { // drop the pre-4 list cache once; lists still update on the normal schedule
            prefs.filterSchemaVersion = 4
            File(ctx.filesDir, "adblock").deleteRecursively()
        }
        statsFile = File(ctx.filesDir, "shield_stats.json")
        try {
            val o = JSONObject(statsFile.readText())
            totalBlocked.set(o.optLong("total"))
            popupsBlocked.set(o.optLong("popups"))
        } catch (_: Exception) {
        }
        publishStats()
        rebuild()
        main.postDelayed(object : Runnable {
            override fun run() { publishStats(); main.postDelayed(this, 2000) }
        }, 2000)
    }

    fun sync(prefs: Prefs) {
        enabled = prefs.shield
        strict = prefs.shieldStrict
        youtube = prefs.youtubeAds
        gpc = prefs.dnt
        allow = prefs.allowlist
        if (builtStrict != null && builtStrict != strict) rebuild()
    }

    private fun listBytes(ctx: Context, name: String): ByteArray? {
        val f = File(File(ctx.filesDir, "filters"), "$name.txt")
        if (f.exists() && f.length() > 0) return try { f.readBytes() } catch (_: Exception) { null }
        return try { ctx.assets.open("filters/$name.txt").use { it.readBytes() } } catch (_: Exception) { null }
    }

    /**
     * Fingerprint of everything the engine is built from: the app install, the strict flag and each
     * list's size and modification time. Bundled lists only change with the app, so they cost nothing to check.
     */
    private fun cacheKey(ctx: Context, names: List<String>): String {
        val pkg = try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).lastUpdateTime } catch (_: Exception) { 0L }
        val dir = File(ctx.filesDir, "filters")
        val sb = StringBuilder("v$CACHE_REV|pkg=$pkg|")
        for (n in names) {
            val f = File(dir, "$n.txt")
            if (f.exists() && f.length() > 0) sb.append(n).append('=').append(f.length()).append('@').append(f.lastModified())
            else sb.append(n).append("=bundled")
            sb.append(';').append(if (trustedList(n)) 'T' else 'U').append('|')
        }
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Makes the engine for the current lists and swaps it in. Loads the binary cache when the lists are
     * unchanged; otherwise compiles the lists and writes a new cache. Runs on a background thread.
     */
    fun rebuild(onReady: (() -> Unit)? = null) {
        val ctx = appCtx ?: return
        if (!ShieldNative.loaded) {
            main.post { info.value = info.value.copy(loading = false) }
            return
        }
        val wantStrict = strict
        Io.disk.execute {
            val names = STANDARD + if (wantStrict) STRICT else emptyList()
            val cacheFile = File(ctx.filesDir, if (wantStrict) "shield-engine-strict.bin" else "shield-engine.bin")
            val key = cacheKey(ctx, names)
            var ptr = try { ShieldNative.createFromCache(cacheFile.path, key) } catch (_: Throwable) { 0L }
            val fromLists = ptr == 0L
            if (fromLists) {
                val lists = names.mapNotNull { n -> listBytes(ctx, n)?.let { n to it } }
                ptr = try {
                    ShieldNative.create(lists.map { it.second }.toTypedArray(), BooleanArray(lists.size) { trustedList(lists[it].first) })
                } catch (_: Throwable) {
                    0L
                }
            }
            if (ptr == 0L) return@execute
            val old = engine
            engine = ptr
            builtStrict = wantStrict
            ready = true
            // Requests may still be in flight on the old engine; retire it safely in background.
            if (old != 0L) retireEngine(old)
            val st = try { JSONObject(ShieldNative.stats(ptr)?.toString(Charsets.UTF_8) ?: "{}") } catch (_: Exception) { JSONObject() }
            val updatedFile = File(File(ctx.filesDir, "filters"), "updated.txt")
            val updated = if (updatedFile.exists()) updatedFile.readText().take(10) else "bundled"
            val cachedHit = st.optBoolean("cached")
            Log.i(TAG, "engine ${if (cachedHit) "loaded from cache in ${st.optInt("loadMs")} ms" else "built from lists in ${st.optInt("buildMs")} ms"}, " +
                "ready ${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()} ms after process start")
            main.post {
                info.value = ShieldInfo(
                    domains = st.optInt("hostnames"),
                    paths = st.optInt("network") - st.optInt("hostnames"),
                    cosmetic = st.optInt("cosmetic"),
                    updated = updated,
                    loading = false,
                    buildMs = st.optInt("buildMs"),
                    loadMs = st.optInt("loadMs"),
                    cached = st.optBoolean("cached"),
                )
                resources.clear()
                revision.intValue++
                onReady?.invoke()
            }
            if (fromLists) try { ShieldNative.saveCache(ptr, cacheFile.path, key) } catch (_: Throwable) { }
        }
    }

    fun isAllowlisted(host: String): Boolean {
        if (host.isEmpty()) return false
        val a = allow
        if (a.isEmpty()) return false
        var h = host
        while (true) {
            if (a.contains(h)) return true
            val i = h.indexOf('.')
            if (i < 0) return false
            h = h.substring(i + 1)
        }
    }

    private fun methodBits(m: String): Int = when (if (m == "GET") m else m.uppercase()) { // "GET" is nearly every request: skip the allocation
        "GET" -> 1; "POST" -> 2; "HEAD" -> 4; "PUT" -> 8; "DELETE" -> 16; "PATCH" -> 32; "OPTIONS" -> 64; else -> 0
    }

    fun typeBits(type: String): Int = when (type) {
        "document" -> T_DOCUMENT
        "subdocument" -> T_SUBDOCUMENT
        "stylesheet" -> T_STYLESHEET
        "script" -> T_SCRIPT
        "image" -> T_IMAGE
        "font" -> T_FONT
        "object" -> T_OBJECT
        "xmlhttprequest" -> T_XHR
        "ping" -> T_PING
        "media" -> T_MEDIA
        "websocket" -> T_WEBSOCKET
        "popup" -> T_POPUP
        else -> T_OTHER
    }

    private fun sourceUrl(pageHostOrUrl: String): String = when {
        pageHostOrUrl.isEmpty() -> ""
        pageHostOrUrl.contains("://") -> pageHostOrUrl
        else -> "https://$pageHostOrUrl/"
    }

    /** Full engine verdict, or null when no filter matched. */
    fun match(url: String, source: String, type: Int, method: String = "GET"): Verdict? {
        val e = engine
        if (e == 0L || !enabled) return null
        val raw = try { ShieldNative.check(e, url, source, type, methodBits(method)) } catch (_: Throwable) { null }
        if (raw == null) return null
        val p = raw.toString(Charsets.UTF_8).split('\t')
        val code = p.getOrNull(0)?.toIntOrNull() ?: return null
        return Verdict(code and 1 != 0, code and 2 != 0, code and 4 != 0, p.getOrNull(1).orEmpty(), p.getOrNull(2).orEmpty())
    }

    fun typeName(type: Int): String = when (type) {
        T_DOCUMENT -> "document"
        T_SUBDOCUMENT -> "subdocument"
        T_STYLESHEET -> "stylesheet"
        T_SCRIPT -> "script"
        T_IMAGE -> "image"
        T_FONT -> "font"
        T_OBJECT -> "object"
        T_XHR -> "xhr"
        T_PING -> "ping"
        T_MEDIA -> "media"
        T_WEBSOCKET -> "websocket"
        T_POPUP -> "popup"
        else -> "other"
    }

    /**
     * Decides a subresource request; returns the response to use instead, or null to let it load.
     * Every decision is recorded in [log] when one is given.
     */
    fun intercept(url: Uri, pageUrl: String, type: Int, method: String, log: RequestLog? = null, onBlocked: () -> Unit): WebResourceResponse? {
        val u = url.toString()
        val v = match(u, pageUrl, type, method)
        val response: WebResourceResponse?
        val action: RequestAction
        if (v == null || !v.blocked) {
            response = null
            action = RequestAction.ALLOWED
        } else {
            onBlocked()
            val redirected = if (v.redirect.isNotEmpty()) resourceResponse(v.redirect) else null
            response = redirected ?: emptyResponse()
            action = if (redirected != null) RequestAction.REDIRECTED else RequestAction.BLOCKED
        }
        log?.add(RequestEntry(System.currentTimeMillis(), u, typeName(type), action, v?.filter.orEmpty()))
        return response
    }

    /** Compatibility helper: returns the blocked host, or null. */
    fun check(url: Uri, pageHost: String, type: String = "other"): String? {
        val v = match(url.toString(), sourceUrl(pageHost), typeBits(type)) ?: return null
        return if (v.blocked) url.host?.lowercase() ?: v.filter else null
    }

    fun checkNavigation(url: Uri, sourceHost: String, popup: Boolean): String? {
        if (!enabled || isAllowlisted(sourceHost)) return null
        val v = match(url.toString(), sourceUrl(sourceHost), if (popup) T_POPUP else T_DOCUMENT) ?: return null
        return if (v.blocked) url.host?.lowercase() ?: v.filter else null
    }

    /** Strips tracking parameters (`$removeparam`) from a navigation URL. */
    fun cleanUrl(url: String, source: String): String? {
        val e = engine
        if (e == 0L || !enabled) return null
        return try { ShieldNative.cleanUrl(e, url, source) } catch (_: Throwable) { null }
    }

    private fun resourceResponse(name: String): WebResourceResponse? {
        val r = resources[name] ?: run {
            val raw = try { ShieldNative.resource(name) } catch (_: Throwable) { null } ?: return null
            val nl = raw.indexOf('\n'.code.toByte())
            if (nl < 0) return null
            val pair = String(raw, 0, nl, Charsets.UTF_8) to raw.copyOfRange(nl + 1, raw.size)
            resources[name] = pair
            pair
        }
        return WebResourceResponse(
            r.first, "utf-8", 200, "OK",
            mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "no-store"),
            ByteArrayInputStream(r.second),
        )
    }

    fun cosmeticFor(url: String, topHost: String): String {
        val e = engine
        if (e == 0L || !enabled || isAllowlisted(topHost) || isAllowlisted(Uri.parse(url).host?.lowercase().orEmpty())) return "{\"off\":true}"
        return try { ShieldNative.cosmetic(e, url)?.toString(Charsets.UTF_8) } catch (_: Throwable) { null } ?: "{\"off\":true}"
    }

    fun hiddenFor(url: String, classes: String, ids: String, topHost: String): String {
        val e = engine
        if (e == 0L || !enabled || isAllowlisted(topHost)) return "[]"
        return try { ShieldNative.hidden(e, url, classes, ids)?.toString(Charsets.UTF_8) } catch (_: Throwable) { null } ?: "[]"
    }

    @Volatile private var statsDirty = true

    fun onBlocked() {
        totalBlocked.incrementAndGet()
        statsDirty = true
    }

    fun onPopupBlocked() { popupsBlocked.incrementAndGet(); statsDirty = true }

    private fun publishStats() {
        if (!statsDirty) return // nothing was blocked since the last time: no sorting, no recomposition
        statsDirty = false
        val s = ShieldStats(totalBlocked.get(), popupsBlocked.get())
        if (s != stats.value) stats.value = s
    }

    fun saveStats() {
        if (!::statsFile.isInitialized) return
        val text = JSONObject().put("total", totalBlocked.get()).put("popups", popupsBlocked.get()).toString()
        Io.disk.execute { writeAtomic(statsFile, text) }
    }

    fun emptyResponse() = WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Document-start script for every frame: scriptlets + cosmetic runtime (talks to [ShieldBridge]). */
    fun documentScript(): String {
        val gpcJs = if (gpc) "try{Object.defineProperty(Navigator.prototype,'globalPrivacyControl',{get:function(){return true},configurable:true});}catch(e){}\n" else ""
        return gpcJs + contentJs
    }

    /** Last-resort YouTube cleanup: skips any ad that still reaches the player. */
    const val YOUTUBE_SCRIPT = """(function(){
if(window.__arivnaYT)return;window.__arivnaYT=1;
var css='.ytp-ad-module,.ytp-ad-overlay-container,.ytp-ad-image-overlay,.ytp-ad-text-overlay,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-promoted-sparkles-web-renderer,ytd-display-ad-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,ytd-companion-slot-renderer,ytd-player-legacy-desktop-watch-ads-renderer,ytd-action-companion-ad-renderer,ytd-promoted-video-renderer,#player-ads,#masthead-ad,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-promoted-video-renderer,ytm-promoted-sparkles-text-search-renderer,ad-slot-renderer,ytm-ad-slot-renderer,ytd-merch-shelf-renderer,ytm-merch-shelf-renderer,ytm-mealbar-promo-renderer,.video-ads{display:none!important}';
function addCss(){var s=document.createElement('style');s.textContent=css;(document.head||document.documentElement).appendChild(s);}
if(document.documentElement)addCss();else document.addEventListener('DOMContentLoaded',addCss);
var muted=false;
setInterval(function(){try{
var ad=document.querySelector('.ad-showing,.ad-interrupting');var v=document.querySelector('video');
if(ad&&v){if(!muted){muted=true;v.muted=true;}if(isFinite(v.duration)&&v.duration>0&&v.currentTime<v.duration-0.3){v.currentTime=v.duration-0.1;}v.playbackRate=16;}
else if(muted&&v){muted=false;v.muted=false;v.playbackRate=1;}
var b=document.querySelectorAll('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button,.ytm-skip-ad-button,button[class*="skip-ad"],button[class*="ad-skip"]');
for(var i=0;i<b.length;i++){try{b[i].click();}catch(e){}}
var o=document.querySelectorAll('.ytp-ad-overlay-close-button');for(var j=0;j<o.length;j++){try{o[j].click();}catch(e){}}
}catch(e){}},250);
})();"""

    // ---------- Filter list updates ----------

    /** Auto-update runs on launch and on return to the foreground once the lists are older than this. */
    const val UPDATE_INTERVAL_MS = 24L * 60 * 60 * 1000

    private fun fetchText(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 Arivna")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    /** Inlines `!#include` sub-lists, which uBlock Origin's lists rely on. */
    private fun expandIncludes(text: String, base: String, depth: Int = 0): String {
        if (!text.contains("!#include") || depth > 3) return text
        val sb = StringBuilder(text.length + 1024)
        for (line in text.lineSequence()) {
            val m = Regex("^!#include\\s+(\\S+)").find(line)
            if (m == null) { sb.append(line).append('\n'); continue }
            val url = URL(URL(base), m.groupValues[1]).toString()
            sb.append(expandIncludes(fetchText(url), url, depth + 1)).append('\n')
        }
        return sb.toString()
    }

    /**
     * Downloads one list with a conditional request. Returns true when the list changed, false when it is
     * the same (304 or identical body), null when it failed. [tags] holds name -> [ETag, Last-Modified].
     */
    private fun fetchList(dir: File, name: String, url: String, tags: JSONObject): Boolean? {
        var code = -1
        var bytes = 0
        var changed: Boolean? = null
        try {
            val dest = File(dir, "$name.txt")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("User-Agent", "Mozilla/5.0 Arivna")
            val old = synchronized(tags) { tags.optJSONArray(name) }
            if (old != null && dest.length() > 0) { // validators only describe the file while it exists
                old.optString(0).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("If-None-Match", it) }
                old.optString(1).takeIf { it.isNotEmpty() }?.let { c.setRequestProperty("If-Modified-Since", it) }
            }
            code = c.responseCode
            if (code == 304) changed = false
            else if (code == 200) {
                val text = expandIncludes(c.inputStream.bufferedReader().use { it.readText() }, url)
                bytes = text.length
                if (text.length >= 500) {
                    changed = !(dest.exists() && dest.readText() == text)
                    if (changed == true) {
                        val tmp = File(dir, "$name.txt.tmp")
                        tmp.writeText(text)
                        if (!tmp.renameTo(dest)) { dest.delete(); tmp.renameTo(dest) }
                    }
                    // shortcut: a list with !#include is refetched every time, since its ETag only covers the top file
                    val etag = c.getHeaderField("ETag").orEmpty()
                    val lm = c.getHeaderField("Last-Modified").orEmpty()
                    synchronized(tags) {
                        if ((etag.isNotEmpty() || lm.isNotEmpty()) && !text.contains("!#include")) tags.put(name, org.json.JSONArray().put(etag).put(lm)) else tags.remove(name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "list $name failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        Log.i(TAG, "list $name http=$code bytes=$bytes changed=$changed")
        return changed
    }

    /** Refreshes every list (4 at a time), then rebuilds the engine only if one changed. */
    fun updateLists(ctx: Context, done: (Boolean, String) -> Unit) {
        Io.net.execute {
            val pool = Executors.newFixedThreadPool(4)
            try {
                val dir = File(ctx.filesDir, "filters").apply { mkdirs() }
                val etagFile = File(dir, "etags.json")
                val tags = try { JSONObject(etagFile.readText()) } catch (_: Exception) { JSONObject() }
                val results = pool.invokeAll(SOURCES.map { (name, url) -> Callable { fetchList(dir, name, url, tags) } }).map { it.get() }
                val changed = results.count { it == true }
                val failed = results.count { it == null }
                Log.i(TAG, "update done: $changed changed, ${results.size - changed - failed} unchanged, $failed failed")
                if (failed == results.size) throw IllegalStateException("Couldn't reach the filter list servers; existing protection was kept")
                writeAtomic(etagFile, tags.toString())
                val today = java.time.LocalDate.now().toString()
                File(dir, "updated.txt").writeText(today)
                if (changed > 0) rebuild() else main.post { info.value = info.value.copy(updated = today) }
                val msg = (if (changed > 0) "Updated just now · $changed of ${results.size} lists changed" else "Already up to date") + if (failed > 0) " · $failed failed" else ""
                main.post { done(true, msg) }
            } catch (e: Exception) {
                main.post { done(false, e.message ?: "Update failed") }
            } finally {
                pool.shutdown()
            }
        }
    }
}
