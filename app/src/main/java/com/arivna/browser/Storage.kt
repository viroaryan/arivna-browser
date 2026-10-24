package com.arivna.browser

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// ---------------------------------------------------------------------------------------------
// One small SQLite database for everything that grows: history and downloads.
//
// Why a database instead of JSON files: a JSON file is rewritten completely on every change and parsed
// completely at startup, so cost grows with the size of the history. Here a visit is a single indexed
// row write, startup reads nothing, the UI loads one page at a time, and old rows are pruned.

class AppDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "arivna.db", null, 2) {
    init {
        // Readers never block the writer (and the other way round).
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL DEFAULT '', " +
                "key TEXT NOT NULL, last_visit INTEGER NOT NULL, visits INTEGER NOT NULL DEFAULT 1)",
        )
        db.execSQL("CREATE INDEX history_time ON history(last_visit)")
        db.execSQL("CREATE INDEX history_key ON history(key)")
        db.execSQL(
            "CREATE TABLE downloads(id INTEGER PRIMARY KEY, url TEXT NOT NULL, name TEXT NOT NULL, mime TEXT NOT NULL DEFAULT '', " +
                "time INTEGER NOT NULL, size INTEGER NOT NULL DEFAULT 0, dm_id INTEGER NOT NULL DEFAULT -1, file_uri TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL("CREATE INDEX downloads_time ON downloads(time)")
        db.execSQL("CREATE TABLE bookmarks(id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX bookmarks_created ON bookmarks(created)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE TABLE IF NOT EXISTS bookmarks(id INTEGER PRIMARY KEY, url TEXT NOT NULL UNIQUE, title TEXT NOT NULL DEFAULT '', created INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS bookmarks_created ON bookmarks(created)")
        }
    }

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(ctx: Context): AppDb = instance ?: synchronized(this) { instance ?: AppDb(ctx).also { instance = it } }
    }
}

private val mainHandler = Handler(Looper.getMainLooper())

