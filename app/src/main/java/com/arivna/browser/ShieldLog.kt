package com.arivna.browser

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.atomic.AtomicBoolean

enum class RequestAction { BLOCKED, REDIRECTED, ALLOWED }

data class RequestEntry(
    val time: Long,
    val url: String,
    val type: String,
    val action: RequestAction,
    /** The filter that decided the request (empty when nothing matched). */
    val filter: String,
)

/** Ring buffer of the most recent requests for one tab. Written from WebView threads, read on the UI thread. */
class RequestLog(private val capacity: Int = 500) {
    private val entries = ArrayDeque<RequestEntry>(capacity)
    private val main = Handler(Looper.getMainLooper())
    private val notifyPending = AtomicBoolean(false)

    /** Bumped on the UI thread whenever entries change; read it in composition to observe the log. */
    val revision = mutableIntStateOf(0)

    fun add(entry: RequestEntry) {
        synchronized(this) {
            if (entries.size == capacity) entries.removeFirst()
            entries.addLast(entry)
        }
        // Coalesce bursts: at most one pending UI refresh at a time.
        if (notifyPending.compareAndSet(false, true)) {
            main.post {
                notifyPending.set(false)
                revision.intValue++
            }
        }
    }

    /** Newest first. */
    fun snapshot(): List<RequestEntry> = synchronized(this) { entries.reversed() }

    /** Must be called on the UI thread. */
    fun clear() {
        synchronized(this) { entries.clear() }
        revision.intValue++
    }
}
