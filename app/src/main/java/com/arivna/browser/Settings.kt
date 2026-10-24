package com.arivna.browser

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Lets other screens open Settings straight on a sub page (for example "tabs" or "downloads"). */
internal object SettingsNav { var start = "" }

private class Item(val section: String, val title: String, val icon: ImageVector, val sub: String = "", val toggle: Boolean? = null, val onClick: () -> Unit)

/** Settings: a plain list that opens sub pages; the sub page stack is kept here so MainActivity only routes "settings". */
@Composable
internal fun SettingsScreen(s: AppState) {
    val c = Arivna.colors
    val p = s.prefs
    val stack = remember { mutableStateListOf<String>().apply { if (SettingsNav.start.isNotEmpty()) add(SettingsNav.start); SettingsNav.start = "" } }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var deleteDialog by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    BackHandler(stack.isNotEmpty() || searching) { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else { searching = false; query = "" } }
    LaunchedEffect(searching) { if (searching) focus.requestFocus() }

    val page = stack.lastOrNull()
    if (page != null) { SettingsPage(s, page, go = { stack.add(it) }, back = { stack.removeAt(stack.lastIndex) }); return }

    fun launch(intent: Intent) { runCatching { s.activity.startActivity(intent) } }
    val pkg = s.activity.packageName
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", pkg, null))
    val version = remember { runCatching { s.activity.packageManager.getPackageInfo(pkg, 0).versionName }.getOrNull().orEmpty() }
    val items = listOf(
        Item("General", "Search", Ic.Search, p.engine.label) { stack.add("search") },
        Item("General", "Tabs", Ic.Layers, if (p.tabLayout == "list") "List" else "Grid") { stack.add("tabs") },
        Item("General", "Homepage", Ic.Home) { stack.add("homepage") },
        Item("General", "Customize", Ic.Palette) { stack.add("customize") },
        Item("General", "Accessibility", Ic.User) { stack.add("accessibility") },
        Item("General", "Language", Ic.Globe) {
            launch(if (Build.VERSION.SDK_INT >= 33) Intent(Settings.ACTION_APP_LOCALE_SETTINGS, Uri.fromParts("package", pkg, null)) else details)
        },
        Item("Privacy and security", "Enhanced Tracking Protection", Ic.ShieldCheck, if (!p.shield) "Off" else if (p.shieldStrict) "Strict" else "Standard") { stack.add("etp") },
        Item("Privacy and security", "Site settings", Ic.Settings) { stack.add("site") },
        Item("Privacy and security", "Delete browsing data", Ic.Trash) { deleteDialog = true },
        Item("Privacy and security", "Delete browsing data on quit", Ic.Trash, if (p.deleteOnQuit) "On" else "Off") { stack.add("quit") },
        Item("Privacy and security", "Notifications", Ic.Info) { launch(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, pkg)) },
        Item("Advanced", "Open links in apps", Ic.Share, LINK_MODES.firstOrNull { it.first == p.openLinksInApps }?.second.orEmpty()) { stack.add("links") },
        Item("Advanced", "Download Settings", Ic.Download) { stack.add("downloads") },
        Item("Advanced", "Remote debugging via USB", Ic.Code, toggle = p.remoteDebug) { p.remoteDebug = !p.remoteDebug; WebView.setWebContentsDebuggingEnabled(p.remoteDebug) },
        Item("About", "About Arivna", Ic.Info, "Version $version") { stack.add("about") },
    )
    val shown = if (query.isBlank()) items else items.filter { it.title.contains(query.trim(), true) || it.sub.contains(query.trim(), true) }

    Column(Modifier.fillMaxSize()) {
        if (searching) Row(Modifier.fillMaxWidth().padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { searching = false; query = "" }) { Icon(Ic.Back, "Close search", tint = c.text) }
            BasicTextField(query, { query = it }, Modifier.weight(1f).focusRequester(focus), singleLine = true,
                textStyle = TextStyle(color = c.text, fontSize = 18.sp), cursorBrush = SolidColor(c.accent),
                decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (query.isEmpty()) Text("Search settings", color = c.text3, fontSize = 18.sp); inner() } })
            if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Ic.Close, "Clear search", tint = c.text2) }
        } else ScreenTitle("Settings", onBack = { s.screen = "browser" }) {
            IconButton(onClick = { searching = true }) { Icon(Ic.Search, "Search settings", tint = c.text) }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            shown.groupBy { if (query.isBlank()) it.section else "Results" }.forEach { (section, group) ->
                SectionHeader(section)
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    shape = RoundedCornerShape(18.dp),
                    color = c.surface,
                ) {
                    Column {
                        group.forEachIndexed { index, item ->
                            ListRow(item.title, item.sub, lead = { Icon(item.icon, null, tint = c.accentStrong) },
                                trail = item.toggle?.let { on -> { Switch(on, null); Spacer(Modifier.width(12.dp)) } }, onClick = item.onClick)
                            if (index != group.lastIndex) HorizontalDivider(Modifier.padding(start = 56.dp), color = c.border)
                        }
                    }
                }
            }
            if (shown.isEmpty()) Text("No settings match.", Modifier.padding(16.dp), color = c.text3)
        }
    }
    if (deleteDialog) DeleteDataDialog(s) { deleteDialog = false }
}
