package com.arivna.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import java.util.Locale
import kotlin.math.roundToInt

sealed class Dlg {
    class Js(val type: Int, val host: String, val message: String, val defaultValue: String?, val result: JsResult) : Dlg() {
        companion object { const val ALERT = 0; const val CONFIRM = 1; const val PROMPT = 2 }
    }
    data class Link(val url: String) : Dlg()
    class WebPermission(val origin: String, val resources: List<String>, val onAllow: () -> Unit, val onDeny: () -> Unit) : Dlg()
    class Geolocation(val origin: String, val onAllow: () -> Unit, val onDeny: () -> Unit) : Dlg()
}

class MainActivity : ComponentActivity() {
    lateinit var state: AppState
    private var speech: SpeechRecognizer? = null
    private var pendingPrivateVoice = false
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val files = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCallback = null
    }
    private var permissionRequest: PermissionRequest? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val request = permissionRequest
        permissionRequest = null
        if (request != null) {
            if (granted.values.all { it }) request.grant(request.resources) else request.deny()
        }
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var geoCallback: GeolocationPermissions.Callback? = null
    private var geoOrigin: String? = null
    private val locationPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val cb = geoCallback
        val o = geoOrigin
        geoCallback = null
        geoOrigin = null
        if (cb != null && o != null) {
            val ok = granted.values.any { it }
            cb.invoke(o, ok, false)
        }
    }

    /** Android 13+ needs the user's OK before an app can show download notifications. */
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private val voicePermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginVoiceRecognition(pendingPrivateVoice)
        else Toast.makeText(this, "Microphone permission is needed for voice search", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = AppState(this)
        state.handleIntent(intent)
        setContent { BrowserApp(state) }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); state.handleIntent(intent) }
    override fun onResume() { super.onResume(); DownloadTracker.instance?.syncService() }
    override fun onStop() { Shield.saveStats(); state.saveSession(now = true); super.onStop() }

    /** The system wants memory back: background tabs give up their WebViews (they reload from saved state when opened). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) state.discardBackgroundTabs()
    }
    override fun onDestroy() { if (isFinishing) state.onQuitCleanup(); speech?.destroy(); state.tabs.toList().forEach { it.destroy() }; super.onDestroy() }

    fun startVoiceSearch(privateMode: Boolean) {
        pendingPrivateVoice = privateMode
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePermission.launch(Manifest.permission.RECORD_AUDIO)
        } else beginVoiceRecognition(privateMode)
    }

    private val voiceHandler = android.os.Handler(android.os.Looper.getMainLooper())

    fun cancelVoice() {
        speech?.cancel(); speech?.destroy(); speech = null
        state.voice = null
    }

    /** Ends listening early (the user tapped the mic); the recogniser then delivers its final result. */
    fun stopVoice() { speech?.stopListening() }

    private fun voiceFailed(message: String, privateMode: Boolean) {
        speech?.destroy(); speech = null
        state.voice = VoiceUi(listening = false, message = message, private = privateMode)
        voiceHandler.postDelayed({ if (state.voice?.listening == false) state.voice = null }, 1800)
    }

    private fun beginVoiceRecognition(privateMode: Boolean) {
        val recognizer = if (privateMode) {
            if (Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
                Toast.makeText(this, "Private voice search needs an on-device speech model", Toast.LENGTH_LONG).show(); return
            }
            SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        } else {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Toast.makeText(this, "No speech recognition service is installed", Toast.LENGTH_LONG).show(); return
            }
            SpeechRecognizer.createSpeechRecognizer(this)
        }
        speech?.destroy(); speech = recognizer
        state.voice = VoiceUi(listening = true, private = privateMode)
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                speech?.destroy(); speech = null
                if (text.isBlank()) { voiceFailed("Didn't catch that. Try again", privateMode); return }
                state.voice = null
                state.navigate(text)
            }
            override fun onError(error: Int) {
                voiceFailed(when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Try again"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech heard"
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Voice service is offline"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed"
                    else -> "Voice search couldn't start"
                }, privateMode)
            }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) {
                val cur = state.voice ?: return
                if (!cur.listening) return
                val level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
                if (kotlin.math.abs(level - cur.level) > 0.06f) state.voice = VoiceUi(true, cur.text, "", level, privateMode)
            }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                val cur = state.voice ?: return
                if (text.isNotBlank() && cur.listening) state.voice = VoiceUi(true, text, "", cur.level, privateMode)
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, privateMode)
        })
    }
    fun openFileChooser(callback: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = callback
        return try { files.launch(params.createIntent()); true } catch (_: Exception) { fileCallback = null; callback.onReceiveValue(null); false }
    }
    fun askWebPermission(request: PermissionRequest) {
        val needed = request.resources.mapNotNull {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
                else -> null
            }
        }.distinct()
        if (needed.isEmpty()) { request.deny(); return }
        val names = request.resources.map {
            when (it) {
                PermissionRequest.RESOURCE_VIDEO_CAPTURE -> "Camera"
                PermissionRequest.RESOURCE_AUDIO_CAPTURE -> "Microphone"
                else -> it
            }
        }
        val host = try { request.origin.host ?: request.origin.toString() } catch (_: Throwable) { request.origin.toString() }
        state.dialog = Dlg.WebPermission(
            origin = host,
            resources = names,
            onAllow = {
                permissionRequest = request
                val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    request.grant(request.resources)
                    permissionRequest = null
                } else {
                    permissions.launch(missing.toTypedArray())
                }
            },
            onDeny = { request.deny() }
        )
    }

    fun requestGeolocation(origin: String, callback: GeolocationPermissions.Callback) {
        val host = try { Uri.parse(origin).host ?: origin } catch (_: Throwable) { origin }
        state.dialog = Dlg.Geolocation(
            origin = host,
            onAllow = {
                val needed = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                val missing = needed.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) {
                    callback.invoke(origin, true, false)
                } else {
                    geoCallback = callback
                    geoOrigin = origin
                    locationPermissions.launch(needed.toTypedArray())
                }
            },
            onDeny = { callback.invoke(origin, false, false) }
        )
    }
}

