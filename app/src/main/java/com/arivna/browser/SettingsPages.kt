package com.arivna.browser

import android.app.DownloadManager
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

internal val LINK_MODES = listOf("always" to "Always", "ask" to "Ask before opening", "never" to "Never")
private val ASK_BLOCK = listOf("ask" to "Ask to allow", "block" to "Blocked")

/** A permission page: the stored value is one of [options]. */
private class Perm(val label: String, val options: List<Pair<String, String>>, val get: (Prefs) -> String, val set: (Prefs, String) -> Unit)

private val PERMS = linkedMapOf(
    "autoplay" to Perm("Autoplay", listOf("allow" to "Allow audio and video", "blockAudio" to "Block audio only", "blockAll" to "Block audio and video"), { it.autoplay }, { p, v -> p.autoplay = v }),
    "camera" to Perm("Camera", ASK_BLOCK, { it.permCamera }, { p, v -> p.permCamera = v }),
    "location" to Perm("Location", ASK_BLOCK, { it.permLocation }, { p, v -> p.permLocation = v }),
    "microphone" to Perm("Microphone", ASK_BLOCK, { it.permMicrophone }, { p, v -> p.permMicrophone = v }),
    "notification" to Perm("Notification", ASK_BLOCK, { it.permNotification }, { p, v -> p.permNotification = v }),
    "storage" to Perm("Persistent storage", ASK_BLOCK, { it.permStorage }, { p, v -> p.permStorage = v }),
)

@Composable
private fun Radios(options: List<Pair<String, String>>, current: String, set: (String) -> Unit) =
    options.forEach { (id, label) -> Choice(label, current == id) { set(id) } }

@Composable
private fun Page(title: String, back: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle(title, back)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), content = content)
    }
}

@Composable
private fun Note(text: String) = Text(text, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = Arivna.colors.text3, style = Type.small)

