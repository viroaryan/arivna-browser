package com.arivna.browser

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

object Io {
    val disk = Executors.newSingleThreadExecutor()
    val net = Executors.newFixedThreadPool(3)
    /** Database reads and image decoding, kept apart from the single writer thread. */
    val read = Executors.newFixedThreadPool(2)
}

fun writeAtomic(file: File, text: String) {
    try {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
    } catch (_: Exception) {
    }
}

internal fun parseNavInput(input: String, searchTemplate: String): String {
    val q = input.trim()
    if (q.isEmpty()) return ""
    if (q.startsWith("https://", true) || q.startsWith("http://", true) ||
        q.startsWith("about:", true) || q.startsWith("file://", true) ||
        q.startsWith("chrome://", true)) {
        return q
    }
    if (q.contains(' ')) {
        return searchTemplate.replace("%s", URLEncoder.encode(q, "UTF-8"))
    }
    val localRegex = Regex("^(localhost|127\\.0\\.0\\.1|192\\.168\\.\\d+\\.\\d+|10\\.\\d+\\.\\d+\\.\\d+)(:\\d+)?(/.*)?$", RegexOption.IGNORE_CASE)
    if (localRegex.matches(q)) {
        return "http://$q"
    }
    val domainRegex = Regex("^[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?(\\.[a-zA-Z0-9]([a-zA-Z0-9-]*[a-zA-Z0-9])?)*\\.([a-zA-Z]{2,24})(:\\d+)?(/.*)?$")
    val match = domainRegex.find(q)
    if (match != null) {
        val tld = match.groupValues[4].lowercase()
        val nonTlds = setOf("js", "ts", "py", "java", "kt", "cpp", "cs", "rb", "go", "php", "html", "css", "json", "xml", "md", "txt", "exe", "apk", "zip", "tar", "gz")
        if (tld !in nonTlds) {
            return "https://$q"
        }
    }
    return searchTemplate.replace("%s", URLEncoder.encode(q, "UTF-8"))
}

enum class SearchEngine(val id: String, val label: String, val searchUrl: String, val suggestUrl: String?, val letter: String) {
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s", "https://suggestqueries.google.com/complete/search?client=firefox&q=%s", "G"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com/ac/?q=%s&type=list", "D"),
    BING("bing", "Bing", "https://www.bing.com/search?q=%s", "https://api.bing.com/osjson.aspx?query=%s", "B"),
    BRAVE("brave", "Brave Search", "https://search.brave.com/search?q=%s", "https://search.brave.com/api/suggest?q=%s", "B"),
    ECOSIA("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s", "https://ac.ecosia.org/autocomplete?q=%s&type=list", "E"),
    STARTPAGE("startpage", "Startpage", "https://www.startpage.com/sp/search?query=%s", null, "S"),
    YAHOO("yahoo", "Yahoo", "https://search.yahoo.com/search?p=%s", null, "Y");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id } ?: GOOGLE
    }
}

data class SearchProvider(
    val id: String,
    val label: String,
    val searchUrl: String,
    val suggestUrl: String?,
    val host: String,
    val builtIn: Boolean,
)

data class Shortcut(val title: String, val url: String)

class Prefs(ctx: Context) {
    private val sp: SharedPreferences = ctx.getSharedPreferences("arivna", Context.MODE_PRIVATE)

