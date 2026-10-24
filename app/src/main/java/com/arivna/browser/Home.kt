package com.arivna.browser

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun HomeScreen(s: AppState) {
    val c = Arivna.colors
    val p = s.prefs
    val priv = s.current?.isPrivate == true
    val back = when {
        priv -> Brush.verticalGradient(listOf(Color(0xFF25003E), Color(0xFF12001F)))
        c.isDark -> Brush.verticalGradient(listOf(Color(0xFF0F1B4C), Color(0xFF20305F)))
        else -> Brush.verticalGradient(listOf(c.bg, c.bg))
    }
    Column(Modifier.fillMaxSize().background(back).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(20.dp))
        if (priv) {
            Icon(Ic.Private, null, Modifier.size(64.dp), c.accent)
            Text("Private browsing", Modifier.padding(top = 16.dp), style = Type.title, color = c.text)
            Text("Arivna clears your history, cookies and site data when you close all your private tabs.", Modifier.padding(top = 8.dp), style = Type.small, color = c.text2, textAlign = TextAlign.Center)
            return@Column
        }
        Box(Modifier.fillMaxWidth().height(112.dp)) {
            Column(Modifier.align(Alignment.TopCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                Image(painterResource(R.drawable.arivna_logo), "Arivna", Modifier.size(72.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                Text("Arivna", Modifier.padding(top = 8.dp), color = c.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            if (PrivateProfile.supported) {
                Surface(
                    modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
                    shape = RoundedCornerShape(16.dp),
                    color = c.surface,
                    onClick = { s.newTab(true) },
                ) { Box(contentAlignment = Alignment.Center) { Icon(Ic.Private, "Open private browsing", Modifier.size(25.dp), c.accentStrong) } }
            }
        }
        Column(Modifier.fillMaxWidth()) {
            if (p.homeShortcuts) {
                Spacer(Modifier.height(24.dp))
                (p.shortcuts + null).chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { item -> ShortcutTile(item, s, Modifier.weight(1f)) }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (p.homeProtected) Surface(modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 16.dp), shape = RoundedCornerShape(24.dp), color = c.surface, onClick = { s.screen = "shield" }) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (p.shield) Ic.ShieldCheck else Ic.ShieldOff, null, Modifier.size(20.dp), c.text2)
                    Spacer(Modifier.width(8.dp))
                    Text("You're protected", color = c.text)
                }
            }
            val last = s.tabs.lastOrNull { it !== s.current && !it.showHome && !it.isPrivate }
            if (p.homeContinue && last != null) {
                SectionTitle("Continue")
                Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = c.surface, onClick = { s.select(last) }) {
                    Column {
                        val thumb = remember(last.thumbnail) { last.thumbnail?.asImageBitmap() }
                        if (thumb != null) Image(thumb, null, Modifier.fillMaxWidth().height(120.dp), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            SiteLogo(s, last.host, 24)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(last.displayTitle, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(last.host.removePrefix("www."), style = Type.small, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            if (p.homeBookmarks && s.bookmarks.items.isNotEmpty()) HomeList(s, "Bookmarks", s.bookmarks.items.take(4).map { it.title to it.url })
            val recent = s.history.recent.distinctBy { Uri.parse(it.url).host }.take(5)
            if (p.homeRecent && recent.isNotEmpty()) HomeList(s, "Recently visited", recent.map { it.title to it.url })
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SectionTitle(text: String) =
    Text(text, Modifier.padding(top = 24.dp, bottom = 8.dp), style = Type.bodyMedium, color = Arivna.colors.text)

@Composable
private fun HomeList(s: AppState, title: String, rows: List<Pair<String, String>>) {
    val c = Arivna.colors
    SectionTitle(title)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), color = c.surface) {
        Column {
            rows.forEach { (name, url) ->
                val host = Uri.parse(url).host.orEmpty()
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { s.navigate(url) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SiteLogo(s, host, 24)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(name.ifBlank { url }, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(host.removePrefix("www."), style = Type.small, color = c.text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A round site tile; a null [item] is the "Add shortcut" tile. Long-press a site for Edit / Remove. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutTile(item: Shortcut?, s: AppState, modifier: Modifier) {
    val c = Arivna.colors
    var menu by remember { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(8.dp)).combinedClickable(
        onLongClick = { if (item != null) menu = true },
        onClick = { if (item != null) s.navigate(item.url) else { s.shortcutEditing = null; s.shortcutEditor = true } },
    ).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(60.dp).clip(CircleShape).background(c.surface), contentAlignment = Alignment.Center) {
            if (item != null) SiteLogo(s, Uri.parse(item.url).host.orEmpty(), 32) else Icon(Ic.Plus, null, tint = c.text2)
        }
        Text(item?.title ?: "Add shortcut", Modifier.padding(top = 6.dp), style = Type.label, color = c.text, maxLines = 1, textAlign = TextAlign.Center, overflow = TextOverflow.Ellipsis)
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
            DropdownMenuItem(text = { Text("Edit", color = c.text) }, onClick = { menu = false; s.shortcutEditing = item; s.shortcutEditor = true })
            DropdownMenuItem(text = { Text("Remove", color = c.text) }, onClick = { menu = false; s.prefs.removeShortcut(item!!) })
        }
    }
}

@Composable
internal fun SiteLogo(s: AppState, host: String, size: Int) {
    val c = Arivna.colors
    s.favicons.version.value
    LaunchedEffect(host) { s.favicons.prefetch(host) }
    val bmp = s.favicons.get(host)
    val image = remember(bmp) { bmp?.asImageBitmap() }
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        if (image != null) Image(image, host, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        else Icon(Ic.Globe, host, Modifier.fillMaxSize(), tint = c.text3)
    }
}

@Composable
internal fun ShortcutEditor(s: AppState) {
    val old = s.shortcutEditing
    var url by remember(old) { mutableStateOf(old?.url ?: "") }
    var title by remember(old) { mutableStateOf(old?.title ?: "") }
    var error by remember(old) { mutableStateOf("") }
    AlertDialog(onDismissRequest = { s.shortcutEditor = false }, containerColor = Arivna.colors.surface, titleContentColor = Arivna.colors.text, textContentColor = Arivna.colors.text2, title = { Text(if (old == null) "Add a website" else "Edit shortcut") },
        text = { Column {
            Text("The real site logo will appear automatically.", style = Type.small)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(url, { url = it; error = "" }, label = { Text("Website URL") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(title, { title = it }, label = { Text("Label (optional)") }, singleLine = true)
            if (error.isNotBlank()) Text(error, color = Arivna.colors.danger, style = Type.small)
        } },
        confirmButton = { TextButton(onClick = {
            val normalized = url.trim().let { if (it.startsWith("http://") || it.startsWith("https://")) it else "https://$it" }
            val uri = Uri.parse(normalized)
            val host = uri.host
            if (uri.scheme !in listOf("http", "https") || host.isNullOrBlank() || !host.contains('.')) error = "Enter a valid website address"
            else {
                if (old != null) s.prefs.removeShortcut(old)
                s.prefs.addShortcut(Shortcut(title.trim().ifBlank { host.removePrefix("www.") }, normalized))
                s.favicons.prefetch(host)
                s.shortcutEditor = false
            }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { s.shortcutEditor = false }) { Text("Cancel") } })
}

@Composable
internal fun SearchEngineEditor(s: AppState) {
    var name by remember { mutableStateOf("") }
    var template by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { s.searchEngineEditor = false },
        title = { Text("Add search engine") },
        text = {
            Column {
                Text("Use %s where the search words should go. The website's real icon will be downloaded and cached.", color = Arivna.colors.text2, style = Type.small)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(name, { name = it; error = "" }, label = { Text("Name") }, placeholder = { Text("Example Search") }, singleLine = true)
                Spacer(Modifier.height(9.dp))
                OutlinedTextField(template, { template = it; error = "" }, label = { Text("Search URL") }, placeholder = { Text("https://example.com/search?q=%s") }, singleLine = true)
                if (error.isNotBlank()) Text(error, color = Arivna.colors.danger, style = Type.small, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = {
            val value = template.trim()
            val uri = runCatching { Uri.parse(value.replace("%s", "arivna")) }.getOrNull()
            when {
                name.trim().length !in 2..32 -> error = "Enter a name between 2 and 32 characters"
                !value.contains("%s") -> error = "Search URL must contain %s"
                uri?.scheme != "https" || uri.host.isNullOrBlank() -> error = "Use a valid HTTPS search URL"
                s.prefs.engines.any { it.label.equals(name.trim(), true) || it.searchUrl == value } -> error = "That search engine already exists"
                else -> {
                    s.prefs.addSearchEngine(name.trim(), value)
                    s.favicons.prefetch(uri!!.host!!)
                    s.searchEngineEditor = false
                }
            }
        }) { Text("Add") } },
        dismissButton = { TextButton(onClick = { s.searchEngineEditor = false }) { Text("Cancel") } },
    )
}


