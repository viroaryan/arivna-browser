package com.arivna.browser

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------------------------
// Shared building blocks: every secondary screen is a title bar, section headers and a plain list.

/** Title bar with a back arrow ([onBack] null hides it) and optional trailing actions. */
@Composable
internal fun ScreenTitle(text: String, onBack: (() -> Unit)? = null, action: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Ic.Back, "Back", tint = Arivna.colors.text) }
        Text(text, Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp), color = Arivna.colors.text, fontSize = 22.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        action()
    }
}

@Composable
internal fun SectionHeader(text: String) {
    Text(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp), color = Arivna.colors.accentStrong, style = Type.label.copy(fontSize = 14.sp))
}

/** One list row: optional 24dp icon, title, grey subtitle, trailing action. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ListRow(
    title: String, subtitle: String = "", titleColor: Color = Arivna.colors.text,
    lead: (@Composable () -> Unit)? = null, trail: (@Composable () -> Unit)? = null, selected: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().background(if (selected) Arivna.colors.accentSoft else Color.Transparent).heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.combinedClickable(onLongClick = onLongClick, onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = if (trail != null) 4.dp else 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (lead != null) { Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { lead() }; Spacer(Modifier.width(16.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = titleColor, style = Type.body, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) Text(subtitle, style = Type.small, color = Arivna.colors.text3, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trail?.invoke()
    }
}

@Composable
internal fun SettingToggle(label: String, value: Boolean, subtitle: String = "", enabled: Boolean = true, change: (Boolean) -> Unit) {
    val c = Arivna.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(enabled) { change(!value) }.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, color = if (enabled) c.text else c.text3, style = Type.body)
            if (subtitle.isNotEmpty()) Text(subtitle, style = Type.small, color = c.text3)
        }
        Spacer(Modifier.width(12.dp))
        Switch(value, null, enabled = enabled)
    }
}

@Composable
internal fun Choice(label: String, selected: Boolean, subtitle: String = "", lead: (@Composable () -> Unit)? = null, trail: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val c = Arivna.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(start = 16.dp, end = if (trail != null) 4.dp else 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, null)
        Spacer(Modifier.width(16.dp))
        if (lead != null) { Box(Modifier.size(24.dp)) { lead() }; Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(label, color = c.text, style = Type.body)
            if (subtitle.isNotEmpty()) Text(subtitle, style = Type.small, color = c.text3)
        }
        trail?.invoke()
    }
}

// ---------------------------------------------------------------------------------------------
// Page load error

@Composable
internal fun ErrorScreen(tab: Tab) {
    val c = Arivna.colors
    val isSsl = tab.error?.code == PageError.SSL
    var showAdvanced by remember(tab.error) { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 32.dp), verticalArrangement = Arrangement.Center) {
        Icon(Ic.Warning, null, Modifier.size(40.dp), c.danger)
        Spacer(Modifier.height(16.dp))
        Text(if (isSsl) "Your connection is not private" else "This page couldn't load", style = Type.headline, color = c.text)
        Spacer(Modifier.height(8.dp))
        Text(tab.error?.description ?: "Please check your connection.", color = c.text2, style = Type.body)
        Spacer(Modifier.height(24.dp))
        if (isSsl) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { tab.cancelSsl() }, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = c.accent)) { Text("Back to safety", color = Color.White) }
                OutlinedButton(onClick = { showAdvanced = !showAdvanced }, shape = RoundedCornerShape(8.dp)) { Text(if (showAdvanced) "Hide details" else "Advanced", color = c.text) }
            }
            if (showAdvanced) {
                Spacer(Modifier.height(16.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = c.surface2, border = BorderStroke(1.dp, c.border), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("This server could not prove that it is ${tab.host}. Its security certificate may be expired, self-signed, or issued by an untrusted authority.", style = Type.small, color = c.text2)
                        TextButton(onClick = { tab.proceedSsl() }, contentPadding = PaddingValues(0.dp)) { Text("Proceed to ${tab.host} (unsafe)", color = c.danger, style = Type.bodyMedium) }
                    }
                }
            }
        } else {
            Button(onClick = { tab.reload() }, shape = RoundedCornerShape(8.dp)) { Text("Try again") }
        }
    }
}

/** A three-dot button with its own dropdown; a "Delete" entry is drawn in the danger colour. */
@Composable
internal fun RowMenu(vararg actions: Pair<String, () -> Unit>) {
    val c = Arivna.colors
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Ic.More, "More options", tint = c.text2) }
        DropdownMenu(open, { open = false }, Modifier.background(c.surface)) {
            actions.forEach { (label, run) -> DropdownMenuItem(text = { Text(label, color = if (label == "Delete") c.danger else c.text) }, onClick = { open = false; run() }) }
        }
    }
}

internal fun AppState.share(urls: List<String>) =
    activity.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, urls.joinToString("\n")), null))