    private inner class P<T>(
        private val key: String,
        default: T,
        read: SharedPreferences.(String, T) -> T,
        private val write: SharedPreferences.Editor.(String, T) -> Unit,
    ) : ReadWriteProperty<Any?, T> {
        private val state: MutableState<T> = mutableStateOf(sp.read(key, default))
        override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
            state.value = value
            sp.edit().apply { write(key, value) }.apply()
        }
    }

    private fun bool(k: String, d: Boolean) = P(k, d, { a, b -> getBoolean(a, b) }, { a, b -> putBoolean(a, b) })
    private fun str(k: String, d: String) = P(k, d, { a, b -> getString(a, b) ?: b }, { a, b -> putString(a, b) })
    private fun int(k: String, d: Int) = P(k, d, { a, b -> getInt(a, b) }, { a, b -> putInt(a, b) })
    private fun long(k: String, d: Long) = P(k, d, { a, b -> getLong(a, b) }, { a, b -> putLong(a, b) })

    var theme by str("theme", "system")
    var accent by str("accent", AccentPreset.Terracotta.name)
    var searchEngine by str("searchEngine", SearchEngine.GOOGLE.id)
    var customEnginesRaw by str("customEngines", "")
    var textZoom by int("textZoom", 100)
    var nightMode by bool("nightMode", false)
    var javascript by bool("javascript", true)
    var desktopDefault by bool("desktopDefault", false)
    var pullToRefresh by bool("pullToRefresh", true)
    var shield by bool("shield", true)
    var autoUpdateFilters by bool("autoUpdateFilters", true)
    var lastFilterUpdate by long("lastFilterUpdate", 0L)
    var filterSchemaVersion by int("filterSchemaVersion", 0)
    var shieldStrict by bool("shieldStrict", false)
    var youtubeAds by bool("youtubeAds", true)
    var blockPopups by bool("blockPopups", true)
    var block3pCookies by bool("block3pCookies", false)
    var dnt by bool("dnt", true)
    var allowlistRaw by str("allowlist", "")
    var shortcutsRaw by str("shortcuts", "")
    // Firefox-style settings (see docs/firefox/SPEC.md)
    var addressBar by str("addressBar", "top")                 // "top" | "bottom"
    var tabLayout by str("tabLayout", "grid")                  // "grid" | "list"
    var closeTabs by str("closeTabs", "never")                 // "never" | "day" | "week" | "month"
    var openingScreen by str("openingScreen", "after4h")       // "home" | "last" | "after4h"
    var homeShortcuts by bool("homeShortcuts", true)
    var homeContinue by bool("homeContinue", true)
    var homeBookmarks by bool("homeBookmarks", true)
    var homeRecent by bool("homeRecent", true)
    var homeProtected by bool("homeProtected", true)
    var hideToolbarOnScroll by bool("hideToolbarOnScroll", false)
    var autoFontSize by bool("autoFontSize", true)
    var zoomAllSites by bool("zoomAllSites", false)
    var suggestSearch by bool("suggestSearch", true)
    var suggestPrivate by bool("suggestPrivate", false)
    var suggestRecentSearches by bool("suggestRecentSearches", true)
    var suggestHistory by bool("suggestHistory", true)
    var suggestBookmarks by bool("suggestBookmarks", true)
    var suggestClipboard by bool("suggestClipboard", false)
    var voiceSearch by bool("voiceSearch", true)
    var autocompleteUrls by bool("autocompleteUrls", true)
    var recentSearchesRaw by str("recentSearches", "")        // newline separated, newest first, max 20
    var autoplay by str("autoplay", "blockAudio")              // "allow" | "blockAudio" | "blockAll"
    var permCamera by str("permCamera", "ask")                 // "ask" | "block"
    var permLocation by str("permLocation", "ask")
    var permMicrophone by str("permMicrophone", "ask")
    var permNotification by str("permNotification", "ask")
    var permStorage by str("permStorage", "ask")
    var sitePermsRaw by str("sitePerms", "")                   // "host|kind|ask/block" per line (exceptions)
    var deleteOnQuit by bool("deleteOnQuit", false)
    var quitTabs by bool("quitTabs", true)
    var quitHistory by bool("quitHistory", true)
    var quitCookies by bool("quitCookies", true)
    var quitCache by bool("quitCache", true)
    var quitPerms by bool("quitPerms", false)
    var quitDownloads by bool("quitDownloads", false)
    var openLinksInApps by str("openLinksInApps", "ask")      // "always" | "ask" | "never"
    var deleteDownloadMode by str("deleteDownloadMode", "device") // "device" | "history"
    var askDeleteDownload by bool("askDeleteDownload", true)
    var remoteDebug by bool("remoteDebug", false)
    var lastStop by long("lastStop", 0L)

    val customEngines: List<SearchProvider>
        get() = try {
            val a = JSONArray(customEnginesRaw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val url = o.getString("u")
                val host = android.net.Uri.parse(url.replace("%s", "test")).host ?: return@mapNotNull null
                SearchProvider(o.getString("id"), o.getString("n"), url, null, host, false)
            }
        } catch (_: Exception) { emptyList() }

    val engines: List<SearchProvider>
        get() = SearchEngine.entries.map { e ->
            SearchProvider(e.id, e.label, e.searchUrl, e.suggestUrl, android.net.Uri.parse(e.searchUrl.replace("%s", "test")).host.orEmpty(), true)
        } + customEngines

    val engine: SearchProvider get() = engines.firstOrNull { it.id == searchEngine } ?: engines.first()

    fun addSearchEngine(name: String, template: String) {
        val id = "custom_${System.currentTimeMillis()}"
        val all = customEngines + SearchProvider(id, name, template, null, android.net.Uri.parse(template.replace("%s", "test")).host.orEmpty(), false)
        val a = JSONArray(); all.forEach { a.put(JSONObject().put("id", it.id).put("n", it.label).put("u", it.searchUrl)) }
        customEnginesRaw = a.toString(); searchEngine = id
    }

    fun removeSearchEngine(id: String) {
        val all = customEngines.filterNot { it.id == id }
        val a = JSONArray(); all.forEach { a.put(JSONObject().put("id", it.id).put("n", it.label).put("u", it.searchUrl)) }
        customEnginesRaw = a.toString()
        if (searchEngine == id) searchEngine = SearchEngine.GOOGLE.id
    }

    val allowlist: Set<String>
        get() = allowlistRaw.split('\n').filter { it.isNotBlank() }.toSet()

    fun setAllowed(host: String, allowed: Boolean) {
        val set = allowlist.toMutableSet()
        if (allowed) set.add(host) else set.remove(host)
        allowlistRaw = set.joinToString("\n")
    }

    var shortcuts: List<Shortcut>
        get() {
            if (shortcutsRaw.isBlank()) return DEFAULT_SHORTCUTS
            return try {
                val a = JSONArray(shortcutsRaw)
                (0 until a.length()).map { val o = a.getJSONObject(it); Shortcut(o.getString("t"), o.getString("u")) }
            } catch (_: Exception) {
                DEFAULT_SHORTCUTS
            }
        }
        set(v) {
            val a = JSONArray()
            v.forEach { a.put(JSONObject().put("t", it.title).put("u", it.url)) }
            shortcutsRaw = a.toString()
        }

    fun addShortcut(shortcut: Shortcut) {
        val host = android.net.Uri.parse(shortcut.url).host ?: return
        shortcuts = shortcuts.filterNot { android.net.Uri.parse(it.url).host == host } + shortcut
    }

    fun removeShortcut(shortcut: Shortcut) { shortcuts = shortcuts.filterNot { it.url == shortcut.url } }

    companion object {
        val DEFAULT_SHORTCUTS = listOf(
            Shortcut("Google", "https://www.google.com"),
            Shortcut("YouTube", "https://m.youtube.com"),
            Shortcut("Wikipedia", "https://www.wikipedia.org"),
            Shortcut("Reddit", "https://www.reddit.com"),
            Shortcut("GitHub", "https://github.com"),
            Shortcut("X", "https://x.com"),
            Shortcut("Amazon", "https://www.amazon.in"),
            Shortcut("News", "https://news.google.com"),
        )
    }
}

