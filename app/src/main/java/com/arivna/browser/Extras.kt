package com.arivna.browser

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Voice search: live transcription

class VoiceUi(val listening: Boolean, val text: String = "", val message: String = "", val level: Float = 0f, val private: Boolean = false)

@Composable
fun VoiceOverlay(s: AppState, v: VoiceUi) {
    val c = Arivna.colors
    val pulse by animateFloatAsState(1f + v.level * 0.28f, tween(120), label = "mic-level")
    Dialog(onDismissRequest = { s.activity.cancelVoice() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), shape = RoundedCornerShape(8.dp), color = c.surface, border = BorderStroke(1.dp, c.border)) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (v.listening) (if (v.text.isEmpty()) "Listening…" else "Hearing you") else "Voice search", style = Type.label, color = c.text3)
                Spacer(Modifier.height(16.dp))
                Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(80.dp).scale(pulse).background(c.accent.copy(alpha = 0.2f), CircleShape))
                    Surface(Modifier.size(64.dp).clip(CircleShape).clickable(enabled = v.listening) { s.activity.stopVoice() }, shape = CircleShape, color = c.accent) {
                        Box(contentAlignment = Alignment.Center) { Icon(Ic.Mic, "Stop listening", Modifier.size(28.dp), Color.White) }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    v.message.ifEmpty { v.text.ifEmpty { "Speak now" } }, Modifier.fillMaxWidth().height(72.dp), textAlign = TextAlign.Center,
                    style = Type.headline,
                    color = if (v.text.isNotEmpty()) c.text else if (v.message.isNotEmpty()) c.danger else c.text2,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = { s.activity.cancelVoice() }) { Text("Cancel", color = c.text2) }
            }
        }
    }
}
