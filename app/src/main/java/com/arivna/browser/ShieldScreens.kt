package com.arivna.browser

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Locale

/** Slim strip shown while the element picker is waiting for a tap. */
@Composable
fun PickerBar(s: AppState) {
    val c = Arivna.colors
    Surface(Modifier.fillMaxWidth(), color = c.accentSoft) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Tap an element to block it", Modifier.weight(1f), color = c.text, style = Type.small)
            TextButton(onClick = { s.cancelPicker() }) { Text("Cancel", color = c.accentStrong) }
        }
    }
}

/** Confirms the element the user tapped; the selector can be edited before it is saved. */
@Composable
fun BlockElementDialog(s: AppState, element: PickedElement) {
    val c = Arivna.colors
    var selector by remember(element) { mutableStateOf(element.selector) }
    var error by remember(element) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { s.cancelPicker() },
        containerColor = c.surface,
        titleContentColor = c.text,
        textContentColor = c.text2,
        title = { Text("Block this element?") },
        text = {
            Column {
                Text("It stays hidden on ${element.host}. Edit the selector to block more or less.", style = Type.small)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    selector,
                    { selector = it; error = "" },
                    label = { Text("CSS selector") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                )
                if (error.isNotBlank()) Text(error, color = c.danger, style = Type.small, modifier = Modifier.padding(top = 6.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val sel = selector.trim()
                if (UserFilters.ruleFor(element.host, sel) == null) error = "Enter a valid CSS selector"
                else s.blockPicked(element, sel)
            }) { Text("Block", color = c.accentStrong) }
        },
        dismissButton = { TextButton(onClick = { s.cancelPicker() }) { Text("Cancel") } },
    )
}

/** Lists the element rules the user has saved, with delete. */
@Composable
fun UserFiltersScreen(s: AppState) {
    val c = Arivna.colors
    val ctx = s.activity
    var rules by remember { mutableStateOf(UserFilters.rules(ctx)) }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("My filters")
        HorizontalDivider(color = c.border)
        if (rules.isEmpty()) Text("No rules yet. Open the menu on a page and choose Block element.", Modifier.padding(16.dp), color = c.text3)
        LazyColumn {
            items(rules) { line ->
                ListRow(line.substringBefore("##"), line.substringAfter("##"), trail = {
                    IconButton(onClick = {
                        UserFilters.remove(ctx, line)
                        rules = UserFilters.rules(ctx)
                        Shield.rebuild()
                    }) { Icon(Ic.Trash, "Delete rule for ${line.substringBefore("##")}", Modifier.size(24.dp), tint = c.text2) }
                })
            }
        }
    }
}

private enum class LogFilter(val label: String) { ALL("All"), BLOCKED("Blocked"), REDIRECTED("Redirected"), ALLOWED("Allowed") }

/** The current tab's recent sub-resource requests, filterable by outcome and text. */
@Composable
fun RequestLogScreen(s: AppState, tab: Tab?) {
    val c = Arivna.colors
    val log = tab?.requestLog
    val revision = log?.revision?.intValue ?: 0
    val all = remember(revision, log) { log?.snapshot().orEmpty() }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    var query by remember { mutableStateOf("") }
    val shown = remember(all, filter, query) {
        val q = query.trim().lowercase()
        all.filter { e ->
            (filter == LogFilter.ALL || e.action.name == filter.name) &&
                (q.isEmpty() || e.url.lowercase().contains(q) || e.filter.lowercase().contains(q) || e.type.contains(q))
        }
    }
    val blocked = all.count { it.action == RequestAction.BLOCKED }
    val redirected = all.count { it.action == RequestAction.REDIRECTED }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("Request log", onBack = { s.screen = "browser" }) {
            TextButton(onClick = { log?.clear() }, enabled = log != null && all.isNotEmpty()) { Text("Clear", color = c.accentStrong) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogFilter.entries.forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) }) }
        }
        OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search URL, type or filter") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
        Text(
            "${tab?.host?.ifBlank { null } ?: "No page open"} · ${shown.size} of ${all.size} · $blocked blocked · $redirected redirected",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = Type.small, color = c.text3,
        )
        HorizontalDivider(color = c.border)
        if (shown.isEmpty()) Text(if (all.isEmpty()) "Nothing logged yet. Open a page to see its requests." else "No requests match.", Modifier.padding(16.dp), color = c.text3)
        LazyColumn(Modifier.weight(1f)) { items(shown) { e -> RequestRow(e) } }
    }
}

