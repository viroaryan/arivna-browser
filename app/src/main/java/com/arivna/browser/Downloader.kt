package com.arivna.browser

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.runtime.mutableStateListOf
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

// ---------------------------------------------------------------------------------------------
// Arivna's own downloader. It runs inside a foreground service, resumes after connection drops
// and keeps going when the app is in the background (the system DownloadManager does not).

enum class DlState { WAITING, RUNNING, DONE, FAILED }

data class ActiveDownload(
    val id: Long,
    val name: String,
    val mime: String,
    val total: Long,
    val done: Long,
    val speed: Long,
    val etaSec: Long,
    val state: DlState,
    val fileUri: String = "",
    val note: String = "",
)

fun formatEta(sec: Long): String = when {
    sec < 0 -> ""
    sec < 60 -> "$sec s left"
    sec < 3600 -> "${sec / 60} min ${sec % 60} s left"
    else -> "${sec / 3600} h ${(sec % 3600) / 60} min left"
}

/** Ids handed out by [DownloadTracker] are time based; older ids came from the system DownloadManager. */
fun isOwnDownloadId(id: Long) = id > 10_000_000_000L

private class PermanentFailure(message: String) : IOException(message)

private class Job(
    val id: Long,
    val url: String,
    var name: String,
    var mime: String,
    val userAgent: String,
    val cookie: String?,
    val referer: String?,
) {
    @Volatile var cancelled = false
    @Volatile var conn: HttpURLConnection? = null
    // Progress is written here by the worker thread (cheap) and copied to the UI by one shared ticker.
    @Volatile var done = 0L
    @Volatile var total = -1L
    @Volatile var speed = 0L
    @Volatile var state = DlState.WAITING
    @Volatile var note = "Waiting in queue"
}

/** Where the downloaded bytes go: a pending entry in the public Downloads folder (or app storage on old Android). */
/** `photo.jpg` -> `photo-k3x9a.jpg`: a name that cannot collide with the numbered copies. */
private fun taggedName(name: String): String {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return name.substring(0, dot) + "-" + java.lang.Long.toString(System.nanoTime() and 0xFFFFFF, 36) + name.substring(dot)
}

private class Sink(private val ctx: Context, var displayName: String, private val mime: String) {
    private var uri: Uri? = null
    private var file: File? = null

    init {
        if (Build.VERSION.SDK_INT >= 29) {
            // MediaStore numbers duplicates ("name (1)" ... "name (32)") and then gives up with an exception,
            // so once the plain name is used up fall back to a name with a unique tag.
            var inserted: Uri? = null
            for (attempt in 0..2) {
                val name = if (attempt == 0) displayName else taggedName(displayName)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    if (mime.isNotBlank()) put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                inserted = try { ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) } catch (e: IllegalStateException) { null }
                if (inserted != null) break
            }
            val u = inserted ?: throw IOException("Can't create the file")
            uri = u
            // MediaStore may rename the file when the name is already taken.
            ctx.contentResolver.query(u, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) displayName = c.getString(0) ?: displayName
            }
        } else {
            val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
            dir.mkdirs()
            var f = File(dir, displayName)
            var n = 1
            while (f.exists()) { f = File(dir, displayName.substringBeforeLast('.') + " ($n)." + displayName.substringAfterLast('.', "")); n++ }
            file = f
            displayName = f.name
        }
    }

    fun open(append: Boolean): OutputStream =
        uri?.let { ctx.contentResolver.openOutputStream(it, if (append) "wa" else "wt") }
            ?: FileOutputStream(file!!, append)

    /** Makes the file visible to other apps and returns where it is. */
    fun finish(): String {
        uri?.let {
            try {
                ctx.contentResolver.update(it, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: IllegalStateException) {
                // MediaStore gives the file its real name now, and gives up after 32 files with the same name.
                val name = taggedName(displayName)
                ctx.contentResolver.update(it, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.IS_PENDING, 0)
                }, null, null)
                displayName = name
            }
            return it.toString()
        }
        return Uri.fromFile(file!!).toString()
    }

    fun discard() {
        try { uri?.let { ctx.contentResolver.delete(it, null, null) } } catch (_: Exception) { }
        try { file?.delete() } catch (_: Exception) { }
    }
}

