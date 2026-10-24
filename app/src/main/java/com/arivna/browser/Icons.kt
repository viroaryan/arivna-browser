package com.arivna.browser

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

// Line icons (Lucide, ISC licence) drawn as stroked paths.
private fun circle(cx: Float, cy: Float, r: Float) =
    "M${cx - r} ${cy}a$r $r 0 1 0 ${2 * r} 0a$r $r 0 1 0 ${-2 * r} 0"

private fun rect(x: Float, y: Float, w: Float, h: Float, r: Float = 0f): String {
    if (r == 0f) return "M$x ${y}h${w}v${h}h${-w}Z"
    return "M${x + r} ${y}h${w - 2 * r}a$r $r 0 0 1 $r ${r}v${h - 2 * r}a$r $r 0 0 1 ${-r} ${r}h${-(w - 2 * r)}a$r $r 0 0 1 ${-r} ${-r}v${-(h - 2 * r)}a$r $r 0 0 1 $r ${-r}Z"
}

private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = "M$x1 ${y1}L$x2 $y2"
private fun poly(vararg p: Float): String {
    val sb = StringBuilder("M${p[0]} ${p[1]}")
    var i = 2
    while (i < p.size) { sb.append("L${p[i]} ${p[i + 1]}"); i += 2 }
    return sb.toString()
}