data class Bookmark(val id: Long, val title: String, val url: String, val created: Long)
/** One level of folders. The bookmarks table has no folder column, so folders live in their own small prefs file. */
data class BookmarkFolder(val id: Long, val name: String)
data class HistoryItem(val id: Long, val url: String, val title: String, val time: Long, val visits: Int = 1)
data class DownloadItem(val id: Long, val url: String, val fileName: String, val mime: String, val time: Long, val size: Long, val dmId: Long = -1L, val fileUri: String = "")

class BookmarksStore(private val ctx: Context) {
    val items = mutableStateListOf<Bookmark>()
    val folders = mutableStateListOf<BookmarkFolder>()
    /** bookmark id -> folder id; bookmarks not in the map sit at the top level. */
    val parents = mutableStateMapOf<Long, Long>()
    private val meta = ctx.getSharedPreferences("bookmark_folders", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private fun saveMeta() {
        val f = JSONArray(); folders.forEach { f.put(JSONObject().put("i", it.id).put("n", it.name)) }
        val p = JSONObject(); parents.forEach { (k, v) -> p.put(k.toString(), v) }
        meta.edit().putString("folders", f.toString()).putString("parents", p.toString()).apply()
    }

    fun addFolder(name: String) { folders.add(BookmarkFolder(System.currentTimeMillis(), name.trim().ifBlank { "New folder" })); saveMeta() }

    fun renameFolder(f: BookmarkFolder, name: String) {
        val i = folders.indexOf(f)
        if (i >= 0 && name.isNotBlank()) { folders[i] = f.copy(name = name.trim()); saveMeta() }
    }

    /** Deleting a folder deletes the bookmarks inside it. */
    fun removeFolder(f: BookmarkFolder) {
        items.filter { parents[it.id] == f.id }.forEach(::remove)
        folders.remove(f); saveMeta()
    }

    fun move(b: Bookmark, folder: BookmarkFolder?) {
        if (folder == null) parents.remove(b.id) else parents[b.id] = folder.id
        saveMeta()
    }

    init {
        try {
            JSONArray(meta.getString("folders", "[]") ?: "[]").let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { folders.add(BookmarkFolder(it.getLong("i"), it.getString("n"))) } }
            JSONObject(meta.getString("parents", "{}") ?: "{}").let { o -> o.keys().forEach { parents[it.toLong()] = o.getLong(it) } }
        } catch (_: Exception) { }
        Io.read.execute {
            importLegacyJson()
            val rows = ArrayList<Bookmark>()
            try {
                AppDb.get(ctx).readableDatabase.rawQuery("SELECT id,url,title,created FROM bookmarks ORDER BY created DESC", null).use { c ->
                    while (c.moveToNext()) {
                        rows.add(Bookmark(c.getLong(0), c.getString(2), c.getString(1), c.getLong(3)))
                    }
                }
            } catch (_: Exception) { }
            main.post {
                items.clear()
                items.addAll(rows)
            }
        }
    }