/** Escapes `%`, `_` and `\` so user text is matched literally by LIKE ... ESCAPE '\'. */
private fun likeEscape(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

/** Adds a visit to a page, creating the row when it is new. Must run inside a transaction. */
private fun upsertVisit(d: SQLiteDatabase, url: String, title: String, time: Long) {
    d.execSQL(
        "UPDATE history SET last_visit=MAX(last_visit,?), visits=visits+1, title=CASE WHEN ?<>'' THEN ? ELSE title END WHERE url=?",
        arrayOf<Any>(time, title, title, url),
    )
    if (d.compileStatement("SELECT changes()").simpleQueryForLong() == 0L) {
        d.insert("history", null, ContentValues().apply {
            put("url", url); put("title", title); put("key", historyKey(url)); put("last_visit", time); put("visits", 1)
        })
    }
}

/** `https://www.Example.com/a` -> `example.com/a`: what prefix searches are matched against. */
fun historyKey(url: String): String = url.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")

// ---------------------------------------------------------------------------------------------
// History

class HistoryStore(private val ctx: Context) {
    /** The newest few distinct pages, kept in memory for the home screen. Everything else stays in the database. */
    val recent = mutableStateListOf<HistoryItem>()
    /** Total number of pages in the history. */
    var count by mutableIntStateOf(0)
        private set
    /** Bumped whenever the history changes, so open screens know to reload. */
    var revision by mutableIntStateOf(0)
        private set

    private val refreshQueued = AtomicBoolean(false)

    init {
        Io.disk.execute {
            importLegacyJson()
            prune()
            refresh()
        }
    }

    private fun db(): SQLiteDatabase = AppDb.get(ctx).writableDatabase

    /** Records a visit. One row per page: the visit count and time are updated instead of adding rows. */
    fun record(url: String, title: String) {
        if (!url.startsWith("http")) return
        val clean = url.substringBefore('#')
        val now = System.currentTimeMillis()
        Io.disk.execute {
            try {
                val d = db()
                d.beginTransaction()
                try {
                    upsertVisit(d, clean, title, now)
                    d.setTransactionSuccessful()
                } finally { d.endTransaction() }
            } catch (_: Exception) { }
            scheduleRefresh()
        }
    }

    fun updateTitle(url: String, title: String) {
        if (title.isBlank()) return
        val clean = url.substringBefore('#')
        Io.disk.execute {
            try { db().execSQL("UPDATE history SET title=? WHERE url=? AND title<>?", arrayOf(title, clean, title)) } catch (_: Exception) { }
            scheduleRefresh()
        }
    }

    fun remove(item: HistoryItem) {
        recent.remove(item)
        Io.disk.execute {
            try { db().delete("history", "id=?", arrayOf(item.id.toString())) } catch (_: Exception) { }
            scheduleRefresh()
        }
    }

    fun clear() {
        recent.clear(); count = 0; revision++
        Io.disk.execute {
            try { db().execSQL("DELETE FROM history"); db().execSQL("VACUUM") } catch (_: Exception) { }
            scheduleRefresh()
        }
    }

    /** A page of history, newest first, optionally filtered by [query]. [done] is called on the main thread. */
    fun page(before: Long, limit: Int, query: String, done: (List<HistoryItem>) -> Unit) {
        Io.read.execute {
            val out = ArrayList<HistoryItem>(limit)
            try {
                val q = query.trim().lowercase()
                val sql: String
                val args: Array<String>
                if (q.isEmpty()) {
                    sql = "SELECT id,url,title,last_visit,visits FROM history WHERE last_visit<? ORDER BY last_visit DESC LIMIT ?"
                    args = arrayOf(before.toString(), limit.toString())
                } else {
                    val like = "%" + likeEscape(q) + "%"
                    sql = "SELECT id,url,title,last_visit,visits FROM history WHERE last_visit<? AND (key LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\') ORDER BY last_visit DESC LIMIT ?"
                    args = arrayOf(before.toString(), like, like, limit.toString())
                }
                AppDb.get(ctx).readableDatabase.rawQuery(sql, args).use { c ->
                    while (c.moveToNext()) out.add(HistoryItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)))
                }
            } catch (_: Exception) { }
            mainHandler.post { done(out) }
        }
    }

    /** Address-bar suggestions: pages starting with the text first (index range scan), then pages containing it. */
    fun suggest(q: String, limit: Int, done: (List<HistoryItem>) -> Unit) {
        val query = q.trim().lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.")
        if (query.isEmpty()) { done(emptyList()); return }
        Io.read.execute {
            val out = LinkedHashMap<Long, HistoryItem>()
            try {
                val rd = AppDb.get(ctx).readableDatabase
                rd.rawQuery(
                    "SELECT id,url,title,last_visit,visits FROM history WHERE key>=? AND key<? ORDER BY visits DESC, last_visit DESC LIMIT ?",
                    arrayOf(query, query + "￿", limit.toString()),
                ).use { c -> while (c.moveToNext()) out[c.getLong(0)] = HistoryItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)) }
                if (out.size < limit) {
                    val like = "%" + likeEscape(query) + "%"
                    rd.rawQuery(
                        "SELECT id,url,title,last_visit,visits FROM history WHERE key LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' ORDER BY last_visit DESC LIMIT ?",
                        arrayOf(like, like, (limit * 3).toString()),
                    ).use { c ->
                        while (c.moveToNext() && out.size < limit) {
                            val id = c.getLong(0)
                            if (id !in out) out[id] = HistoryItem(id, c.getString(1), c.getString(2), c.getLong(3), c.getInt(4))
                        }
                    }
                }
            } catch (_: Exception) { }
            val list = out.values.toList()
            mainHandler.post { done(list) }
        }
    }

    // ----- housekeeping -----

    private fun scheduleRefresh() {
        if (refreshQueued.compareAndSet(false, true)) mainHandler.postDelayed({ refreshQueued.set(false); Io.read.execute { refresh() } }, 600)
    }

    private fun refresh() {
        try {
            val rd = AppDb.get(ctx).readableDatabase
            val rows = ArrayList<HistoryItem>(40)
            rd.rawQuery("SELECT id,url,title,last_visit,visits FROM history ORDER BY last_visit DESC LIMIT 40", null).use { c ->
                while (c.moveToNext()) rows.add(HistoryItem(c.getLong(0), c.getString(1), c.getString(2), c.getLong(3), c.getInt(4)))
            }
            val n = rd.compileStatement("SELECT COUNT(*) FROM history").simpleQueryForLong().toInt()
            mainHandler.post {
                if (recent.toList() != rows) { recent.clear(); recent.addAll(rows) }
                count = n
                revision++
            }
        } catch (_: Exception) { }
    }

    /** Keeps the database small: at most a year of history and 100,000 pages. */
    private fun prune() {
        try {
            val d = db()
            val yearAgo = System.currentTimeMillis() - 365L * 24 * 60 * 60 * 1000
            d.execSQL("DELETE FROM history WHERE last_visit<?", arrayOf(yearAgo))
            val n = d.compileStatement("SELECT COUNT(*) FROM history").simpleQueryForLong()
            if (n > MAX_ROWS) d.execSQL("DELETE FROM history WHERE id IN (SELECT id FROM history ORDER BY last_visit ASC LIMIT ?)", arrayOf(n - MAX_ROWS))
        } catch (_: Exception) { }
    }

    /** One-time import of the old history.json. */
    private fun importLegacyJson() {
        val file = File(ctx.filesDir, "history.json")
        if (!file.exists()) return
        try {
            val a = JSONArray(file.readText())
            val d = db()
            d.beginTransaction()
            try {
                // the file is newest first; walk oldest first so repeated pages end with their latest time
                for (i in a.length() - 1 downTo 0) {
                    val o = a.getJSONObject(i)
                    val url = o.getString("u").substringBefore('#')
                    val t = o.getLong("w")
                    upsertVisit(d, url, o.optString("t"), t)
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        } catch (_: Exception) {
            // a damaged file: the history just starts fresh
        }
        file.delete()
    }

    private companion object { const val MAX_ROWS = 100_000L }
}

// ---------------------------------------------------------------------------------------------
// Downloads list

class DownloadsStore(private val ctx: Context) {
    val items = mutableStateListOf<DownloadItem>()

    init {
        Io.read.execute {
            importLegacyJson()
            val rows = ArrayList<DownloadItem>()
            try {
                AppDb.get(ctx).readableDatabase.rawQuery("SELECT id,url,name,mime,time,size,dm_id,file_uri FROM downloads ORDER BY time DESC LIMIT 1000", null).use { c ->
                    while (c.moveToNext()) rows.add(DownloadItem(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getLong(5), c.getLong(6), c.getString(7)))
                }
            } catch (_: Exception) { }
            mainHandler.post { items.addAll(rows) }
        }
    }

    private fun write(block: (SQLiteDatabase) -> Unit) = Io.disk.execute { try { block(AppDb.get(ctx).writableDatabase) } catch (_: Exception) { } }

    fun add(d: DownloadItem) {
        items.add(0, d)
        write { it.insertWithOnConflict("downloads", null, values(d), SQLiteDatabase.CONFLICT_REPLACE) }
    }

    /** Records where a finished download was saved. */
    fun finish(dmId: Long, uri: String, size: Long, name: String) {
        val i = items.indexOfFirst { it.dmId == dmId }
        if (i < 0) return
        val d = items[i].copy(fileUri = uri, size = size, fileName = name)
        items[i] = d
        write { it.insertWithOnConflict("downloads", null, values(d), SQLiteDatabase.CONFLICT_REPLACE) }
    }

    fun remove(d: DownloadItem) {
        items.remove(d)
        write { it.delete("downloads", "id=?", arrayOf(d.id.toString())) }
    }

    fun clear() {
        items.clear()
        write { it.execSQL("DELETE FROM downloads") }
    }

    private fun values(d: DownloadItem) = ContentValues().apply {
        put("id", d.id); put("url", d.url); put("name", d.fileName); put("mime", d.mime)
        put("time", d.time); put("size", d.size); put("dm_id", d.dmId); put("file_uri", d.fileUri)
    }

    private fun importLegacyJson() {
        val file = File(ctx.filesDir, "downloads.json")
        if (!file.exists()) return
        try {
            val a = JSONArray(file.readText())
            val d = AppDb.get(ctx).writableDatabase
            d.beginTransaction()
            try {
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    d.insertWithOnConflict("downloads", null, ContentValues().apply {
                        put("id", o.getLong("id")); put("url", o.getString("u")); put("name", o.getString("f")); put("mime", o.optString("m"))
                        put("time", o.getLong("w")); put("size", o.optLong("s")); put("dm_id", o.optLong("d", -1L)); put("file_uri", o.optString("x"))
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                }
                d.setTransactionSuccessful()
            } finally { d.endTransaction() }
        } catch (_: Exception) { }
        file.delete()
    }
}

// ---------------------------------------------------------------------------------------------
// Favicons: decoded off the main thread, bounded on disk

class FaviconCache(ctx: Context) {
    private val dir = File(ctx.filesDir, "favicons").apply { mkdirs() }
    // 96 px icons are ~37 KB decoded; 120 of them are ~4.5 MB.
    private val mem = LruCache<String, Bitmap>(120)
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    val version = mutableIntStateOf(0)

    init { Io.disk.execute { Thread.sleep(4000); prune() } }

    /** The icon if it is already in memory. Otherwise null, and it is loaded in the background (the UI is told through [version]). */
    fun get(host: String): Bitmap? {
        if (host.isEmpty()) return null
        mem.get(host)?.let { return it }
        if (!missing.contains(host) && loading.add(host)) {
            Io.read.execute {
                val bmp = try { File(dir, "$host.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } } catch (_: Exception) { null }
                mainHandler.post {
                    if (bmp != null) { mem.put(host, bmp); version.intValue++ } else missing.add(host)
                    loading.remove(host)
                }
            }
        }
        return null
    }

    fun put(host: String, bmp: Bitmap) {
        if (host.isEmpty() || bmp.width < 8) return
        val scaled = if (bmp.width > 96) Bitmap.createScaledBitmap(bmp, 96, 96 * bmp.height / bmp.width, true) else bmp
        mem.put(host, scaled)
        missing.remove(host)
        Io.disk.execute {
            try { FileOutputStream(File(dir, "$host.png")).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Exception) { }
        }
        version.intValue++
    }

    /** Fetches an icon for a site we have not visited yet (home shortcuts). */
    fun prefetch(host: String) {
        if (host.isEmpty() || (mem.get(host)?.width ?: 0) >= 48 || !inFlight.add(host)) return
        Io.net.execute {
            try {
                val onDisk = try { File(dir, "$host.png").takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } } catch (_: Exception) { null }
                if (onDisk != null && onDisk.width >= 48) { mainHandler.post { mem.put(host, onDisk); missing.remove(host); version.intValue++ }; return@execute }
                val sources = listOf("https://$host/favicon.ico", "https://icons.duckduckgo.com/ip3/$host.ico", "https://www.google.com/s2/favicons?domain=$host&sz=128")
                for (source in sources) {
                    try {
                        val c = URL(source).openConnection() as HttpURLConnection
                        c.connectTimeout = 5000; c.readTimeout = 5000
                        c.instanceFollowRedirects = true
                        val bmp = c.inputStream.use { BitmapFactory.decodeStream(it) }
                        c.disconnect()
                        if (bmp != null && bmp.width >= 8) { mainHandler.post { put(host, bmp) }; return@execute }
                    } catch (_: Exception) { }
                }
            } finally { inFlight.remove(host) }
        }
    }

    /** Keeps the folder small: the newest 400 icons and about 8 MB. */
    private fun prune() {
        try {
            val files = dir.listFiles()?.sortedByDescending { it.lastModified() } ?: return
            var bytes = 0L
            files.forEachIndexed { i, f ->
                bytes += f.length()
                if (i >= 400 || bytes > 8L * 1024 * 1024) f.delete()
            }
        } catch (_: Exception) { }
    }
}
