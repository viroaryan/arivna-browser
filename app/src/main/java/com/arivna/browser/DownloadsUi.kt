package com.arivna.browser

import android.text.format.Formatter
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Building blocks

private fun iconOf(name: String, mime: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    val m = mime.lowercase()
    return when {
        m.startsWith("video") || ext in setOf("mp4", "mkv", "webm", "avi", "mov", "3gp", "m4v") -> Ic.Play
        m.startsWith("audio") || ext in setOf("mp3", "m4a", "wav", "flac", "ogg", "aac") -> Ic.Mic
        m.startsWith("image") || ext in setOf("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp", "avif") -> Ic.Image
        ext == "apk" -> Ic.Grid
        ext in setOf("zip", "rar", "7z", "tar", "gz") || m.contains("zip") -> Ic.Folder
        m.startsWith("text") || m.contains("pdf") || m.contains("document") || ext in setOf("pdf", "doc", "docx", "txt", "xls", "xlsx", "ppt", "pptx", "csv") -> Ic.FileText
        else -> Ic.Download
    }
}

private fun progressOf(d: ActiveDownload): Float = if (d.total > 0) (d.done.toFloat() / d.total).coerceIn(0f, 1f) else -1f

private fun isLive(d: ActiveDownload) = d.state == DlState.RUNNING || d.state == DlState.WAITING

/** "12 MB of 40 MB · 30%", then speed and time left while running. */
private fun statusLine(d: ActiveDownload, size: (Long) -> String): String = when (d.state) {
    DlState.WAITING -> d.note.ifEmpty { "Starting…" }
    DlState.RUNNING -> listOfNotNull(
        if (d.total > 0) "${size(d.done)} of ${size(d.total)}" else "${size(d.done)} downloaded",
        d.speed.takeIf { it > 0 }?.let { "${size(it)}/s" },
        formatEta(d.etaSec).takeIf { it.isNotEmpty() },
    ).joinToString(" · ")
    DlState.DONE -> "Complete · ${size(d.total)}"
    DlState.FAILED -> "Download failed"
}

@Composable
private fun StateIcon(d: ActiveDownload) {
    val c = Arivna.colors
    when (d.state) {
        DlState.DONE -> Icon(Ic.Check, null, tint = c.success)
        DlState.FAILED -> Icon(Ic.Warning, null, tint = c.danger)
        else -> Icon(iconOf(d.name, d.mime), null, tint = c.text2)
    }
}

// ---------------------------------------------------------------------------------------------
// Banner shown under the toolbar while something downloads

@Composable
fun DownloadBanner(s: AppState) {
    val list = s.downloadTracker.active
    val d = list.firstOrNull { it.state == DlState.RUNNING || it.state == DlState.WAITING } ?: list.lastOrNull()
    AnimatedVisibility(
        visible = d != null && s.screen != "downloads",
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        if (d != null) BannerRow(s, d, list.count { isLive(it) })
    }
}

@Composable
private fun BannerRow(s: AppState, d: ActiveDownload, running: Int) {
    val c = Arivna.colors
    val ctx = LocalContext.current
    val progress = progressOf(d)
    val line = statusLine(d) { Formatter.formatShortFileSize(ctx, it) } + if (d.state == DlState.RUNNING && running > 1) " · +${running - 1} more" else ""
    Surface(Modifier.fillMaxWidth().clickable { s.screen = "downloads" }, color = c.surface) {
        Column {
            Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                StateIcon(d)
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, color = c.text, style = Type.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(line, style = Type.small, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = {
                    if (d.state == DlState.DONE) { if (!s.downloadTracker.openUri(d.fileUri, d.mime)) Toast.makeText(ctx, "Couldn't open this file", Toast.LENGTH_SHORT).show(); s.downloadTracker.dismiss(d.id) }
                    else s.screen = "downloads"
                }) { Text(if (d.state == DlState.DONE) "Open" else "View", color = c.accentStrong) }
            }
            if (d.state == DlState.RUNNING) {
                if (progress >= 0f) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth(), color = c.accent)
                else LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent)
            }
            HorizontalDivider(color = c.border)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Downloads screen

