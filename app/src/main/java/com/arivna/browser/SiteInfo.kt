package com.arivna.browser

import android.webkit.WebStorage
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Bottom sheet behind the toolbar shield: tracking protection for this site, connection, site data. */
@Composable
internal fun SiteInfoSheet(s: AppState, tab: Tab, dismiss: () -> Unit) {
    val c = Arivna.colors
    var confirmClear by remember { mutableStateOf(false) }
    val secure = tab.url.startsWith("https://")
    val issuer = tab.webView?.certificate?.issuedBy?.oName
    val host = tab.host.removePrefix("www.")
    SheetScaffold(dismiss) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(tab.displayTitle, style = Type.headline, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(host, style = Type.small, color = c.text2)
        }
        val on = !Shield.isAllowlisted(tab.host)
        CardGroup(listOf(
            CardItem(Ic.ShieldCheck, "Enhanced Tracking Protection  ${if (on) "On" else "Off"}", "If something looks broken on this site, try turning it off.",
                trailing = { Switch(on, null) },
                onClick = { s.prefs.setAllowed(tab.host, on); Shield.sync(s.prefs); tab.reload(); dismiss() }),
            CardItem(if (secure) Ic.Lock else Ic.Warning, if (secure) "Secure connection" else "Connection is not secure",
                if (secure && issuer != null) "Verified by $issuer" else null),
            CardItem(Ic.Trash, "Clear cookies and site data", onClick = { confirmClear = true }),
            CardItem(Ic.Settings, "Privacy settings", onClick = { dismiss(); s.screen = "settings" }),
        ))
    }
    if (confirmClear) AlertDialog(
        onDismissRequest = { confirmClear = false }, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text2,
        title = { Text("Clear cookies and site data?") },
        text = { Text("This signs you out of $host and removes its stored data.") },
        confirmButton = { TextButton(onClick = {
            clearSiteData(tab)
            Toast.makeText(s.activity, "Cookies and site data cleared", Toast.LENGTH_SHORT).show()
            confirmClear = false; dismiss(); tab.reload()
        }) { Text("Clear", color = c.danger) } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
    )
}

/** Expires every cookie the page's URL can see (host-only and domain cookies) and drops the origin's web storage. */
private fun clearSiteData(tab: Tab) {
    val cm = tab.cookieManager()
    val host = tab.host
    val names = cm.getCookie(tab.url).orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
    val domains = listOf(null, host, ".$host", ".${host.substringAfter('.')}")
    for (name in names) for (d in domains) cm.setCookie(tab.url, "$name=; Max-Age=0; Path=/" + (d?.let { "; Domain=$it" } ?: ""))
    cm.flush()
    WebStorage.getInstance().deleteOrigin(android.net.Uri.parse(tab.url).let { "${it.scheme}://${it.authority}" })
}