private fun icon(name: String, vararg paths: String, stroke: Float = 1.8f, filled: Boolean = false): ImageVector {
    val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
    for (p in paths) {
        b.addPath(
            pathData = addPathNodes(p),
            fill = if (filled) SolidColor(Color.Black) else null,
            stroke = if (filled) null else SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

object Ic {
    val Back = icon("back", "m12 19-7-7 7-7", "M19 12H5")
    val Forward = icon("forward", "M5 12h14", "m12 5 7 7-7 7")
    val Reload = icon("reload", "M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8", "M21 3v5h-5")
    val Close = icon("close", "M18 6 6 18", "m6 6 12 12")
    val Plus = icon("plus", "M5 12h14", "M12 5v14", stroke = 2.2f)
    val Search = icon("search", circle(11f, 11f, 8f), "m21 21-4.3-4.3")
    private const val STAR = "M12 2L15.09 8.26L22 9.27L17 14.14L18.18 21.02L12 17.77L5.82 21.02L7 14.14L2 9.27L8.91 8.26Z"
    val Star = icon("star", STAR)
    val StarFilled = icon("starFilled", STAR, filled = true)
    private const val SHIELD = "M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z"
    val ShieldCheck = icon("shieldCheck", SHIELD, "m9 12 2 2 4-4")
    val Retry = icon("retry", "M21 12a9 9 0 0 0-9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M3 12a9 9 0 0 0 9 9 9.75 9.75 0 0 0 6.74-2.74L21 16", "M16 16h5v5")
    val Shield = icon("shield", SHIELD)
    val ShieldOff = icon(
        "shieldOff", "m2 2 20 20",
        "M5 5a1 1 0 0 0-1 1v7c0 5 3.5 7.5 7.67 8.94a1 1 0 0 0 .67.01c2.35-.82 4.48-1.97 5.9-3.71",
        "M9.309 3.652A12.252 12.252 0 0 0 11.24 2.28a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1v7a9.784 9.784 0 0 1-.08 1.264"
    )
    val Download = icon("download", "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4", poly(7f, 10f, 12f, 15f, 17f, 10f), line(12f, 15f, 12f, 3f))
    val More = icon("more", circle(12f, 5f, 0.9f), circle(12f, 12f, 0.9f), circle(12f, 19f, 0.9f), stroke = 2.4f)
    val Settings = icon(
        "settings",
        "M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z",
        circle(12f, 12f, 3f)
    )
    val History = icon("history", "M3 12a9 9 0 1 0 9-9 9.75 9.75 0 0 0-6.74 2.74L3 8", "M3 3v5h5", "M12 7v5l4 2")
    val Bookmark = icon("bookmark", "m19 21-7-4-7 4V5a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v16z")
    val Monitor = icon("monitor", rect(2f, 3f, 20f, 14f, 2f), line(8f, 21f, 16f, 21f), line(12f, 17f, 12f, 21f))
    val Warning = icon("warning", "m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3", "M12 9v4", "M12 17h.01")
    val Info = icon("info", circle(12f, 12f, 10f), "M12 16v-4", "M12 8h.01")
    val Globe = icon("globe", circle(12f, 12f, 10f), "M12 2a14.5 14.5 0 0 0 0 20 14.5 14.5 0 0 0 0-20", "M2 12h20")
    val Home = icon("home", "m3 9 9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z", poly(9f, 22f, 9f, 12f, 15f, 12f, 15f, 22f))
    val Share = icon("share", "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8", poly(16f, 6f, 12f, 2f, 8f, 6f), line(12f, 2f, 12f, 15f))
    val Folder = icon("folder", "M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z")
    val FileText = icon("fileText", "M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z", "M14 2v4a2 2 0 0 0 2 2h4", "M10 9H8", "M16 13H8", "M16 17H8")
    val Trash = icon("trash", "M3 6h18", "M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6", "M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2")
    val Mic = icon("mic", "M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3Z", "M19 10v2a7 7 0 0 1-14 0v-2", line(12f, 19f, 12f, 22f))
    val Private = icon(
        "private", circle(6f, 15f, 4f), circle(18f, 15f, 4f), "M14 15a2 2 0 0 0-2-2 2 2 0 0 0-2 2",
        "M2.5 13 5 7c.7-1.3 1.4-2 3-2", "M21.5 13 19 7c-.7-1.3-1.5-2-3-2"
    )
    val ChevronRight = icon("chevronRight", "m9 18 6-6-6-6")
    val ChevronDown = icon("chevronDown", "m6 9 6 6 6-6")
    val ChevronUp = icon("chevronUp", "m18 15-6-6-6 6")
    val Check = icon("check", "M20 6 9 17l-5-5", stroke = 2.2f)
    val Image = icon("image", rect(3f, 3f, 18f, 18f, 2f), circle(9f, 9f, 2f), "m21 15-3.086-3.086a2 2 0 0 0-2.828 0L6 21")
    val Play = icon("play", rect(2f, 4f, 20f, 16f, 4f), "m10 9 5 3-5 3z")
    val Edit = icon("edit", "M21.174 6.812a1 1 0 0 0-3.986-3.987L3.842 16.174a2 2 0 0 0-.5.83l-1.321 4.352a.5.5 0 0 0 .623.622l4.353-1.32a2 2 0 0 0 .83-.497z")
    val Grid = icon("grid", rect(3f, 3f, 7f, 7f, 1f), rect(14f, 3f, 7f, 7f, 1f), rect(14f, 14f, 7f, 7f, 1f), rect(3f, 14f, 7f, 7f, 1f))
    val Palette = icon(
        "palette",
        "M12 22a1 1 0 0 1 0-20 10 9 0 0 1 10 9 5 5 0 0 1-5 5h-2.25a1.75 1.75 0 0 0-1.4 2.8l.3.4a1.75 1.75 0 0 1-1.4 2.8z",
        circle(13.5f, 6.5f, 0.6f), circle(17.5f, 10.5f, 0.6f), circle(6.5f, 12.5f, 0.6f), circle(8.5f, 7.5f, 0.6f)
    )
    val Code = icon("code", poly(16f, 18f, 22f, 12f, 16f, 6f), poly(8f, 6f, 2f, 12f, 8f, 18f))
    val Layers = icon(
        "layers", "M12.83 2.18a2 2 0 0 0-1.66 0L2.6 6.08a1 1 0 0 0 0 1.83l8.58 3.91a2 2 0 0 0 1.66 0l8.58-3.9a1 1 0 0 0 0-1.83Z",
        "m22 17.65-9.17 4.16a2 2 0 0 1-1.66 0L2 17.65", "m22 12.65-9.17 4.16a2 2 0 0 1-1.66 0L2 12.65"
    )
    val User = icon("user", circle(12f, 8f, 5f), "M20 21a8 8 0 0 0-16 0")
    val Lock = icon("lock", rect(3f, 11f, 18f, 11f, 2f), "M7 11V7a5 5 0 0 1 10 0v4")
    val Key = icon("key", circle(7.5f, 15.5f, 5.5f), "m21 2-9.6 9.6", "m15.5 7.5 3 3L22 7l-3-3")
    val External = icon("external", "M15 3h6v6", "M10 14 21 3", "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6")
    val Clipboard = icon("clipboard", rect(8f, 2f, 8f, 4f, 1f), "M16 4h2a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h2")
    val ArrowUpLeft = icon("arrowUpLeft", "M17 17 7 7", "M7 17V7h10")
}