/** Starts, tracks and cancels downloads, and exposes their live progress to the UI and notifications. */
class DownloadTracker(private val ctx: Context) {
    val active = mutableStateListOf<ActiveDownload>()
    /** Called on the main thread when a download finishes: (id, file uri, size in bytes, final file name). */
    var onFinished: ((Long, String, Long, String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val nm = NotificationManagerCompat.from(ctx)
    private val jobs = ConcurrentHashMap<Long, Job>()
    // At most 3 files download at once, like Chrome: more connections just slow each other down. The rest wait in line.
    private val pool = Executors.newFixedThreadPool(MAX_PARALLEL)
    private var ticking = false
    private val lastNotified = HashMap<Long, Long>()
    private val ids = AtomicLong(System.currentTimeMillis())
    private var serviceOn = false
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        instance = this
        if (Build.VERSION.SDK_INT >= 26) {
            val mgr = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(NotificationChannel(CHANNEL, "Download progress", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Live progress of files you download"
                setShowBadge(false)
            })
            mgr.createNotificationChannel(NotificationChannel(CHANNEL_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tells you when a download is complete or failed"
            })
        }
    }

    // ----- starting and stopping -----

    /** Begins downloading [url]. Returns the id used by the UI and notifications. */
    fun start(url: String, name: String, mime: String, userAgent: String, cookie: String?, referer: String?): Long {
        val id = ids.incrementAndGet()
        val job = Job(id, url, name, mime, userAgent, cookie, referer)
        jobs[id] = job
        active.add(ActiveDownload(id, name, mime, -1, 0, 0, -1, DlState.WAITING, note = job.note))
        syncService()
        if (!ticking) { ticking = true; main.post(ticker) }
        pool.execute { run(job) }
        return id
    }

    fun cancel(id: Long) {
        jobs.remove(id)?.let { it.cancelled = true; try { it.conn?.disconnect() } catch (_: Exception) { } }
        nm.cancel(id.toInt())
        dismiss(id)
        // a legacy DownloadManager download (from an older version of the app)
        if (!isOwnDownloadId(id)) try { (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(id) } catch (_: Exception) { }
    }

    /** Cancels every download that has not started yet. */
    fun cancelQueued() {
        active.filter { it.state == DlState.WAITING && it.done == 0L }.forEach { cancel(it.id) }
    }

    fun dismiss(id: Long) {
        lastNotified.remove(id)
        active.removeAll { it.id == id }
        syncService()
    }

    // ----- the worker -----

    private fun run(job: Job) {
        var sink: Sink? = null
        var done = 0L
        var total = -1L
        try {
            val scheme = Uri.parse(job.url).scheme?.lowercase()
            if (scheme != "https" && scheme != "http") throw PermanentFailure("Unsupported link")
            sink = Sink(ctx, job.name, job.mime)
            job.note = "Starting…"
            var attempt = 0
            var speed = 0.0
            var lastPush = 0L
            var lastBytes = 0L
            var lastTime = SystemClock.elapsedRealtime()
            while (!job.cancelled) {
                var conn: HttpURLConnection? = null
                try {
                    conn = URL(job.url).openConnection() as HttpURLConnection
                    job.conn = conn
                    conn.connectTimeout = 20_000
                    conn.readTimeout = 30_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", job.userAgent)
                    conn.setRequestProperty("Accept", "*/*")
                    conn.setRequestProperty("Accept-Encoding", "identity")
                    job.cookie?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
                    job.referer?.let { conn.setRequestProperty("Referer", it) }
                    if (done > 0) conn.setRequestProperty("Range", "bytes=$done-")
                    val code = conn.responseCode
                    if (code == 416 && done > 0) break // we already have everything
                    if (code !in 200..299) {
                        if (code == 408 || code == 429 || code >= 500) throw IOException("HTTP $code")
                        throw PermanentFailure("HTTP $code")
                    }
                    var append = done > 0
                    if (done > 0 && code != 206) { done = 0; append = false } // server ignored Range: start over
                    total = if (code == 206) conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L else conn.contentLengthLong
                    if (job.mime.isBlank()) job.mime = conn.contentType?.substringBefore(';')?.trim().orEmpty()
                    sync(job, done, total, speed, DlState.RUNNING, "")
                    conn.inputStream.use { input ->
                        sink.open(append).use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (!job.cancelled) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                done += n
                                attempt = 0
                                val now = SystemClock.elapsedRealtime()
                                if (now - lastPush >= 400) {
                                    val inst = (done - lastBytes) * 1000.0 / (now - lastTime).coerceAtLeast(1)
                                    speed = if (speed == 0.0) inst else speed * 0.6 + inst * 0.4
                                    lastBytes = done; lastTime = now; lastPush = now
                                    sync(job, done, total, speed, DlState.RUNNING, "")
                                }
                            }
                        }
                    }
                    if (job.cancelled) break
                    if (total in 1 until Long.MAX_VALUE && done < total) throw IOException("Connection closed early")
                    break
                } catch (e: PermanentFailure) {
                    throw e
                } catch (e: IOException) {
                    if (job.cancelled) break
                    if (e.message?.contains("Cleartext", ignoreCase = true) == true) throw PermanentFailure("Insecure link blocked")
                    attempt++
                    android.util.Log.w("ArivnaDL", "job ${job.id} attempt $attempt at $done/$total: ${e.javaClass.simpleName}: ${e.message}")
                    if (attempt > 10) throw e
                    speed = 0.0
                    sync(job, done, total, 0.0, DlState.WAITING, "Waiting for connection…")
                    var waited = 0
                    val wait = minOf(30, attempt * 3) * 1000
                    while (waited < wait && !job.cancelled) { Thread.sleep(250); waited += 250 }
                    lastTime = SystemClock.elapsedRealtime(); lastBytes = done
                } finally {
                    try { conn?.disconnect() } catch (_: Exception) { }
                }
            }
            if (job.cancelled) { sink.discard(); return }
            val uri = sink.finish()
            val name = sink.displayName
            val size = if (done > 0) done else total
            main.post { complete(job.id, uri, size, name) }
        } catch (e: Exception) {
            android.util.Log.e("ArivnaDL", "job ${job.id} failed: ${e.javaClass.simpleName}: ${e.message}")
            sink?.discard()
            if (!job.cancelled) main.post { fail(job.id) }
        } finally {
            jobs.remove(job.id)
        }
    }

