package com.arivna.browser

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Private | Normal switch at the top of the tray. */
@Composable
private fun RowScope.ModeTab(selected: Boolean, label: String, onClick: () -> Unit, icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit) {
    val c = Arivna.colors
    val tint = if (selected) c.accentStrong else c.text3
    Column(Modifier.weight(1f).clickable(onClick = onClick).semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon(tint)
            Text(label.removeSuffix(" tabs"), color = tint, style = Type.small, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().height(3.dp).background(if (selected) c.accentStrong else androidx.compose.ui.graphics.Color.Transparent))
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TabCard(s: AppState, tab: Tab, active: Boolean, selecting: Boolean, picked: Boolean, list: Boolean, onClick: () -> Unit, onLongClick: () -> Unit, onClose: () -> Unit) {
    val c = Arivna.colors
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { if (it != SwipeToDismissBoxValue.Settled) { onClose(); true } else false })
    val shape = RoundedCornerShape(16.dp)
    val marked = if (selecting) picked else active
    SwipeToDismissBox(swipe, backgroundContent = {}, enableDismissFromStartToEnd = !selecting, enableDismissFromEndToStart = !selecting) {
        val thumb = @Composable { mod: Modifier ->
            Box(mod.background(c.surface2), contentAlignment = Alignment.Center) {
                val bmp = tab.thumbnail
                val image = remember(bmp) { bmp?.asImageBitmap() }
                if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                else Icon(if (tab.isPrivate) Ic.Private else Ic.Globe, null, Modifier.size(24.dp), c.text3)
            }
        }
        val trail = @Composable {
            if (selecting) Checkbox(picked, null, Modifier.padding(12.dp))
            else IconButton(onClick = onClose) { Icon(Ic.Close, "Close ${tab.displayTitle}", Modifier.size(18.dp), c.text2) }
        }
        Column(Modifier.clip(shape).background(if (marked) c.accentSoft else c.surface).border(if (marked) 1.dp else 0.dp, if (marked) c.accent else androidx.compose.ui.graphics.Color.Transparent, shape).combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
            if (list) Row(verticalAlignment = Alignment.CenterVertically) {
                thumb(Modifier.size(72.dp, 64.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(tab.displayTitle, color = c.text, style = Type.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(tab.url.removePrefix("https://").removePrefix("http://"), color = c.text3, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                trail()
            } else {
                Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!tab.showHome) SiteLogo(s, tab.host, 16) else Icon(Ic.Home, null, Modifier.size(16.dp), c.text3)
                    Text(tab.displayTitle, Modifier.weight(1f).padding(start = 8.dp), color = c.text, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    trail()
                }
                thumb(Modifier.fillMaxWidth().aspectRatio(0.85f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabsScreen(s: AppState) {
    val c = Arivna.colors
    val ctx = LocalContext.current
    var showPrivate by remember { mutableStateOf(s.current?.isPrivate == true) }
    var selecting by remember { mutableStateOf(false) }
    val picked = remember { mutableStateListOf<Long>() }
    var query by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var closedList by remember { mutableStateOf(false) }
    var undo by remember { mutableStateOf<ClosedTab?>(null) }
    LaunchedEffect(undo) { if (undo != null) { delay(4000); undo = null } }
    BackHandler(selecting || query != null || closedList) {
        when { closedList -> closedList = false; selecting -> { selecting = false; picked.clear() }; else -> query = null }
    }
    if (closedList) { ClosedTabsList(s) { closedList = false }; return }

    val q = query.orEmpty().trim()
    val visible = s.tabs.filter { it.isPrivate == showPrivate && (q.isEmpty() || it.displayTitle.contains(q, true) || it.url.contains(q, true)) }
    val list = s.prefs.tabLayout == "list"

    fun closeOne(tab: Tab) {
        val prev = s.closedTabs.firstOrNull()
        s.close(tab)
        undo = if (tab.isPrivate) null else s.closedTabs.firstOrNull()?.takeIf { it !== prev }
    }
    fun endSelection() { selecting = false; picked.clear() }
    val chosen = s.tabs.filter { it.id in picked }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (selecting) {
                ScreenTitle(if (picked.isEmpty()) "Select tabs" else "${picked.size} selected", onBack = ::endSelection) {
                    IconButton(onClick = {
                        val pages = chosen.filter { !it.showHome && it.url.startsWith("http") }
                        pages.forEach { s.bookmarks.add(it.displayTitle, it.url) }
                        Toast.makeText(ctx, "Bookmarked ${pages.size}", Toast.LENGTH_SHORT).show(); endSelection()
                    }, enabled = picked.isNotEmpty()) { Icon(Ic.Star, "Bookmark", tint = c.text) }
                    IconButton(onClick = {
                        val text = chosen.filter { it.url.startsWith("http") }.joinToString("\n") { it.url }
                        s.activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null)); endSelection()
                    }, enabled = picked.isNotEmpty()) { Icon(Ic.Share, "Share", tint = c.text) }
                    IconButton(onClick = { chosen.forEach { s.close(it) }; endSelection() }, enabled = picked.isNotEmpty()) { Icon(Ic.Close, "Close selected tabs", tint = c.text) }
                }
            } else Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                if (PrivateProfile.supported) ModeTab(showPrivate, "Private tabs", { showPrivate = true }) { Icon(Ic.Private, null, Modifier.size(24.dp), it) }
                ModeTab(!showPrivate, "Normal tabs", { showPrivate = false }) { tint ->
                    Box(Modifier.size(26.dp).border(2.dp, tint, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                        Text("${s.tabs.count { !it.isPrivate }}", color = tint, fontSize = 12.sp, style = Type.label)
                    }
                }
            }
            if (visible.isEmpty()) Text(if (q.isNotEmpty()) "No tabs match." else if (showPrivate) "No private tabs open." else "No tabs open.", Modifier.padding(16.dp), color = c.text3)
            LazyVerticalGrid(
                GridCells.Fixed(if (list) 1 else 2), Modifier.weight(1f),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 10.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(visible, key = { it.id }) { tab ->
                    TabCard(s, tab, tab.id == s.activeId, selecting, tab.id in picked, list,
                        onClick = { if (selecting) { if (tab.id in picked) picked.remove(tab.id) else picked.add(tab.id) } else s.select(tab) },
                        onLongClick = { if (!selecting) { selecting = true; picked.add(tab.id) } },
                        onClose = { closeOne(tab) })
                }
                if (!showPrivate) item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        Row(Modifier.clip(CircleShape).background(c.surface).clickable { s.screen = "shield" }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Ic.ShieldCheck, null, Modifier.size(18.dp), c.text2)
                            Spacer(Modifier.width(8.dp))
                            Text("You're protected", style = Type.small, color = c.text2)
                        }
                    }
                }
            }
        }

        undo?.let { closed ->
            Surface(Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 88.dp), shape = RoundedCornerShape(12.dp), color = c.text, contentColor = c.bg) {
                Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tab closed", color = c.bg, style = Type.small)
                    TextButton(onClick = { s.reopenClosedTab(closed); undo = null }) { Text("Undo", color = c.accent) }
                }
            }
        }

        if (!selecting) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f, fill = false).clip(CircleShape).background(c.surface2), verticalAlignment = Alignment.CenterVertically) {
                if (query == null) {
                    IconButton(onClick = { query = "" }) { Icon(Ic.Search, "Search tabs", tint = c.text) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Ic.More, "Tab tray menu", tint = c.text) }
                        DropdownMenu(menu, { menu = false }, Modifier.background(c.surface)) {
                            @Composable fun entry(label: String, onClick: () -> Unit) = DropdownMenuItem(text = { Text(label, color = c.text) }, onClick = { menu = false; onClick() })
                            entry("Select tabs") { selecting = true }
                            entry("Select all") { selecting = true; picked.clear(); picked.addAll(visible.map { it.id }) }
                            entry("Recently closed tabs") { closedList = true }
                            entry("Tab settings") { SettingsNav.start = "tabs"; s.screen = "settings" }
                            entry("Close all tabs") { s.closeTabs(showPrivate) }
                        }
                    }
                } else {
                    Spacer(Modifier.width(16.dp))
                    BasicTextField(query.orEmpty(), { query = it }, Modifier.widthIn(min = 160.dp, max = 240.dp), singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = 16.sp), cursorBrush = SolidColor(c.accent),
                        decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search tabs", color = c.text3); inner() } })
                    IconButton(onClick = { query = null }) { Icon(Ic.Close, "Close search", Modifier.size(20.dp), c.text2) }
                }
            }
            Spacer(Modifier.weight(1f))
            FloatingActionButton(onClick = { s.newTab(showPrivate) }, containerColor = c.accent, contentColor = c.onAccent) { Icon(Ic.Plus, if (showPrivate) "New private tab" else "New tab") }
        }
    }
}
