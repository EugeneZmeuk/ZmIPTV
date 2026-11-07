package com.zmeuk.zmiptv

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.ContentScale
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.exoplayer.ExoPlayer

@Composable
fun App(
    vm: TvViewModel,
    player: ExoPlayer?,
    playerError: String?,
    buffering: Boolean,
    videoSizeTick: Int,
    onPlay: (String) -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            val pl = vm.playlist
            if (vm.showSetup) {
                SetupScreen(vm)
            } else if (pl == null) {
                // first start / playlist still loading: a splash, not the settings
                SplashScreen(vm)
            } else {
                PlayerScreen(vm, pl, player, playerError, buffering, videoSizeTick, onPlay)
            }
        }
    }
}

@Composable
private fun SetupScreen(vm: TvViewModel) {
    var playlistUrl by remember(vm.settingsRevision) { mutableStateOf(vm.prefs.playlistUrl) }
    var epgUrl by remember(vm.settingsRevision) { mutableStateOf(vm.prefs.epgUrl) }
    val firstFocus = remember { FocusRequester() }
    var help by remember { mutableStateOf(false) }
    var phoneInput by remember { mutableStateOf(false) }
    if (help) {
        RemoteHelpScreen(onClose = { help = false })
        return
    }
    if (phoneInput) {
        QrInputScreen(vm, onClose = { phoneInput = false })
        return
    }
    BackHandler(enabled = vm.playlist != null) { vm.showSetup = false }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { firstFocus.requestFocus() } }

    Column(
        Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 32.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(T.settingsTitle, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
        UrlField(T.playlistUrl, playlistUrl) { playlistUrl = it }
        UrlField(T.epgUrl, epgUrl) { epgUrl = it }
        Column(Modifier.widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SettingsItem(T.qrButton, Modifier.focusRequester(firstFocus)) { phoneInput = true }
            // always bilingual, so the switch can be found whatever language is on
            SettingsItem("🌐 Language: ${vm.lang.title}") {
                vm.changeLang(Lang.entries[(vm.lang.ordinal + 1) % Lang.entries.size])
            }
            SettingsItem(T.buffer(vm.bufferLevel)) { vm.changeBufferLevel((vm.bufferLevel + 1) % 3) }
            SettingsItem(T.backupMode(vm.backupMode)) { vm.nextBackupMode() }
            SettingsItem(T.keyUp(vm.invertUpDown)) { vm.setInvert(!vm.invertUpDown) }
            SettingsItem("💾  " + T.save, enabled = !vm.loading) { vm.saveSettings(playlistUrl, epgUrl) }
            SettingsItem("⟳  " + T.reloadAll, enabled = !vm.loading && vm.playlist != null) { vm.reloadAll() }
            SettingsItem(T.helpButton) { help = true }
        }
        if (vm.loading) CircularProgressIndicator(color = Accent)
        vm.status?.let { Text(it, color = Dim, fontSize = 16.sp) }
        vm.playlist?.let {
            Text(T.stats(it.channels.size, it.groups.size, vm.favorites.size), color = Dim, fontSize = 15.sp)
        }
        vm.epgStatus?.let { Text(it, color = Dim, fontSize = 15.sp) }
        AppLog.file?.let { Text("📄 Log: ${AppLog.totalBytes() / 1024} KB · ${it.parent}", color = Dim, fontSize = 13.sp) }
    }
}

/** A remote-friendly row: visible focus frame, OK = click. */
@Composable
private fun SettingsItem(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    TvItem(modifier = modifier, onClick = { if (enabled) onClick() }) {
        Text(text, color = if (enabled) Color.White else Dim, fontSize = 17.sp)
    }
}

/**
 * Text field that does not grab the remote: ▲▼ just pass through it, OK starts editing
 * (and opens the keyboard), «Done» on the keyboard or leaving the field ends it.
 */
@Composable
private fun UrlField(label: String, value: String, onChange: (String) -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        readOnly = !editing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { editing = false; keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth()
            .onFocusChanged { if (!it.isFocused) editing = false }
            .onPreviewKeyEvent { e ->
                if (editing || e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        editing = true
                        keyboard?.show()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> focus.moveFocus(FocusDirection.Down)
                    KeyEvent.KEYCODE_DPAD_UP -> focus.moveFocus(FocusDirection.Up)
                    else -> false
                }
            },
    )
}

/** Shown while the playlist is loading (settings open only if it fails or no URL is set). */
@Composable
private fun SplashScreen(vm: TvViewModel) {
    // the same picture is the window background until the first frame (themes.xml, MainActivity.onCreate)
    Box(Modifier.fillMaxSize().background(Color(0xFF101C30))) {
        Image(
            painter = painterResource(R.drawable.splash_zm),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        // under "LIVE TV · CATCH-UP · EPG" of the picture (left edge at 44.5 % of the width, ~75 % down)
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.weight(0.73f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.fillMaxWidth(0.445f))
                CircularProgressIndicator(Modifier.size(24.dp), color = Accent, strokeWidth = 3.dp)
                Spacer(Modifier.width(14.dp))
                Text(vm.status ?: T.loadingPlaylist, color = Dim, fontSize = 18.sp)
            }
            Spacer(Modifier.weight(0.27f))
        }
    }
}