/** Opens the sub page named [page]; [go] pushes another page, [back] pops. */
@Composable
internal fun SettingsPage(s: AppState, page: String, go: (String) -> Unit, back: () -> Unit) {
    val c = Arivna.colors
    val p = s.prefs
    val applyAll = { s.tabs.forEach(Tab::applySettings) }
    when {
        page == "search" -> Page("Search", back) {
            SectionHeader("Default search engine")
            p.engines.forEach { e -> Choice(e.label, p.searchEngine == e.id, lead = { SiteLogo(s, e.host, 24) }) { p.searchEngine = e.id } }
            ListRow("Manage alternative search engines", "Add or remove search engines") { go("engines") }
            SectionHeader("Suggestions from search engines")
            SettingToggle("Show search suggestions", p.suggestSearch) { p.suggestSearch = it }
            SettingToggle("Show in private sessions", p.suggestPrivate) { p.suggestPrivate = it }
            SettingToggle("Show recent searches", p.suggestRecentSearches) { p.suggestRecentSearches = it }
            SectionHeader("Address bar")
            SettingToggle("Search browsing history", p.suggestHistory) { p.suggestHistory = it }
            SettingToggle("Search bookmarks", p.suggestBookmarks) { p.suggestBookmarks = it }
            SectionHeader("Address bar preferences")
            SettingToggle("Show clipboard suggestions", p.suggestClipboard) { p.suggestClipboard = it }
            SettingToggle("Show voice search", p.voiceSearch) { p.voiceSearch = it }
            SettingToggle("Autocomplete URLs", p.autocompleteUrls) { p.autocompleteUrls = it }
        }
        page == "engines" -> Page("Manage alternative search engines", back) {
            p.engines.forEach { e ->
                ListRow(e.label, e.host, lead = { SiteLogo(s, e.host, 24) },
                    trail = if (e.builtIn) null else ({ IconButton(onClick = { p.removeSearchEngine(e.id) }) { Icon(Ic.Trash, "Remove ${e.label}", Modifier.size(24.dp), c.text2) } }))
            }
            ListRow("Add search engine", lead = { Icon(Ic.Plus, null, tint = c.accentStrong) }, titleColor = c.accentStrong) { s.searchEngineEditor = true }
        }
        page == "tabs" -> Page("Tabs", back) {
            SectionHeader("Tab layout")
            Radios(listOf("list" to "List", "grid" to "Grid"), p.tabLayout) { p.tabLayout = it }
            SectionHeader("Close tabs")
            Radios(listOf("never" to "Never", "day" to "After one day", "week" to "After one week", "month" to "After one month"), p.closeTabs) { p.closeTabs = it }
        }
        page == "homepage" -> Page("Homepage", back) {
            SettingToggle("Shortcuts", p.homeShortcuts) { p.homeShortcuts = it }
            SettingToggle("Privacy report", p.homeProtected) { p.homeProtected = it }
            SettingToggle("Continue", p.homeContinue) { p.homeContinue = it }
            SettingToggle("Bookmarks", p.homeBookmarks) { p.homeBookmarks = it }
            SettingToggle("Recently visited", p.homeRecent) { p.homeRecent = it }
            SectionHeader("Opening screen")
            Radios(listOf("home" to "Homepage", "last" to "Last tab", "after4h" to "Homepage after four hours of inactivity"), p.openingScreen) { p.openingScreen = it }
        }
        page == "customize" -> Page("Customize", back) {
            SectionHeader("Theme")
            Radios(listOf("light" to "Light", "dark" to "Dark", "system" to "Follow device theme"), p.theme) { p.theme = it }
            SettingToggle("Night mode for websites", p.nightMode) { p.nightMode = it; s.tabs.forEach(Tab::applyNightMode) }
            SectionHeader("Address bar location")
            Radios(listOf("top" to "Top", "bottom" to "Bottom"), p.addressBar) { p.addressBar = it }
            SectionHeader("Gestures")
            SettingToggle("Pull to refresh", p.pullToRefresh) { p.pullToRefresh = it; applyAll() }
            SettingToggle("Scroll to hide toolbar", p.hideToolbarOnScroll) { p.hideToolbarOnScroll = it }
        }
        page == "accessibility" -> Page("Accessibility", back) {
            SettingToggle("Automatic font sizing", p.autoFontSize, "Font size will match your Android settings. Disable to manage font size here.") { p.autoFontSize = it; applyAll() }
            if (!p.autoFontSize) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Font size", color = c.text, style = Type.body)
                    Text("Make text on websites larger or smaller", color = c.text3, style = Type.small)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Slider(p.textZoom.toFloat(), { p.textZoom = (it / 5).roundToInt() * 5 }, Modifier.weight(1f), onValueChangeFinished = applyAll, valueRange = 50f..200f)
                        Text("${p.textZoom} %", Modifier.padding(start = 12.dp), color = c.text2, style = Type.small)
                    }
                    Text("This is sample text. It is here to show how text will appear when you increase or decrease the size with this setting.",
                        color = c.text, fontSize = (16 * p.textZoom / 100f).sp, lineHeight = (22 * p.textZoom / 100f).sp)
                }
            }
            SettingToggle("Zoom on all websites", p.zoomAllSites, "Enable to allow pinch and zoom, even on websites that prevent this gesture.") { p.zoomAllSites = it; applyAll() }
        }
        page == "etp" -> Page("Enhanced Tracking Protection", back) {
            Note("Arivna protects you from many of the most common trackers that follow what you do online.")
            Choice("Standard (default)", !p.shieldStrict, "Pages will load normally, but block fewer trackers.") { p.shieldStrict = false; Shield.sync(p); s.refreshShieldTabs() }
            Choice("Strict", p.shieldStrict, "Stronger tracking protection and faster performance, but some sites may not work properly.") { p.shieldStrict = true; Shield.sync(p); s.refreshShieldTabs() }
            SettingToggle("Tell websites not to share & sell data", p.dnt) { p.dnt = it; Shield.sync(p) }
            ListRow("Exceptions", "${p.allowlist.size} sites") { go("exceptions") }
            SectionHeader("Ad blocker")
            SettingToggle("Block ads and trackers", p.shield) { p.shield = it; Shield.sync(p); s.refreshShieldTabs() }
            SettingToggle("YouTube ad cleanup", p.youtubeAds) { p.youtubeAds = it; Shield.sync(p); s.refreshShieldTabs() }
            ListRow("Privacy shield", "Blocked requests, filter lists, request log and my filters", lead = { Icon(Ic.Shield, null, tint = c.text2) }) { s.screen = "shield" }
        }
        page == "exceptions" -> Page("Exceptions", back) {
            val hosts = p.allowlist.sorted()
            if (hosts.isEmpty()) Note("Sites with Enhanced Tracking Protection turned off appear here.")
            hosts.forEach { h ->
                ListRow(h, lead = { SiteLogo(s, h, 24) }, trail = {
                    IconButton(onClick = { p.setAllowed(h, false); Shield.sync(p) }) { Icon(Ic.Trash, "Remove $h", Modifier.size(24.dp), c.text2) }
                })
            }
        }
        page == "site" -> Page("Site settings", back) {
            SectionHeader("Content")
            SettingToggle("Always request desktop site", p.desktopDefault) { p.desktopDefault = it; applyAll() }
            SettingToggle("Block pop-ups", p.blockPopups) { p.blockPopups = it }
            SettingToggle("JavaScript", p.javascript) { p.javascript = it; applyAll() }
            SectionHeader("Permissions")
            PERMS.forEach { (key, perm) -> ListRow(perm.label, perm.options.firstOrNull { it.first == perm.get(p) }?.second.orEmpty()) { go("perm:$key") } }
            SettingToggle("Block cross-site cookies", p.block3pCookies) { p.block3pCookies = it; applyAll() }
            ListRow("Exceptions", "Sites with their own permissions") { go("siteperms") }
        }
        page.startsWith("perm:") -> PERMS[page.removePrefix("perm:")]?.let { perm ->
            Page(perm.label, back) { Radios(perm.options, perm.get(p)) { perm.set(p, it) } }
        }
        page == "siteperms" -> Page("Exceptions", back) {
            // sitePermsRaw lines are "host|kind|ask/block"
            val lines = p.sitePermsRaw.lines().filter { it.count { ch -> ch == '|' } == 2 }
            if (lines.isEmpty()) Note("Permissions you set for a single site appear here.")
            lines.forEach { line ->
                val (host, kind, value) = line.split('|')
                ListRow(host, (PERMS[kind]?.label ?: kind) + ": " + if (value == "block") "Blocked" else "Ask to allow", lead = { SiteLogo(s, host, 24) }, trail = {
                    IconButton(onClick = { p.sitePermsRaw = p.sitePermsRaw.lines().filter { it != line }.joinToString("\n") }) { Icon(Ic.Trash, "Remove $host", Modifier.size(24.dp), c.text2) }
                })
            }
        }
        page == "quit" -> Page("Delete browsing data on quit", back) {
            SettingToggle("Delete browsing data on quit", p.deleteOnQuit, "Automatically deletes browsing data when you select “Quit” from the main menu") { p.deleteOnQuit = it }
            val on = p.deleteOnQuit
            SettingToggle("Open tabs", p.quitTabs, enabled = on) { p.quitTabs = it }
            SettingToggle("Browsing history", p.quitHistory, enabled = on) { p.quitHistory = it }
            SettingToggle("Cookies and site data", p.quitCookies, "You’ll be logged out of most sites", on) { p.quitCookies = it }
            SettingToggle("Cached images and files", p.quitCache, "Frees up storage space", on) { p.quitCache = it }
            SettingToggle("Site permissions", p.quitPerms, enabled = on) { p.quitPerms = it }
            SettingToggle("Downloads", p.quitDownloads, enabled = on) { p.quitDownloads = it }
        }
        page == "links" -> Page("Open links in apps", back) { Radios(LINK_MODES, p.openLinksInApps) { p.openLinksInApps = it } }
        page == "downloads" -> Page("Download Settings", back) {
            SectionHeader("File storage")
            ListRow("Default download location", "Downloads")
            SectionHeader("Delete or remove downloads")
            Choice("Delete from device", p.deleteDownloadMode == "device", "File is deleted from your device and removed from download history") { p.deleteDownloadMode = "device" }
            Choice("Remove from download history", p.deleteDownloadMode == "history", "File is removed from your download history, but is still saved on your device") { p.deleteDownloadMode = "history" }
            SettingToggle("Ask when I delete files", p.askDeleteDownload) { p.askDeleteDownload = it }
            ListRow("Manage downloads with another app") { runCatching { s.activity.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } }
        }
        page == "about" -> AboutScreen(s, back)
        else -> Unit
    }
}

