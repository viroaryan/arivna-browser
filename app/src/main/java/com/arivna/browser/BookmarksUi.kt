package com.arivna.browser

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

private val SortIcon = ImageVector.Builder("sort", 24.dp, 24.dp, 24f, 24f).addPath(
    addPathNodes("M3 6h18M3 12h12M3 18h6"), stroke = SolidColor(androidx.compose.ui.graphics.Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round,
).build()

private val sorts = listOf("newest" to "Newest first", "oldest" to "Oldest first", "az" to "A to Z")

@Composable
private fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    val c = Arivna.colors
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text2,
        title = { Text(title) }, text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }) },
        confirmButton = { TextButton(onClick = { onDone(name); onDismiss() }, enabled = name.isNotBlank()) { Text("Save", color = c.accentStrong) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditDialog(b: BookmarksStore, item: Bookmark, onDismiss: () -> Unit) {
    val c = Arivna.colors
    var title by remember { mutableStateOf(item.title) }
    var url by remember { mutableStateOf(item.url) }
    var folderId by remember { mutableStateOf(b.parents[item.id]) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text2,
        title = { Text("Edit bookmark") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Name") })
                OutlinedTextField(url, { url = it }, Modifier.padding(top = 8.dp), singleLine = true, label = { Text("URL") })
                if (b.folders.isNotEmpty()) {
                    Text("Folder", Modifier.padding(top = 12.dp), color = c.text3, style = Type.small)
                    Choice("Bookmarks", folderId == null) { folderId = null }
                    b.folders.forEach { f -> Choice(f.name, folderId == f.id) { folderId = f.id } }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                b.update(item, title.trim().ifBlank { url }, url.trim())
                b.move(item, b.folders.firstOrNull { it.id == folderId })
                onDismiss()
            }, enabled = url.isNotBlank()) { Text("Save", color = c.accentStrong) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Bookmarks with one level of folders, sorting, per-item menu and multi-select. */
@Composable
internal fun BookmarksScreen(s: AppState) {
    val c = Arivna.colors
    val b = s.bookmarks
    var sort by remember { mutableStateOf("newest") }
    var sortMenu by remember { mutableStateOf(false) }
    var folder by remember { mutableStateOf<BookmarkFolder?>(null) }
    val picked = remember { mutableStateListOf<Long>() }
    var newFolder by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<BookmarkFolder?>(null) }
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var deleting by remember { mutableStateOf<BookmarkFolder?>(null) }

    BackHandler(folder != null || picked.isNotEmpty()) { if (picked.isNotEmpty()) picked.clear() else folder = null }

    val shown = b.items.filter { b.parents[it.id] == folder?.id }.let {
        when (sort) { "az" -> it.sortedBy { x -> x.title.lowercase() }; "oldest" -> it.sortedBy { x -> x.created }; else -> it.sortedByDescending { x -> x.created } }
    }
    val folders = if (folder == null) b.folders.sortedBy { it.name.lowercase() } else emptyList()

    Column(Modifier.fillMaxSize()) {
        if (picked.isNotEmpty()) ScreenTitle("${picked.size} selected", onBack = { picked.clear() }) {
            IconButton(onClick = { s.share(b.items.filter { it.id in picked }.map { it.url }); picked.clear() }) { Icon(Ic.Share, "Share", tint = c.text) }
            IconButton(onClick = { b.items.filter { it.id in picked }.forEach(b::remove); picked.clear() }) { Icon(Ic.Trash, "Delete", tint = c.text) }
        } else ScreenTitle(folder?.name ?: "Bookmarks", onBack = { if (folder != null) folder = null else s.screen = "browser" }) {
            Box {
                IconButton(onClick = { sortMenu = true }) { Icon(SortIcon, "Sorting options", tint = c.text) }
                DropdownMenu(sortMenu, { sortMenu = false }, Modifier.background(c.surface)) {
                    sorts.forEach { (id, label) -> DropdownMenuItem(text = { Text(label, color = if (sort == id) c.accentStrong else c.text) }, onClick = { sort = id; sortMenu = false }) }
                }
            }
            if (folder == null) IconButton(onClick = { newFolder = true }) { Icon(Ic.Folder, "Add a new folder", tint = c.text) }
        }
        if (shown.isEmpty() && folders.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Ic.Bookmark, null, Modifier.size(48.dp), c.text3)
                Spacer(Modifier.height(16.dp))
                Text(if (folder == null) "Keep all your favorite bookmarks together" else "This folder is empty", color = c.text2, style = Type.body)
            }
        }
        LazyColumn {
            items(folders, key = { "f${it.id}" }) { f ->
                val n = b.parents.values.count { it == f.id }
                ListRow(f.name, "$n bookmarks", lead = { Icon(Ic.Folder, null, tint = c.text2) },
                    trail = { RowMenu("Rename" to { renaming = f }, "Delete" to { deleting = f }) }) { folder = f }
            }
            items(shown, key = { it.id }) { bm ->
                val on = bm.id in picked
                val host = android.net.Uri.parse(bm.url).host.orEmpty()
                ListRow(bm.title, bm.url.removePrefix("https://").removePrefix("http://"), selected = on,
                    lead = { SiteLogo(s, host, 24) },
                    trail = {
                        RowMenu(
                            "Edit" to { editing = bm }, "Copy" to { s.copy(bm.url) }, "Share" to { s.share(listOf(bm.url)) },
                            "Open in new tab" to { s.newTab(url = bm.url) }, "Open in private tab" to { s.newTab(true, bm.url) }, "Delete" to { b.remove(bm) },
                        )
                    },
                    onLongClick = { if (!on) picked.add(bm.id) },
                ) { if (picked.isNotEmpty()) { if (on) picked.remove(bm.id) else picked.add(bm.id) } else s.newTab(url = bm.url) }
            }
        }
    }
    if (newFolder) NameDialog("New folder", "", { b.addFolder(it) }) { newFolder = false }
    renaming?.let { f -> NameDialog("Rename folder", f.name, { b.renameFolder(f, it) }) { renaming = null } }
    editing?.let { EditDialog(b, it) { editing = null } }
    deleting?.let { f ->
        AlertDialog(
            onDismissRequest = { deleting = null }, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text2,
            title = { Text("Delete folder?") }, text = { Text("“${f.name}” and the bookmarks inside it will be deleted.") },
            confirmButton = { TextButton(onClick = { b.removeFolder(f); deleting = null }) { Text("Delete", color = c.danger) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}
