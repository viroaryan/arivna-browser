package com.arivna.browser

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier


import androidx.compose.ui.graphics.Color


import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun FindBar(s: AppState, tab: Tab) {
    val c = Arivna.colors
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(s.findText, { value ->
                s.findText = value
                if (value.isBlank()) { tab.clearFind(); s.findCount = "" } else tab.find(value)
            }, modifier = Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(8.dp), placeholder = { Text("Find in page") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { tab.findNext(true) }))
            Text(s.findCount, style = Type.small, color = c.text2, modifier = Modifier.padding(horizontal = 8.dp))
            IconButton(onClick = { tab.findNext(false) }) { Icon(Ic.ChevronUp, "Previous match", tint = c.text2) }
            IconButton(onClick = { tab.findNext(true) }) { Icon(Ic.ChevronDown, "Next match", tint = c.text2) }
            IconButton(onClick = { s.findOpen = false; s.findText = ""; s.findCount = ""; tab.clearFind() }) { Icon(Ic.Close, "Close find", tint = c.text2) }
        }
        HorizontalDivider(color = c.border)
    }
}

@Composable
internal fun LinkActionsDialog(s: AppState, link: Dlg.Link) {
    val close = { s.dialog = null }
    val actions = listOfNotNull<Pair<String, () -> Unit>>(
        "Open" to { s.current?.load(link.url) },
        "Open in new tab" to { s.newTab(url = link.url) },
        if (PrivateProfile.supported) "Open in private tab" to { s.newTab(true, link.url) } else null,
        "Copy link" to { s.copy(link.url) },
        "Share link" to { s.activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link.url), "Share link")) },
    )
    AlertDialog(onDismissRequest = close, containerColor = Arivna.colors.bg, shape = RoundedCornerShape(12.dp), title = { Text("Link actions") }, text = {
        Column {
            Text(link.url, maxLines = 3, overflow = TextOverflow.Ellipsis, style = Type.small, color = Arivna.colors.text2)
            Spacer(Modifier.height(8.dp))
            actions.forEach { (label, run) ->
                Text(label, Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { run(); close() }.wrapContentHeight(Alignment.CenterVertically), color = Arivna.colors.text)
            }
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Cancel") } })
}

@Composable
private fun TabCountButton(s: AppState) {
    val c = Arivna.colors
    IconButton(onClick = { s.current?.requestThumbnail(); s.screen = "tabs" }) {
        Box(Modifier.size(24.dp).border(2.dp, c.text2, RoundedCornerShape(6.dp)).semantics { contentDescription = "Switch tabs, ${s.tabs.size} open" }, contentAlignment = Alignment.Center) {
            Text(s.tabs.size.coerceAtMost(99).toString(), color = c.text2, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun BrowserToolbar(s: AppState, tab: Tab?) {
    val c = Arivna.colors
    val onPage = tab?.showHome == false
    var info by remember { mutableStateOf(false) }
    val openSearch: () -> Unit = { s.searchRequest++ }
    Box {
        Row(Modifier.fillMaxWidth().background(c.bg).height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(Modifier.weight(1f).height(44.dp), shape = RoundedCornerShape(22.dp), color = c.surface) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (onPage) {
                        val protectedHere = s.prefs.shield && !Shield.isAllowlisted(tab!!.host)
                        IconButton(onClick = { info = true }) { Icon(if (protectedHere) Ic.ShieldCheck else Ic.ShieldOff, "Site information", tint = c.text2) }
                        val secure = tab!!.url.startsWith("https://")
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = openSearch).semantics { contentDescription = "Address bar" }, contentAlignment = Alignment.CenterStart) {
                            Text(tab.host.removePrefix("www.").ifBlank { tab.url }, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (secure) c.text else c.danger)
                        }
                        if (tab.readerAvailable) IconButton(onClick = { tab.toggleReader() }) { Icon(Ic.FileText, "Reader view", tint = if (tab.readerOn) c.accent else c.text2) }
                    } else {
                        Row(Modifier.fillMaxHeight().clickable(onClick = openSearch).padding(start = 14.dp, end = 4.dp).semantics { contentDescription = "Search engine: ${s.prefs.engine.label}" }, verticalAlignment = Alignment.CenterVertically) {
                            SiteLogo(s, s.prefs.engine.host, 24)
                            Icon(Ic.ChevronDown, null, Modifier.size(16.dp), c.text2)
                        }
                        Box(Modifier.weight(1f).fillMaxHeight().clickable(onClick = openSearch).padding(start = 8.dp).semantics { contentDescription = "Address bar" }, contentAlignment = Alignment.CenterStart) {
                            Text("Search or enter address", color = c.text3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (s.prefs.voiceSearch) IconButton(onClick = { s.activity.startVoiceSearch(tab?.isPrivate == true) }) { Icon(Ic.Mic, "Voice search", tint = c.text2) }
                    }
                }
            }
            if (onPage) IconButton(onClick = { s.newTab(tab?.isPrivate == true) }) { Icon(Ic.Plus, "New tab", tint = c.text2) }
            TabCountButton(s)
            IconButton(onClick = { s.menu = true }) { Icon(Ic.More, "Menu", tint = c.text2) }
        }
        // Overlaid so showing it never changes the layout height.
        if (tab?.loading == true) LinearProgressIndicator(progress = { tab.progress / 100f }, modifier = Modifier.fillMaxWidth().height(2.dp).align(if (s.prefs.addressBar == "bottom") Alignment.TopCenter else Alignment.BottomCenter), color = c.accent, trackColor = Color.Transparent)
    }
    if (info && onPage) SiteInfoSheet(s, tab!!) { info = false }
}
