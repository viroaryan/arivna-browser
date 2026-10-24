package com.arivna.browser

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Full-screen search opened by tapping the toolbar field; [close] returns to the page. */
@Composable
internal fun SearchScreen(s: AppState, tab: Tab?, close: () -> Unit) {
    val c = Arivna.colors
    val p = s.prefs
    val priv = tab?.isPrivate == true
    val start = if (tab?.showHome == false) tab.url else ""
    var field by remember { mutableStateOf(TextFieldValue(start, TextRange(0, start.length))) }
    var typed by remember { mutableStateOf(false) } // inline completion only while the user is adding letters, never after a delete
    var engineMenu by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<HistoryItem>>(emptyList()) }
    var remote by remember { mutableStateOf<List<String>>(emptyList()) }
    val focus = remember { FocusRequester() }
    val ctx = LocalContext.current
    val recents = p.recentSearchesRaw.split('\n').filter { it.isNotBlank() }
    val clip = remember { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty() }
    val query = field.text.trim().takeIf { it != start } ?: ""
    LaunchedEffect(Unit) { focus.requestFocus() }

    fun go(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val searchBase = p.engine.searchUrl.substringBefore("%s")
        if (!priv && parseNavInput(t, p.engine.searchUrl).startsWith(searchBase)) p.recentSearchesRaw = (listOf(t) + recents.filter { it != t }).take(20).joinToString("\n")
        s.navigate(t)
        close()
    }

    LaunchedEffect(query) {
        hits = emptyList(); remote = emptyList()
        if (query.isEmpty()) return@LaunchedEffect
        delay(120)
        if (p.suggestHistory) s.history.suggest(query, 4) { if (query == field.text.trim()) hits = it }
        val template = p.engine.suggestUrl
        if (p.suggestSearch && (!priv || p.suggestPrivate) && template != null) remote = fetchSuggestions(template, query)
    }
    // Complete a typed host inline, selected so the next key replaces it.
    LaunchedEffect(hits) {
        val t = field.text
        val host = hits.firstOrNull()?.let { Uri.parse(it.url).host }?.removePrefix("www.") ?: return@LaunchedEffect
        if (p.autocompleteUrls && typed && ' ' !in t && field.selection.collapsed && host.length > t.length && host.startsWith(t, true))
            field = TextFieldValue(host, TextRange(t.length, host.length))
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(22.dp), color = c.surface) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Row(Modifier.height(44.dp).clickable { engineMenu = true }.padding(start = 14.dp, end = 4.dp).semantics { contentDescription = "Choose search engine" }, verticalAlignment = Alignment.CenterVertically) {
                            SiteLogo(s, p.engine.host, 24)
                            Icon(Ic.ChevronDown, null, Modifier.size(16.dp), c.text2)
                        }
                        DropdownMenu(expanded = engineMenu, onDismissRequest = { engineMenu = false }, containerColor = c.bg) {
                            p.engines.forEach { engine -> DropdownMenuItem(
                                text = { Text(engine.label, color = c.text) },
                                leadingIcon = { SiteLogo(s, engine.host, 24) },
                                trailingIcon = {
                                    if (p.engine.id == engine.id) Icon(Ic.Check, "Selected", tint = c.accent)
                                    else if (!engine.builtIn) IconButton(onClick = { p.removeSearchEngine(engine.id) }) { Icon(Ic.Trash, "Remove ${engine.label}", tint = c.danger) }
                                },
                                onClick = { p.searchEngine = engine.id; engineMenu = false }
                            ) }
                            HorizontalDivider(color = c.border)
                            DropdownMenuItem(text = { Text("Add search engine", color = c.text) }, leadingIcon = { Icon(Ic.Plus, null, tint = c.accent) }, onClick = { engineMenu = false; s.searchEngineEditor = true })
                        }
                    }
                    BasicTextField(field, { typed = it.text.length > field.text.length; field = it }, modifier = Modifier.weight(1f).padding(start = 4.dp).focusRequester(focus), singleLine = true,
                        textStyle = TextStyle(color = c.text, fontSize = 16.sp), cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { go(field.text) }),
                        decorationBox = { inner -> Box(contentAlignment = Alignment.CenterStart) { if (field.text.isEmpty()) Text("Search or enter address", color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis); inner() } })
                    if (field.text.isNotEmpty()) IconButton(onClick = { field = TextFieldValue(""); typed = false }) { Icon(Ic.Close, "Clear", tint = c.text2) }
                    if (p.voiceSearch) IconButton(onClick = { close(); s.activity.startVoiceSearch(priv) }) { Icon(Ic.Mic, "Voice search", tint = c.text2) }
                }
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (p.suggestClipboard && clip.startsWith("http", true) && ' ' !in clip) SearchRow(s, Ic.Clipboard, "Fill link from clipboard", clip) { field = TextFieldValue(clip, TextRange(clip.length)); typed = false }
            if (query.isEmpty()) {
                if (p.suggestRecentSearches && recents.isNotEmpty()) {
                    Text("Recent Searches", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp), style = Type.bodyMedium, color = c.text)
                    recents.take(5).forEach { r -> SearchRow(s, Ic.History, r, null, fill = { field = TextFieldValue(r, TextRange(r.length)); typed = false }) { go(r) } }
                }
            } else {
                SearchRow(s, Ic.Search, "Search for “$query”", null, logo = p.engine.host) { go(query) }
                if (p.suggestRecentSearches) recents.filter { it.contains(query, true) && !it.equals(query, true) }.take(2).forEach { r -> SearchRow(s, Ic.History, r, null, fill = { field = TextFieldValue(r, TextRange(r.length)); typed = false }) { go(r) } }
                remote.filter { !it.equals(query, true) }.forEach { r -> SearchRow(s, Ic.Search, r, null, fill = { field = TextFieldValue(r, TextRange(r.length)); typed = false }) { go(r) } }
                if (p.suggestBookmarks) s.bookmarks.items.filter { it.title.contains(query, true) || it.url.contains(query, true) }.take(3).forEach { b -> SearchRow(s, Ic.Star, b.title, b.url) { go(b.url) } }
                if (p.suggestHistory) hits.forEach { h -> SearchRow(s, Ic.History, h.title.ifBlank { h.url }, h.url) { go(h.url) } }
            }
        }
    }
}

