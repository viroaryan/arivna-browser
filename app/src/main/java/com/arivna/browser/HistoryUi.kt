package com.arivna.browser

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val PAGE = 100

/** Favicon at 24dp; until one is available, the site's first letter. */
@Composable
private fun HistoryLogo(s: AppState, host: String) {
    val c = Arivna.colors
    s.favicons.version.intValue
    val bmp = s.favicons.get(host)
    val image = remember(bmp) { bmp?.asImageBitmap() }
    if (image != null) Image(image, host, Modifier.size(24.dp), contentScale = ContentScale.Fit)
    else Box(Modifier.size(24.dp).background(c.surface3, CircleShape), contentAlignment = Alignment.Center) {
        Text(host.removePrefix("www.").firstOrNull()?.uppercase() ?: "?", color = c.text2, fontSize = 12.sp)
    }
}

/** The tabs closed this session; tapping one reopens it. Shared by History and the tab tray. */
@Composable
internal fun ClosedTabsList(s: AppState, onBack: () -> Unit) {
    val c = Arivna.colors
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Recently closed tabs", onBack)
        if (s.closedTabs.isEmpty()) Text("No recently closed tabs", Modifier.padding(16.dp), color = c.text3)
        LazyColumn {
            items(s.closedTabs.toList()) { t ->
                ListRow(t.title.ifBlank { t.url }, t.url.removePrefix("https://").removePrefix("http://"), lead = { HistoryLogo(s, Uri.parse(t.url).host.orEmpty()) }) { s.reopenClosedTab(t) }
            }
        }
    }
}

/**
 * Browsing history. Pages are read from the database 100 at a time as you scroll and the search runs in
 * the database, so it stays instant however long the history is.
 */
@Composable
fun HistoryScreen(s: AppState) {
    val c = Arivna.colors
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var closedList by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<HistoryItem>() }
    val rows = remember { mutableStateListOf<HistoryItem>() }
    var loading by remember { mutableStateOf(false) }
    var exhausted by remember { mutableStateOf(false) }
    var generation by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }

    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now(zone) }
    val timeFmt = remember { DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()) }
    val dayFmt = remember { DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()) }

    fun load(reset: Boolean) {
        if (reset) { generation++; rows.clear(); exhausted = false }
        else if (loading || exhausted) return
        val mine = generation
        loading = true
        s.history.page(rows.lastOrNull()?.time ?: Long.MAX_VALUE, PAGE, query) { list ->
            if (mine != generation) return@page
            rows.addAll(list)
            exhausted = list.size < PAGE
            loading = false
        }
    }

    BackHandler(searching || picked.isNotEmpty() || closedList) {
        when { closedList -> closedList = false; picked.isNotEmpty() -> picked.clear(); else -> { searching = false; query = "" } }
    }
    LaunchedEffect(query) {
        if (query.isNotEmpty()) delay(150)
        load(true)
    }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }
    LaunchedEffect(listState) {
        snapshotFlow { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) to listState.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (total > 0 && last >= total - 12) load(false) }
    }

    // flat list of day headers and pages; rebuilt only when the loaded rows change
    val entries by remember {
        derivedStateOf {
            val out = ArrayList<Any>(rows.size + 16)
            var lastDay: LocalDate? = null
            for (r in rows) {
                val day = Instant.ofEpochMilli(r.time).atZone(zone).toLocalDate()
                if (day != lastDay) { out.add(day); lastDay = day }
                out.add(r)
            }
            out
        }
    }
    val total = s.history.count

    if (closedList) { ClosedTabsList(s) { closedList = false }; return }
    Column(Modifier.fillMaxSize()) {
        if (picked.isNotEmpty()) ScreenTitle("${picked.size} selected", onBack = { picked.clear() }) {
            IconButton(onClick = { picked.forEach { rows.remove(it); s.history.remove(it) }; picked.clear() }) { Icon(Ic.Trash, "Delete selected", tint = c.text) }
        } else if (searching) Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { searching = false; query = "" }) { Icon(Ic.Back, "Close search", tint = c.text) }
            BasicTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 18.sp), cursorBrush = SolidColor(c.accent),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (query.isEmpty()) Text("Search history", color = c.text3, fontSize = 18.sp, maxLines = 1); inner() } })
            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Ic.Close, "Clear search", Modifier.size(20.dp), c.text2) }
        } else ScreenTitle("History", onBack = { s.screen = "browser" }) {
            IconButton(onClick = { searching = true }) { Icon(Ic.Search, "Search history", tint = c.text) }
            if (total > 0) IconButton(onClick = { confirmClear = true }) { Icon(Ic.Trash, "Delete history", tint = c.text) }
        }
        if (rows.isEmpty() && !loading) {
            Text(if (query.isEmpty()) "No history yet" else "Nothing matches “$query”", Modifier.padding(16.dp), color = c.text3)
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (!searching) item(key = "closed-tabs") {
                ListRow("Recently closed tabs", "${s.closedTabs.size} tabs", lead = { Icon(Ic.Layers, null, tint = c.text2) }) { closedList = true }
            }
            items(entries, key = { if (it is LocalDate) "d$it" else (it as HistoryItem).id }, contentType = { if (it is LocalDate) 0 else 1 }) { entry ->
                if (entry is LocalDate) SectionHeader(entry.format(dayFmt))
                else if (entry is HistoryItem) {
                    val rawHost = Uri.parse(entry.url).host.orEmpty()
                    val host = rawHost.removePrefix("www.").ifBlank { entry.url }
                    val visits = if (entry.visits > 1) " · ${entry.visits} visits" else ""
                    val isPicked = entry in picked
                    ListRow(
                        entry.title.ifBlank { host }, host + " · " + Instant.ofEpochMilli(entry.time).atZone(zone).toLocalTime().format(timeFmt) + visits,
                        lead = { HistoryLogo(s, rawHost) }, selected = isPicked,
                        trail = { IconButton(onClick = { rows.remove(entry); s.history.remove(entry) }) { Icon(Ic.Close, "Delete", Modifier.size(24.dp), c.text2) } },
                        onLongClick = { if (!isPicked) picked.add(entry) },
                    ) { if (picked.isNotEmpty()) { if (isPicked) picked.remove(entry) else picked.add(entry) } else s.newTab(url = entry.url) }
                }
            }
        }
    }
    if (confirmClear) {
        var range by remember { mutableStateOf(0) }
        val ranges = listOf("Last hour", "Today and yesterday", "Everything")
        AlertDialog(
            onDismissRequest = { confirmClear = false }, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text2,
            title = { Text("Delete history") },
            text = {
                Column {
                    ranges.forEachIndexed { i, label ->
                        Row(Modifier.fillMaxWidth().clickable { range = i }, verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(range == i, null, Modifier.padding(vertical = 12.dp)); Spacer(Modifier.width(16.dp)); Text(label, color = c.text)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    if (range == 2) { s.history.clear(); rows.clear() }
                    else {
                        val cutoff = if (range == 0) System.currentTimeMillis() - 3_600_000L else today.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                        // shortcut: looks at the newest 5000 pages only, enough for a day or two of browsing
                        s.history.page(Long.MAX_VALUE, 5000, "") { list -> list.filter { it.time >= cutoff }.forEach(s.history::remove) }
                        rows.removeAll { it.time >= cutoff }
                    }
                }) { Text("Delete", color = c.danger) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
