@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zmeuk.zmiptv

import android.app.Activity
import android.os.SystemClock
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun PlayerScreen(
    vm: TvViewModel,
    pl: Playlist,
    player: ExoPlayer?,
    playerError: String?,
    buffering: Boolean,
    videoSizeTick: Int,
    onPlay: (String) -> Unit,
) {
    val ch = pl.channels[vm.current.coerceIn(0, pl.channels.lastIndex)]
    val archive = vm.archive?.takeIf { it.channel == vm.current }
    val activity = LocalContext.current as? Activity
    val currentPlayer by rememberUpdatedState(player)

    var listVisible by remember { mutableStateOf(false) }
    var audioMenu by remember { mutableStateOf(false) }
    var okDown by remember { mutableStateOf(false) }
    var okLong by remember { mutableStateOf(false) }
    // ◀ ▶ held in live: rewind instead of channel list / info
    var arrowDown by remember { mutableStateOf(0) }
    var arrowSeeking by remember { mutableStateOf(false) }
    var rootFocusLater by remember { mutableStateOf(false) }
    var panelVisible by remember { mutableStateOf(true) }
    var panelKey by remember { mutableLongStateOf(0L) }
    var digits by remember { mutableStateOf("") }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var paused by remember { mutableStateOf(false) }
    var pausedAt by remember { mutableLongStateOf(0L) }
    var pausedPosSec by remember { mutableLongStateOf(0L) }
    var playedMs by remember { mutableLongStateOf(0L) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var lastBack by remember { mutableLongStateOf(0L) }
    val rootFocus = remember { FocusRequester() }

    val url = remember(ch.url, archive) {
        if (archive == null) ch.url
        else Catchup.url(ch, archive.startSec, (System.currentTimeMillis() / 1000 - archive.startSec).coerceAtLeast(3600))
    }
    val nowSec = now / 1000
    val posSec: Long = when {
        archive != null -> archive.startSec + playedMs / 1000
        paused -> pausedPosSec
        else -> nowSec - vm.liveDelaySec
    }

    fun showPanel() {
        panelVisible = true
        panelKey = System.nanoTime()
    }

    fun seek(delta: Long) {
        if (!ch.hasArchive) {
            toast = T.noArchive
            return
        }
        val minSec = nowSec - ch.catchupDays * 86_400L + 60
        val base = seekTarget ?: posSec
        seekTarget = (base + delta).coerceIn(minSec, nowSec)
        showPanel()
    }

    fun togglePause() {
        val p = player ?: return
        if (!paused) {
            p.pause()
            pausedPosSec = posSec
            pausedAt = SystemClock.elapsedRealtime()
            paused = true
        } else {
            val pausedFor = SystemClock.elapsedRealtime() - pausedAt
            paused = false
            when {
                // live pause on a channel with archive: continue from where we paused
                archive == null && ch.hasArchive && pausedFor > 5_000 -> vm.startArchive(vm.current, pausedPosSec)
                // long pause in archive: the server may have dropped the session, re-request
                archive != null && pausedFor > 60_000 -> vm.startArchive(vm.current, posSec)
                else -> p.play()
            }
        }
        showPanel()
    }

    fun goLive() {
        seekTarget = null
        if (archive != null) {
            vm.stopArchive()
        } else if (paused) {
            paused = false
            player?.seekToDefaultPosition()
            player?.play()
        }
        toast = T.live
        showPanel()
    }

    // ---- effects
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
    LaunchedEffect(player, url, vm.bufferLevel) { onPlay(url); paused = false }
    LaunchedEffect(vm.notice) { vm.notice?.let { toast = it; vm.notice = null } }
    LaunchedEffect(url) {
        playedMs = 0
        var last = SystemClock.elapsedRealtime()
        while (true) {
            delay(250)
            val t = SystemClock.elapsedRealtime()
            if (currentPlayer?.isPlaying == true) playedMs += t - last
            last = t
        }
    }
    LaunchedEffect(ch.number) { showPanel() }
    LaunchedEffect(panelKey, paused, seekTarget) {
        if (!paused && seekTarget == null) { delay(6_000); panelVisible = false }
    }
    LaunchedEffect(seekTarget) {
        val t = seekTarget ?: return@LaunchedEffect
        delay(900)
        if (t >= System.currentTimeMillis() / 1000 - 15) {
            vm.stopArchive()
            paused = false
            currentPlayer?.play()
        } else {
            vm.startArchive(vm.current, t)
        }
        seekTarget = null
    }
    LaunchedEffect(digits) {
        if (digits.isNotEmpty()) {
            delay(1_500)
            val n = digits.toIntOrNull()
            if (n == null || !vm.playNumber(n)) toast = T.noChannel(digits)
            digits = ""
        }
    }
    LaunchedEffect(toast) { if (toast != null) { delay(2_500); toast = null } }
    LaunchedEffect(rootFocusLater) {
        if (rootFocusLater) { withFrameNanos { }; runCatching { rootFocus.requestFocus() }; rootFocusLater = false }
    }
    LaunchedEffect(listVisible) {
        if (!listVisible) { withFrameNanos { }; runCatching { rootFocus.requestFocus() } }
    }

    BackHandler(enabled = !listVisible) {
        when {
            seekTarget != null -> seekTarget = null
            archive != null || paused -> goLive()
            panelVisible -> panelVisible = false
            SystemClock.elapsedRealtime() - lastBack < 2_000 -> activity?.finish()
            else -> {
                lastBack = SystemClock.elapsedRealtime()
                toast = T.pressBackAgain
            }
        }
    }

    fun openAudioMenu() {
        if (vm.audioTracks.size > 1) audioMenu = true else toast = T.audioOnlyOne
    }

    fun onKey(code: Int): Boolean {
        // ◀ ▶ seek in archive / pause; in live a short press leads to the channel list / info,
        // holding them starts rewinding (see onKeyEvent), as do ⏪ ⏩ ⏯ on remotes that have them
        val controls = archive != null || paused || seekTarget != null
        val up = if (vm.invertUpDown) 1 else -1
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> vm.step(up)
            KeyEvent.KEYCODE_DPAD_DOWN -> vm.step(-up)
            KeyEvent.KEYCODE_CHANNEL_UP -> vm.step(1)
            KeyEvent.KEYCODE_CHANNEL_DOWN -> vm.step(-1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (controls) togglePause() else listVisible = true
            KeyEvent.KEYCODE_DPAD_LEFT -> if (controls) seek(-30) else listVisible = true
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (controls) seek(30) else showPanel()
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_GUIDE, KeyEvent.KEYCODE_TV_CONTENTS_MENU -> listVisible = true
            KeyEvent.KEYCODE_INFO -> showPanel()
            KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> openAudioMenu()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> togglePause()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (paused) togglePause()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (!paused) togglePause()
            KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_SKIP_BACKWARD -> seek(-300)
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_SKIP_FORWARD -> seek(300)
            KeyEvent.KEYCODE_MEDIA_STOP -> goLive()
            KeyEvent.KEYCODE_LAST_CHANNEL -> vm.back()
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                if (digits.length < 4) digits += (code - KeyEvent.KEYCODE_0).toString()
            else -> return false
        }
        return true
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(rootFocus)
            .onKeyEvent { e ->
                if (listVisible || audioMenu) return@onKeyEvent false
                val ne = e.nativeKeyEvent
                val ok = ne.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || ne.keyCode == KeyEvent.KEYCODE_ENTER ||
                    ne.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (ok) {
                    // OK acts on release; holding it opens the audio tracks
                    when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (ne.repeatCount == 0) { okDown = true; okLong = false }
                            else if (okDown && !okLong && (ne.isLongPress || ne.repeatCount >= 6)) { okLong = true; openAudioMenu() }
                        }
                        KeyEventType.KeyUp -> {
                            if (okDown && !okLong) onKey(ne.keyCode)
                            okDown = false
                        }
                    }
                    true
                } else if (ne.keyCode == KeyEvent.KEYCODE_DPAD_LEFT || ne.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    // in archive / pause they seek at once (and keep seeking while held);
                    // in live they act on release, and holding them starts rewinding
                    val back = ne.keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                    when (e.type) {
                        KeyEventType.KeyDown -> when {
                            ne.repeatCount == 0 -> {
                                arrowDown = ne.keyCode
                                arrowSeeking = archive != null || paused || seekTarget != null
                                if (arrowSeeking) onKey(ne.keyCode)
                            }
                            arrowSeeking -> onKey(ne.keyCode)
                            arrowDown == ne.keyCode && (ne.isLongPress || ne.repeatCount >= 6) -> {
                                arrowSeeking = true
                                seek(if (back) -30 else 30)
                            }
                        }
                        KeyEventType.KeyUp -> {
                            if (arrowDown == ne.keyCode && !arrowSeeking) onKey(ne.keyCode)
                            arrowDown = 0
                        }
                    }
                    true
                } else if (e.type != KeyEventType.KeyDown) false else onKey(ne.keyCode)
            }
            .focusable(),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    isFocusable = false
                    isFocusableInTouchMode = false
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    keepScreenOn = true
                }
            },
            update = {
                it.player = player
                // re-layout when the stream resolution changes (otherwise the picture can stay in a corner)
                if (videoSizeTick >= 0) it.requestLayout()
            },
        )

        if (buffering && playerError == null && !paused) {
            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Accent)
        }
        val errorText = when {
            playerError == null -> null
            archive != null -> T.archiveUnavailable
            else -> playerError
        }
        if (errorText != null) {
            Text(
                errorText,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).background(Panel, RoundedCornerShape(12.dp)).padding(20.dp),
            )
        }
        if (archive != null && !panelVisible && !listVisible) {
            Text(
                T.archiveBadge(hhmm(posSec * 1000)),
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp)
                    .background(Color(0xAA000000), RoundedCornerShape(6.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
        if (digits.isNotEmpty()) {
            Text(
                digits,
                color = Color.White,
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopEnd).padding(28.dp)
                    .background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 18.dp, vertical = 6.dp),
            )
        }
        if (panelVisible && !listVisible) {
            ControlPanel(
                vm = vm,
                ch = ch,
                posSec = posSec,
                nowMs = now,
                isArchive = archive != null,
                paused = paused,
                seekTarget = seekTarget,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (audioMenu) {
            AudioMenu(vm, onClose = { audioMenu = false; rootFocusLater = true })
        }
        if (listVisible) {
            ChannelBrowser(
                vm = vm,
                pl = pl,
                now = now,
                onClose = { listVisible = false },
                onPlay = { idx, group -> vm.play(idx, group); listVisible = false },
                onArchive = { idx, start, group -> vm.startArchive(idx, start, group); listVisible = false; showPanel() },
                onSettings = { listVisible = false; vm.showSetup = true },
            )
        }
        toast?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
                    .background(Panel, RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun ControlPanel(
    vm: TvViewModel,
    ch: Channel,
    posSec: Long,
    nowMs: Long,
    isArchive: Boolean,
    paused: Boolean,
    seekTarget: Long?,
    modifier: Modifier,
) {
    val epg = vm.epg
    val shownSec = seekTarget ?: posSec
    val shownMs = shownSec * 1000
    val prog = epg?.at(ch, shownMs)
    val next = prog?.let { p -> epg?.programmes(ch)?.firstOrNull { it.start >= p.stop } }

    Column(
        modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC0000000), Color(0xF2000000))))
            .padding(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Logo(epg?.icon(ch) ?: ch.logo, Modifier.size(width = 72.dp, height = 46.dp))
            Spacer(Modifier.width(14.dp))
            Text("${vm.positionInContext()}", color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(
                ch.name, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (vm.isFavorite(ch)) Text("  ★", color = Gold, fontSize = 22.sp)
            Spacer(Modifier.weight(1f))
            Text(hhmm(nowMs), color = Color.White, fontSize = 22.sp)
        }
        Text(
            groupLabel(ch.group) + if (ch.hasArchive) T.archiveDays(ch.catchupDays) else "",
            color = Dim, fontSize = 13.sp,
        )

        val state = when {
            seekTarget != null -> T.seekingTo(hhmmss(shownMs))
            paused -> T.pausedAt(hhmmss(shownMs))
            isArchive -> T.archivePos(hhmmss(shownMs), (nowMs / 1000 - posSec) / 60)
            else -> null
        }
        if (state != null) {
            Spacer(Modifier.height(6.dp))
            Text(state, color = Gold, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        if (prog != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                "${hhmm(prog.start)}–${hhmm(prog.stop)}  ${prog.title}", color = Color.White, fontSize = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val frac = ((shownMs - prog.start).toFloat() / (prog.stop - prog.start).coerceAtLeast(1)).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { frac },
                color = if (isArchive || paused || seekTarget != null) Gold else Accent,
                trackColor = Color(0x44FFFFFF),
                modifier = Modifier.padding(vertical = 6.dp).widthIn(max = 640.dp).fillMaxWidth(),
            )
            prog.desc?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = Dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        } else if (epg == null) {
            Text(vm.epgStatus ?: T.epgNotLoaded, color = Dim, fontSize = 14.sp)
        }
        if (next != null) {
            Text(
                T.next(hhmm(next.start), next.title), color = Dim, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            (when {
                isArchive || paused || seekTarget != null -> T.hintsArchive
                ch.hasArchive -> T.hintsLiveArchive
                else -> T.hintsLive
            }) +
                if (vm.audioTracks.size > 1) T.audioHint(vm.audioTracks.size) else "",
            color = Color(0x99FFFFFF), fontSize = 12.sp,
        )
    }
}

/** Overlay with the audio tracks of the current stream; OK picks one (remembered for the channel). */
@Composable
private fun BoxScope.AudioMenu(vm: TvViewModel, onClose: () -> Unit) {
    val first = remember { FocusRequester() }
    BackHandler { onClose() }
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { first.requestFocus() } }
    val tracks = vm.audioTracks
    val focusIdx = tracks.indexOfFirst { it.selected }.coerceAtLeast(0)
    Column(
        Modifier.align(Alignment.CenterEnd).padding(32.dp).widthIn(max = 520.dp)
            .background(Panel, RoundedCornerShape(12.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(T.audioTitle, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 10.dp, bottom = 6.dp))
        tracks.forEachIndexed { i, t ->
            TvItem(
                modifier = if (i == focusIdx) Modifier.focusRequester(first) else Modifier,
                selected = t.selected,
                onClick = { vm.selectAudio(t); onClose() },
            ) {
                Text((if (t.selected) "✓  " else "    ") + t.title(), color = Color.White, fontSize = 17.sp)
            }
        }
    }
}