    private fun write(block: (SQLiteDatabase) -> Unit) = Io.disk.execute {
        try { block(AppDb.get(ctx).writableDatabase) } catch (_: Exception) { }
    }

    fun find(url: String) = items.firstOrNull { it.url == url }
    fun isBookmarked(url: String) = find(url) != null

    fun add(title: String, url: String) {
        if (isBookmarked(url)) return
        val b = Bookmark(System.currentTimeMillis(), title.ifBlank { url }, url, System.currentTimeMillis())
        items.add(0, b)
        write {
            val cv = ContentValues().apply {
                put("id", b.id)
                put("url", b.url)
                put("title", b.title)
                put("created", b.created)
            }
            it.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun remove(b: Bookmark) {
        items.remove(b)
        if (parents.remove(b.id) != null) saveMeta()
        write { it.delete("bookmarks", "url=?", arrayOf(b.url)) }
    }

    fun removeUrl(url: String) {
        find(url)?.let { if (parents.remove(it.id) != null) saveMeta() }
        items.removeAll { it.url == url }
        write { it.delete("bookmarks", "url=?", arrayOf(url)) }
    }

    fun update(b: Bookmark, title: String, url: String) {
        val i = items.indexOfFirst { it.id == b.id }
        if (i >= 0) {
            val updated = b.copy(title = title, url = url)
            items[i] = updated
            write {
                val cv = ContentValues().apply {
                    put("url", updated.url)
                    put("title", updated.title)
                }
                it.update("bookmarks", cv, "id=?", arrayOf(b.id.toString()))
            }
        }
    }

    private fun importLegacyJson() {
        val file = File(ctx.filesDir, "bookmarks.json")
        if (!file.exists()) return
        try {
            val a = JSONArray(file.readText())
            val d = AppDb.get(ctx).writableDatabase
            d.beginTransaction()
            try {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val cv = ContentValues().apply {
                        put("id", o.getLong("id"))
                        put("url", o.getString("u"))
                        put("title", o.getString("t"))
                        put("created", o.getLong("c"))
                    }
                    d.insertWithOnConflict("bookmarks", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        } catch (_: Exception) { }
        file.delete()
    }
}