    // ----- state changes (main thread) -----

    private fun sync(job: Job, done: Long, total: Long, speed: Double, state: DlState, note: String) {
        job.done = done; job.total = total; job.speed = speed.toLong(); job.state = state; job.note = note
    }

    /**
     * One timer for all downloads: twice a second it copies each job's progress into the list the screens
     * show and refreshes the notifications. With 50 downloads that is 100 cheap updates a second instead
     * of a main-thread message for every 64 KB chunk of every file.
     */
    private val ticker = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            var any = false
            val primary = primaryId()
            for (job in jobs.values) {
                val i = active.indexOfFirst { it.id == job.id }
                if (i < 0) continue
                val cur = active[i]
                if (cur.state == DlState.DONE || cur.state == DlState.FAILED) continue
                any = true
                val sp = job.speed
                val eta = if (sp > 0 && job.total > 0) (job.total - job.done) / sp else -1L
                val next = cur.copy(total = job.total, done = job.done, speed = sp, etaSec = eta, state = job.state, note = job.note)
                if (next != cur) active[i] = next
                // The foreground notification refreshes every tick; the others every 2 s, and files still in line get none.
                val queued = next.state == DlState.WAITING && next.done == 0L
                if (!queued && (next.id == primary || now - (lastNotified[next.id] ?: 0L) >= 2000)) { lastNotified[next.id] = now; notify(next) }
            }
            if (serviceOn && summaryMode() && nm.areNotificationsEnabled()) {
                try { nm.notify(FG_ID, foregroundNotification()) } catch (_: SecurityException) { }
            }
            if (any) main.postDelayed(this, 500) else ticking = false
        }
    }

    /** With many files, the foreground notification becomes one summary instead of showing a single file. */
    private fun summaryMode() = active.count { it.state == DlState.RUNNING || it.state == DlState.WAITING } > 3

    /** The download that owns the foreground-service notification: a running one if there is one. */
    private fun primaryId(): Long? =
        (active.firstOrNull { it.state == DlState.RUNNING } ?: active.firstOrNull { it.state == DlState.WAITING })?.id

    private fun complete(id: Long, uri: String, size: Long, name: String) {
        val i = active.indexOfFirst { it.id == id }
        if (i < 0) return
        val d = active[i].copy(name = name, total = size, done = size, speed = 0, etaSec = 0, state = DlState.DONE, fileUri = uri, note = "")
        active[i] = d
        onFinished?.invoke(id, uri, size, name)
        notify(d)
        syncService()
        main.postDelayed({ dismiss(id) }, 15_000)
    }

    private fun fail(id: Long) {
        val i = active.indexOfFirst { it.id == id }
        if (i < 0) return
        val d = active[i].copy(speed = 0, etaSec = -1, state = DlState.FAILED, note = "")
        active[i] = d
        notify(d)
        syncService()
        main.postDelayed({ dismiss(id) }, 12_000)
    }

    // ----- opening and deleting files -----

    fun exists(uri: String): Boolean = try {
        if (uri.startsWith("file:")) File(Uri.parse(uri).path.orEmpty()).exists()
        else ctx.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.count > 0 } == true
    } catch (_: Exception) { false }

    fun openUri(uri: String, mime: String): Boolean {
        if (uri.isBlank() || !exists(uri)) return false
        return try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (_: Exception) { false }
    }

    /** Opens a saved download, including ones made by older versions through the system DownloadManager. */
    fun openItem(item: DownloadItem): Boolean {
        if (item.fileUri.isNotBlank()) return openUri(item.fileUri, item.mime)
        if (!isOwnDownloadId(item.dmId) && item.dmId >= 0) try {
            val uri = (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).getUriForDownloadedFile(item.dmId) ?: return false
            ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        } catch (_: Exception) { }
        return false
    }

    fun deleteItem(item: DownloadItem) {
        try {
            when {
                item.fileUri.startsWith("file:") -> File(Uri.parse(item.fileUri).path.orEmpty()).delete()
                item.fileUri.isNotBlank() -> ctx.contentResolver.delete(Uri.parse(item.fileUri), null, null)
                !isOwnDownloadId(item.dmId) && item.dmId >= 0 -> (ctx.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).remove(item.dmId)
            }
        } catch (_: Exception) { }
    }

    // ----- notifications -----

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("open", "downloads").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelIntent(id: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, id.toInt(), Intent(ctx, DownloadActionReceiver::class.java).setAction(ACTION_CANCEL).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun openFileIntent(d: ActiveDownload): PendingIntent? {
        if (d.fileUri.isBlank()) return null
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(d.fileUri), d.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return PendingIntent.getActivity(ctx, d.id.toInt(), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Shows or updates the system notification for one download (progress, speed, time left, Cancel). */
    private fun notify(d: ActiveDownload) {
        if (!nm.areNotificationsEnabled()) return
        // The first running download owns the foreground-service notification, so it keeps updating in the background.
        val nid = if (serviceOn && !summaryMode() && d.id == primaryId()) FG_ID else d.id.toInt()
        try { nm.notify(nid, build(d)) } catch (_: SecurityException) { }
    }

    private fun build(d: ActiveDownload): android.app.Notification {
        val sizeOf = { b: Long -> Formatter.formatShortFileSize(ctx, b) }
        val finished = d.state == DlState.DONE || d.state == DlState.FAILED
        val b = NotificationCompat.Builder(ctx, if (finished) CHANNEL_DONE else CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(d.name)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFFD97757.toInt())
        when (d.state) {
            DlState.WAITING, DlState.RUNNING -> {
                val known = d.total > 0
                val pct = if (known) ((d.done * 100) / d.total).toInt().coerceIn(0, 100) else 0
                val text = if (d.state == DlState.WAITING) d.note.ifEmpty { "Starting…" } else buildString {
                    append(if (known) "${sizeOf(d.done)} of ${sizeOf(d.total)}" else "${sizeOf(d.done)} downloaded")
                    if (d.speed > 0) append(" · ${sizeOf(d.speed)}/s")
                    val eta = formatEta(d.etaSec); if (eta.isNotEmpty()) append(" · $eta")
                }
                b.setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setProgress(100, pct, !known || (d.state == DlState.WAITING && d.done == 0L))
                    .setOngoing(true).setContentIntent(openApp())
                    .setSubText(if (known) "$pct%" else null)
                    .addAction(0, "Cancel", cancelIntent(d.id))
            }
            DlState.DONE -> b.setContentText("Download complete · ${sizeOf(d.total)}").setProgress(0, 0, false)
                .setOngoing(false).setAutoCancel(true).setContentIntent(openApp()).also { nb -> openFileIntent(d)?.let { nb.addAction(0, "Open", it) } }
            DlState.FAILED -> b.setContentText("Download failed").setProgress(0, 0, false)
                .setOngoing(false).setAutoCancel(true).setContentIntent(openApp())
        }
        return b.build()
    }

    /** Notification for the foreground service: the first running download, or a plain placeholder. */
    fun foregroundNotification(): android.app.Notification {
        val live = active.filter { it.state == DlState.RUNNING || it.state == DlState.WAITING }
        // Many files at once: one summary notification instead of dozens of separate ones.
        if (live.size > 3) { // keep in step with summaryMode()
            val known = live.filter { it.total > 0 }
            val pct = if (known.isEmpty()) 0 else (known.sumOf { it.done } * 100 / known.sumOf { it.total }).toInt().coerceIn(0, 100)
            return NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_download).setColor(0xFFD97757.toInt())
                .setContentTitle("Downloading ${live.size} files").setContentText("${live.count { it.state == DlState.RUNNING }} running, ${live.count { it.state == DlState.WAITING }} waiting")
                .setSubText("$pct%").setProgress(100, pct, false).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(openApp()).build()
        }
        val d = live.firstOrNull { it.state == DlState.RUNNING } ?: live.firstOrNull()
        if (d != null) return build(d)
        return NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_download).setContentTitle("Downloading").setOngoing(true).setContentIntent(openApp()).build()
    }

    /** Keeps the app alive (and the CPU awake) while something downloads, so progress keeps moving in the background. */
    fun syncService() {
        val running = active.any { it.state == DlState.RUNNING || it.state == DlState.WAITING }
        if (running && !serviceOn) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
                serviceOn = true
            } catch (_: Throwable) {
                serviceOn = false
            }
            try {
                if (wakeLock == null) {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "arivna:download").apply { acquire(6 * 60 * 60 * 1000L) }
                }
            } catch (_: Throwable) { }
        } else if (!running && serviceOn) {
            serviceOn = false
            try { ctx.stopService(Intent(ctx, DownloadService::class.java)) } catch (_: Throwable) { }
            try { wakeLock?.takeIf { it.isHeld }?.release() } catch (_: Throwable) { }
            wakeLock = null
        }
    }

    companion object {
        const val CHANNEL = "downloads"
        const val MAX_PARALLEL = 3
        const val CHANNEL_DONE = "downloads_done"
        const val FG_ID = 7001
        const val ACTION_CANCEL = "com.arivna.browser.CANCEL_DOWNLOAD"
        const val EXTRA_ID = "id"
        @Volatile var instance: DownloadTracker? = null
    }
}

/** Handles the Cancel button on a download notification. */
class DownloadActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadTracker.ACTION_CANCEL) return
        val id = intent.getLongExtra(DownloadTracker.EXTRA_ID, -1L)
        if (id < 0) return
        val tracker = DownloadTracker.instance
        if (tracker != null) tracker.cancel(id)
        else NotificationManagerCompat.from(context).cancel(id.toInt())
    }
}

/** Foreground service that runs while downloads are active; the notification itself is owned by [DownloadTracker]. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = DownloadTracker.instance?.foregroundNotification()
            ?: NotificationCompat.Builder(this, DownloadTracker.CHANNEL).setSmallIcon(R.drawable.ic_stat_download).setContentTitle("Downloading").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(DownloadTracker.FG_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(DownloadTracker.FG_ID, n)
        return START_NOT_STICKY
    }

    // Android 15+ caps a dataSync foreground service at about 6 hours; stopping cleanly avoids a crash.
    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }
}