const val MAX_LIVE_TABS = 4
const val MAX_SAVED_TABS = 60
const val STALLED_LOAD_GRACE_MS = 15_000L

@Composable
fun BrowserApp(s: AppState) {
    val tab = s.current
    // Private tabs get their own purple-black palette so it is obvious when you are in private mode.
    val private = tab?.isPrivate == true
    val dark = private || when (s.prefs.theme) { "dark" -> true; "light" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
    ArivnaTheme(dark, private) {
        val c = Arivna.colors
        SideEffect {
            s.activity.window.statusBarColor = c.bg.toArgb()
            s.activity.window.navigationBarColor = c.bg.toArgb()
            WindowCompat.getInsetsController(s.activity.window, s.activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        LaunchedEffect(Shield.revision.intValue) { s.tabs.forEach { it.refreshProtection() } }
        var searching by remember { mutableStateOf(false) }
        LaunchedEffect(s.searchRequest) { if (s.searchRequest > 0) searching = true }
        // Scroll-to-hide: the toolbar's layout height shrinks by `hidden` px and it slides out of its own clipped box.
        val hidden = remember { mutableFloatStateOf(0f) }
        val barPx = remember { mutableFloatStateOf(0f) }
        LaunchedEffect(tab?.id, tab?.showHome, s.screen) { hidden.floatValue = 0f }
        BackHandler(searching || s.customView != null || s.screen != "browser" || tab?.canGoBack == true || tab?.isPrivate == true) {
            when {
                searching -> searching = false
                s.customView != null -> s.exitFullscreen()
                s.screen != "browser" -> s.screen = "browser"
                tab?.canGoBack == true -> tab.goBack()
                tab?.isPrivate == true -> s.exitPrivate()
            }
        }
        if (s.customView != null) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                AndroidView(factory = { ctx -> (s.customView?.parent as? ViewGroup)?.removeView(s.customView); s.customView!! }, modifier = Modifier.fillMaxSize())
            }
        } else {
            val bottom = s.prefs.addressBar == "bottom"
            // Keep the browser surface composed while the tab tray is open. Removing and
            // reattaching an Android WebView is expensive and was the largest source of tab
            // switching jank. The tray is a full-screen layer, like Firefox's tab switcher.
            val browserLayerVisible = s.screen == "browser" || s.screen == "tabs"
            val toolbar = @Composable { if (browserLayerVisible)
                Box(Modifier.clipToBounds().layout { m, cons ->
                    val p = m.measure(cons)
                    barPx.floatValue = p.height.toFloat()
                    val gone = if (s.prefs.hideToolbarOnScroll) hidden.floatValue.coerceIn(0f, barPx.floatValue).roundToInt() else 0
                    layout(p.width, p.height - gone) { p.place(0, if (bottom) 0 else -gone) }
                }) { BrowserToolbar(s, tab) }
            }
            val bars = @Composable { DownloadBanner(s); if (s.findOpen && tab?.showHome == false) FindBar(s, tab); if (tab != null && s.pickingTab === tab) PickerBar(s) }
            Box(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().navigationBarsPadding()) {
                Column(Modifier.fillMaxSize()) {
                    if (!bottom) toolbar()
                    if (!bottom) bars()
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            browserLayerVisible -> if (tab == null || tab.showHome) HomeScreen(s) else if (tab.error != null) ErrorScreen(tab) else key(tab.id) {
                                AndroidView(factory = { ctx ->
                                    tab.view(ctx).also {
                                        tab.webView?.setOnScrollChangeListener { _, _, y, _, oldY ->
                                            hidden.floatValue = if (y <= 0) 0f else (hidden.floatValue + (y - oldY)).coerceIn(0f, barPx.floatValue)
                                        }
                                    }
                                }, modifier = Modifier.fillMaxSize())
                            }
                            s.screen == "bookmarks" -> BookmarksScreen(s)
                            s.screen == "history" -> HistoryScreen(s)
                            s.screen == "downloads" -> DownloadsScreen(s)
                            s.screen == "shield" -> ShieldScreen(s, tab)
                            s.screen == "userfilters" -> UserFiltersScreen(s)
                            s.screen == "requestlog" -> RequestLogScreen(s, tab)
                            s.screen == "settings" -> SettingsScreen(s)
                            s.screen == "about" -> AboutScreen(s)
                        }
                    }
                    if (bottom) { bars(); toolbar() }
                }
                if (s.screen == "tabs") {
                    Surface(Modifier.fillMaxSize(), color = c.bg) { TabsScreen(s) }
                }
                if (searching) SearchScreen(s, tab) { searching = false }
            }
        }
        val d = s.dialog
        if (s.menu) BrowserMenu(s, tab)
        s.voice?.let { VoiceOverlay(s, it) }
        if (s.shortcutEditor) ShortcutEditor(s)
        if (s.searchEngineEditor) SearchEngineEditor(s)
        s.picked?.let { BlockElementDialog(s, it) }
        if (d is Dlg.Js) {
            var value by remember(d) { mutableStateOf(d.defaultValue ?: "") }
            AlertDialog(onDismissRequest = { d.result.cancel(); s.dialog = null }, containerColor = c.surface, titleContentColor = c.text, textContentColor = c.text2, title = { Text(d.host) }, text = {
                Column { Text(d.message); if (d.type == Dlg.Js.PROMPT) OutlinedTextField(value, { value = it }) }
            }, confirmButton = { TextButton(onClick = { if (d.result is JsPromptResult) d.result.confirm(value) else d.result.confirm(); s.dialog = null }) { Text("OK") } }, dismissButton = if (d.type == Dlg.Js.ALERT) null else { { TextButton(onClick = { d.result.cancel(); s.dialog = null }) { Text("Cancel") } } })
        }
        if (d is Dlg.Link) LinkActionsDialog(s, d)
        if (d is Dlg.WebPermission) {
            AlertDialog(
                onDismissRequest = { d.onDeny(); s.dialog = null },
                containerColor = c.surface,
                titleContentColor = c.text,
                textContentColor = c.text2,
                title = { Text("Permission request") },
                text = { Text("${d.origin} wants to use your ${d.resources.joinToString(" and ")}.") },
                confirmButton = { TextButton(onClick = { d.onAllow(); s.dialog = null }) { Text("Allow", color = c.accent) } },
                dismissButton = { TextButton(onClick = { d.onDeny(); s.dialog = null }) { Text("Block") } },
            )
        }
        if (d is Dlg.Geolocation) {
            AlertDialog(
                onDismissRequest = { d.onDeny(); s.dialog = null },
                containerColor = c.surface,
                titleContentColor = c.text,
                textContentColor = c.text2,
                title = { Text("Location request") },
                text = { Text("${d.origin} wants to know your location.") },
                confirmButton = { TextButton(onClick = { d.onAllow(); s.dialog = null }) { Text("Allow", color = c.accent) } },
                dismissButton = { TextButton(onClick = { d.onDeny(); s.dialog = null }) { Text("Block") } },
            )
        }
    }
}

