package com.arivna.browser

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** One rounded card row of a Firefox-style sheet. */
internal class CardItem(
    val icon: ImageVector, val label: String, val sub: String? = null, val enabled: Boolean = true,
    val trailing: (@Composable () -> Unit)? = null, val onClick: () -> Unit = {},
)

/** Cards 4dp apart; the first and last get the larger outer radius. */
private fun cardShape(i: Int, n: Int, vertical: Boolean = true): RoundedCornerShape {
    val big = 24.dp; val small = 16.dp
    val first = if (i == 0) big else small
    val last = if (i == n - 1) big else small
    return if (vertical) RoundedCornerShape(first, first, last, last) else RoundedCornerShape(first, last, last, first)
}

@Composable
internal fun CardGroup(items: List<CardItem>) {
    val c = Arivna.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, item ->
            val tint = if (item.enabled) c.text2 else c.text3.copy(alpha = 0.5f)
            Surface(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = cardShape(i, items.size), color = c.surface, onClick = item.onClick, enabled = item.enabled) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, null, Modifier.size(24.dp), tint)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.label, color = if (item.enabled) c.text else tint)
                        if (item.sub != null) Text(item.sub, style = Type.small, color = c.text2)
                    }
                    item.trailing?.invoke()
                }
            }
        }
    }
}

@Composable
private fun TopActions(items: List<CardItem>) {
    val c = Arivna.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEachIndexed { i, item ->
            Surface(modifier = Modifier.weight(1f).height(56.dp), shape = cardShape(i, items.size, false), color = c.surface, onClick = item.onClick, enabled = item.enabled) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(item.icon, item.label, Modifier.size(24.dp), if (item.enabled) c.text2 else c.text3.copy(alpha = 0.5f))
                }
            }
        }
    }
}

@Composable
private fun Tiles(items: List<CardItem>) {
    val c = Arivna.colors
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { item ->
            Surface(modifier = Modifier.weight(1f).height(72.dp), shape = RoundedCornerShape(16.dp), color = c.surface, onClick = item.onClick) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(item.icon, null, Modifier.size(24.dp), c.text2)
                    Text(item.label, Modifier.padding(top = 4.dp), style = Type.label, color = c.text, maxLines = 1, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SheetScaffold(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Arivna.colors.bg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
internal fun BrowserMenu(s: AppState, tab: Tab?) {
    val c = Arivna.colors
    val onPage = tab?.showHome == false
    var more by remember { mutableStateOf(false) }
    fun go(block: () -> Unit): () -> Unit = { s.menu = false; block() }
    fun open(intent: Intent) = try { s.activity.startActivity(intent) } catch (_: Exception) { }
    val tiles = listOf(
        CardItem(Ic.History, "History", onClick = go { s.screen = "history" }),
        CardItem(Ic.Bookmark, "Bookmarks", onClick = go { s.screen = "bookmarks" }),
        CardItem(Ic.Download, "Downloads", onClick = go { s.screen = "downloads" }),
        CardItem(Ic.Key, "Passwords", onClick = go { open(Intent(android.provider.Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:"))) }),
    )
    SheetScaffold({ s.menu = false }) {
        if (onPage && tab != null) {
            TopActions(listOf(
                CardItem(Ic.Back, "Back", enabled = tab.canGoBack, onClick = go { tab.goBack() }),
                CardItem(Ic.Forward, "Forward", enabled = tab.canGoForward, onClick = go { tab.goForward() }),
                CardItem(Ic.Share, "Share", onClick = go { open(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, tab.url), "Share")) }),
                CardItem(if (tab.loading) Ic.Close else Ic.Reload, if (tab.loading) "Stop" else "Refresh", onClick = go { if (tab.loading) tab.stop() else tab.reload() }),
            ))
            val saved = s.bookmarks.isBookmarked(tab.url)
            val shortcut = s.prefs.shortcuts.firstOrNull { Uri.parse(it.url).host == tab.host }
            CardGroup(buildList {
                add(CardItem(if (saved) Ic.StarFilled else Ic.Star, if (saved) "Edit bookmark" else "Bookmark page", onClick = go { if (saved) s.screen = "bookmarks" else s.bookmarks.add(tab.displayTitle, tab.url) }))
                add(CardItem(Ic.Search, "Find in page", onClick = go { s.findOpen = true; s.findText = ""; s.findCount = "" }))
                add(CardItem(Ic.Monitor, "Desktop site", trailing = {
                    Surface(shape = RoundedCornerShape(12.dp), color = if (tab.desktop) c.accentSoft else c.surface2) {
                        Text(if (tab.desktop) "On" else "Off", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = Type.small, color = c.text)
                    }
                }, onClick = go { tab.setDesktopMode(!tab.desktop) }))
                add(CardItem(Ic.More, "More", trailing = { Icon(if (more) Ic.ChevronUp else Ic.ChevronDown, null, tint = c.text2) }, onClick = { more = !more }))
                if (more) {
                    add(CardItem(Ic.Grid, if (shortcut != null) "Remove from shortcuts" else "Add to shortcuts", onClick = go {
                        if (shortcut != null) s.prefs.removeShortcut(shortcut) else { s.prefs.addShortcut(Shortcut(tab.displayTitle.take(28), tab.url)); s.favicons.prefetch(tab.host) }
                    }))
                    add(CardItem(Ic.Home, "Add to Home screen", onClick = go { s.addToHomeScreen(tab) }))
                    add(CardItem(Ic.External, "Open in app", enabled = s.canOpenInApp(tab), onClick = go { s.openInApp(tab) }))
                    add(CardItem(Ic.FileText, "Save as PDF", onClick = go { s.saveAsPdf(tab) }))
                    add(CardItem(Ic.ShieldOff, "Block element", onClick = go { tab.startElementPicker() }))
                    add(CardItem(Ic.Code, "Network request log", onClick = go { s.screen = "requestlog" }))
                }
            })
            Tiles(tiles)
            CardGroup(listOf(CardItem(Ic.Settings, "Settings", onClick = go { s.screen = "settings" })))
        } else {
            Tiles(tiles)
            CardGroup(buildList {
                add(CardItem(Ic.Palette, "Customize homepage", onClick = go { s.screen = "settings" }))
                add(CardItem(Ic.Settings, "Settings", onClick = go { s.screen = "settings" }))
                if (s.prefs.deleteOnQuit) add(CardItem(Ic.Close, "Quit", onClick = go { s.quit() }))
            })
        }
    }
}