@Composable
private fun SearchRow(s: AppState, icon: ImageVector, title: String, subtitle: String?, logo: String? = null, fill: (() -> Unit)? = null, onClick: () -> Unit) {
    val c = Arivna.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(start = 16.dp, end = if (fill == null) 16.dp else 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (logo != null) SiteLogo(s, logo, 24) else Icon(icon, null, tint = c.text2)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.text)
            if (subtitle != null) Text(subtitle, style = Type.small, maxLines = 1, overflow = TextOverflow.Ellipsis, color = c.text2)
        }
        if (fill != null) IconButton(onClick = fill) { Icon(Ic.ArrowUpLeft, "Fill search", tint = c.text2) }
    }
}

/** Search-engine suggestions (OpenSearch JSON: ["query", ["a", "b"]]); empty on any failure. */
private suspend fun fetchSuggestions(template: String, q: String): List<String> = withContext(Dispatchers.IO) {
    try {
        val conn = URL(template.replace("%s", URLEncoder.encode(q, "UTF-8"))).openConnection() as HttpURLConnection
        conn.connectTimeout = 3000; conn.readTimeout = 3000
        try {
            val arr = JSONArray(conn.inputStream.bufferedReader().readText()).getJSONArray(1)
            (0 until arr.length()).map { arr.getString(it) }.take(5)
        } finally { conn.disconnect() }
    } catch (_: Exception) { emptyList() }
}