@Composable
fun DownloadsScreen(s: AppState) {
    val c = Arivna.colors
    val ctx = LocalContext.current
    val tracker = s.downloadTracker
    var confirmClear by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DownloadItem?>(null) }
    fun removeFile(item: DownloadItem) { if (s.prefs.deleteDownloadMode == "device") tracker.deleteItem(item); s.downloads.remove(item) }

    // Derived state: each value is recalculated only when what it depends on changes, and screens below
    // are only told when the *result* changes. The progress ticker (twice a second) therefore only touches
    // the few running rows, never the long list of finished files.
    val liveIds by remember { derivedStateOf { tracker.active.filter { isLive(it) }.mapTo(HashSet()) { it.id } } }
    val running by remember { derivedStateOf { tracker.active.filter { it.state == DlState.RUNNING || (it.state == DlState.WAITING && it.done > 0) } } }
    val queued by remember { derivedStateOf { tracker.active.count { it.state == DlState.WAITING && it.done == 0L } } }
    val storedIds by remember { derivedStateOf { s.downloads.items.mapTo(HashSet()) { it.dmId } } }
    // finished or failed downloads that are not in the saved list (private tabs) still show for a moment
    val extra by remember { derivedStateOf { tracker.active.filter { (it.state == DlState.DONE || it.state == DlState.FAILED) && it.id !in storedIds } } }
    val past by remember { derivedStateOf { s.downloads.items.filter { it.dmId !in liveIds } } }

    val zone = remember { java.time.ZoneId.systemDefault() }
    val today = remember { java.time.LocalDate.now(zone) }
    val timeFmt = remember { java.time.format.DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()) }
    val dayFmt = remember { java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()) }
    fun dayLabel(d: java.time.LocalDate) = when (d) { today -> "Today"; today.minusDays(1) -> "Yesterday"; else -> d.format(dayFmt) }
    // a flat list of day headers and files, rebuilt only when the saved list changes
    val entries by remember {
        derivedStateOf {
            val out = ArrayList<Any>(past.size + 8)
            var lastDay: java.time.LocalDate? = null
            for (item in past) {
                val day = java.time.Instant.ofEpochMilli(item.time).atZone(zone).toLocalDate()
                if (day != lastDay) { out.add(day); lastDay = day }
                out.add(item)
            }
            out
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Downloads", onBack = { s.screen = "browser" }) {
            if (past.isNotEmpty()) IconButton(onClick = { confirmClear = true }) { Icon(Ic.Trash, "Clear download list", tint = c.text) }
            IconButton(onClick = { SettingsNav.start = "downloads"; s.screen = "settings" }) { Icon(Ic.Settings, "Download settings", tint = c.text) }
        }
        if (liveIds.isEmpty() && extra.isEmpty() && past.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Ic.Download, null, Modifier.size(48.dp), c.text3)
                Spacer(Modifier.height(16.dp))
                Text("No downloads yet", color = c.text, style = Type.headline)
                Text("Files you download will appear here.", color = c.text3, style = Type.small)
            }
        } else LazyColumn(Modifier.weight(1f)) {
            if (running.isNotEmpty() || extra.isNotEmpty() || queued > 0) {
                item(key = "live-label") { SectionHeader("Downloading") }
                items(running + extra, key = { "live-${it.id}" }) { d -> LiveRow(s, d) }
                if (queued > 0) item(key = "queued") {
                    ListRow("$queued waiting in line", "They start by themselves, three at a time.", trail = { TextButton(onClick = { s.downloadTracker.cancelQueued() }) { Text("Cancel all", color = c.danger) } })
                }
            }
            items(entries, key = { if (it is java.time.LocalDate) "day-$it" else "file-${(it as DownloadItem).id}" }, contentType = { if (it is java.time.LocalDate) 0 else 1 }) { entry ->
                if (entry is java.time.LocalDate) {
                    SectionHeader(dayLabel(entry))
                } else if (entry is DownloadItem) {
                    FileRow(entry, zone, timeFmt, onOpen = {
                        if (!tracker.openItem(entry)) Toast.makeText(ctx, "Can't open this file. It may have been moved or deleted.", Toast.LENGTH_SHORT).show()
                    }, onCopy = { s.copy(entry.url) }, onShare = { s.share(listOf(entry.url)) }, onDelete = { if (s.prefs.askDeleteDownload) deleting = entry else removeFile(entry) })
                }
            }
        }
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text2,
        title = { Text("Clear the list?") }, text = { Text("The files stay on your phone. Only this list is cleared.") },
        confirmButton = { TextButton(onClick = { s.downloads.clear(); confirmClear = false }) { Text("Clear list", color = c.danger) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
    deleting?.let { item ->
        val device = s.prefs.deleteDownloadMode == "device"
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text2,
            title = { Text(if (device) "Delete file?" else "Remove from history?") },
            text = { Text(if (device) "${item.fileName} is deleted from your device and removed from download history." else "${item.fileName} is removed from your download history, but is still saved on your device.") },
            confirmButton = { TextButton(onClick = { removeFile(item); deleting = null }) { Text(if (device) "Delete" else "Remove", color = c.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

/** One saved file. Takes plain values only, so it is skipped while the progress of other downloads updates. */
@Composable
private fun FileRow(item: DownloadItem, zone: java.time.ZoneId, timeFmt: java.time.format.DateTimeFormatter, onOpen: () -> Unit, onCopy: () -> Unit, onShare: () -> Unit, onDelete: () -> Unit) {
    val c = Arivna.colors
    val ctx = LocalContext.current
    val icon = remember(item.fileName, item.mime) { iconOf(item.fileName, item.mime) }
    val host = remember(item.url) { android.net.Uri.parse(item.url).host.orEmpty().removePrefix("www.") }
    val interrupted = item.fileUri.isBlank() && isOwnDownloadId(item.dmId)
    val time = remember(item.time) { java.time.Instant.ofEpochMilli(item.time).atZone(zone).toLocalTime().format(timeFmt) }
    val size = remember(item.size) { if (item.size > 0) Formatter.formatShortFileSize(ctx, item.size) else "" }
    ListRow(
        item.fileName, listOf(if (interrupted) "Not finished" else "", size, host, time).filter { it.isNotEmpty() }.joinToString(" · "),
        lead = { Icon(icon, null, tint = if (interrupted) c.danger else c.text2) },
        trail = { RowMenu("Open" to onOpen, "Share link" to onShare, "Copy link" to onCopy, "Delete" to onDelete) },
        onClick = onOpen,
    )
}

/** A download in progress (or just finished): progress bar and cancel, or Open/Dismiss. */
@Composable
private fun LiveRow(s: AppState, d: ActiveDownload) {
    val c = Arivna.colors
    val ctx = LocalContext.current
    val progress = progressOf(d)
    Column {
        Row(Modifier.heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            StateIcon(d)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, color = c.text, style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(statusLine(d) { Formatter.formatShortFileSize(ctx, it) }, style = Type.small, color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (isLive(d)) IconButton(onClick = { s.downloadTracker.cancel(d.id) }) { Icon(Ic.Close, "Cancel download", Modifier.size(24.dp), c.text2) }
            else {
                if (d.state == DlState.DONE) TextButton(onClick = { if (!s.downloadTracker.openUri(d.fileUri, d.mime)) Toast.makeText(ctx, "Couldn't open this file", Toast.LENGTH_SHORT).show() }) { Text("Open", color = c.accentStrong) }
                TextButton(onClick = { s.downloadTracker.dismiss(d.id) }) { Text("Dismiss", color = c.text2) }
            }
        }
        if (isLive(d)) {
            Spacer(Modifier.height(8.dp))
            if (progress >= 0f) LinearProgressIndicator({ progress }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = c.accent)
            else LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = c.accent)
            Spacer(Modifier.height(8.dp))
        }
        HorizontalDivider(color = c.border)
    }
}