/** Settings > Delete browsing data: pick what to delete, then Delete. */
@Composable
internal fun DeleteDataDialog(s: AppState, onDismiss: () -> Unit) {
    val c = Arivna.colors
    val labels = listOf("Open tabs", "Browsing history", "Cookies and site data", "Cached images and files", "Site permissions", "Downloads")
    val checked = remember { mutableStateListOf(true, true, true, true, false, false) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text2,
        title = { Text("Delete browsing data") },
        text = {
            Column {
                labels.forEachIndexed { i, label ->
                    Row(Modifier.fillMaxWidth().clickable { checked[i] = !checked[i] }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked[i], null, Modifier.padding(vertical = 12.dp)); Spacer(Modifier.width(16.dp)); Text(label, color = c.text)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                s.deleteBrowsingData(checked[0], checked[1], checked[2], checked[3], checked[4], checked[5])
                Toast.makeText(s.activity, "Browsing data deleted", Toast.LENGTH_SHORT).show(); onDismiss()
            }, enabled = checked.any { it }) { Text("Delete", color = c.danger) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Settings > About: version, WebView version, filter lists, team, links and licences. */
@Composable
internal fun AboutScreen(s: AppState, onBack: () -> Unit = { s.screen = "browser" }) {
    val c = Arivna.colors
    val open: (String) -> Unit = { url -> runCatching { s.activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    val version = remember { runCatching { s.activity.packageManager.getPackageInfo(s.activity.packageName, 0).versionName }.getOrNull().orEmpty() }
    val webView = remember { WebView.getCurrentWebViewPackage()?.versionName ?: "unknown" }
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("About Arivna", onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.arivna_logo), "Arivna", Modifier.size(96.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Spacer(Modifier.height(12.dp))
                Text("Arivna Browser", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.Medium)
                Text("Version $version", style = Type.small, color = c.text3)
                Text("WebView $webView", style = Type.small, color = c.text3)
            }
            Text("A fast, focused browser with a native privacy shield. Your history stays on your device.", Modifier.padding(horizontal = 16.dp), color = c.text2, style = Type.body)
            SectionHeader("Filter lists")
            FilterLists(s)
            SectionHeader("Team")
            listOf(
                "Abhiraj Aryan" to "Lead developer · Browser engine and ad blocking",
                "Rahul Kumar Pal" to "Product and visual design",
                "Priyanshu Singh" to "Testing and error resolution",
                "Shivam Giri" to "Testing and error resolution",
            ).forEach { (name, role) -> ListRow(name, role, lead = { Icon(Ic.User, null, tint = c.text2) }) }
            SectionHeader("Connect")
            ListRow("GitHub", "github.com/viroaryan", lead = { Icon(Ic.Code, null, tint = c.text2) }) { open("https://github.com/viroaryan") }
            ListRow("Instagram", "instagram.com/viro.coder.aryan", lead = { Icon(Ic.User, null, tint = c.text2) }) { open("https://instagram.com/viro.coder.aryan") }
            SectionHeader("Licensing information")
            Text("Built with Kotlin, Jetpack Compose, Android WebView and Rust. Icons are from Lucide (ISC licence). Filter lists (EasyList, EasyPrivacy, AdGuard, uBlock Origin filters, Peter Lowe, URLhaus) belong to their authors and are used under their own licences.",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = c.text3, style = Type.small)
            Spacer(Modifier.height(24.dp))
        }
    }
}