private val logTime = ThreadLocal.withInitial { SimpleDateFormat("HH:mm:ss", Locale.US) }

@Composable
private fun RequestRow(e: RequestEntry) {
    val c = Arivna.colors
    val (label, icon, tint) = when (e.action) {
        RequestAction.BLOCKED -> Triple("Blocked", Ic.ShieldOff, c.danger)
        RequestAction.REDIRECTED -> Triple("Redirected", Ic.Retry, c.accentStrong)
        RequestAction.ALLOWED -> Triple("Allowed", Ic.Check, c.success)
    }
    ListRow(e.url, "$label · ${e.type} · ${logTime.get()!!.format(e.time)}" + if (e.filter.isNotEmpty()) " · ${e.filter}" else "", lead = { Icon(icon, label, tint = tint) })
}

/** Last-updated line, Update now button, status and the daily auto-update switch. Used by the shield screen and Settings > About. */
@Composable
internal fun FilterLists(s: AppState) {
    val c = Arivna.colors
    val last = s.prefs.lastFilterUpdate
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                "Last updated " + if (last == 0L) "never" else android.text.format.DateUtils.formatDateTime(ctx, last, android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME or android.text.format.DateUtils.FORMAT_ABBREV_MONTH),
                color = c.text, style = Type.body,
            )
            Text(s.filterUpdateStatus.ifBlank { "EasyList, EasyPrivacy, AdGuard, uBlock Origin, Peter Lowe, URLhaus" }, style = Type.small, color = c.text3)
        }
        Spacer(Modifier.width(12.dp))
        OutlinedButton(onClick = s::refreshFilters, enabled = !s.filterUpdating, shape = RoundedCornerShape(8.dp)) { Text("Update now") }
    }
    if (s.filterUpdating) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = c.accent)
    SettingToggle("Auto-update daily", s.prefs.autoUpdateFilters) { s.prefs.autoUpdateFilters = it }
}

/** Privacy shield: block counts, switches, this site's setting, and links to the request log and my filters. */
@Composable
internal fun ShieldScreen(s: AppState, tab: Tab?) {
    val c = Arivna.colors
    val info = Shield.info.value
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ScreenTitle("Privacy shield", onBack = { s.screen = "browser" })
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text("%,d requests blocked".format(Shield.stats.value.total), color = c.text, style = Type.headline)
            Text("%,d domains · %,d path rules · %,d visual filters".format(info.domains, info.paths, info.cosmetic), style = Type.small, color = c.text3)
        }
        SettingToggle("Block ads and trackers", s.prefs.shield) { s.prefs.shield = it; Shield.sync(s.prefs); s.refreshShieldTabs() }
        SettingToggle("Strict cosmetic filters", s.prefs.shieldStrict) { s.prefs.shieldStrict = it; Shield.sync(s.prefs); s.refreshShieldTabs() }
        if (tab?.showHome == false) {
            SectionHeader(tab.host)
            SettingToggle("Shield on this site", !Shield.isAllowlisted(tab.host), "${tab.blocked} requests blocked on this page") { enabled ->
                s.prefs.setAllowed(tab.host, !enabled); Shield.sync(s.prefs); tab.reload()
            }
        }
        SectionHeader("Tools")
        ListRow("Request log", lead = { Icon(Ic.History, null, tint = c.text2) }) { s.screen = "requestlog" }
        ListRow("My filters", lead = { Icon(Ic.ShieldOff, null, tint = c.text2) }) { s.screen = "userfilters" }
        SectionHeader("Filter lists")
        FilterLists(s)
    }
}
