@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zmeuk.zmiptv

import android.graphics.drawable.ColorDrawable
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistTracker

class MainActivity : ComponentActivity() {
    private val vm: TvViewModel by viewModels()

    private var player by mutableStateOf<ExoPlayer?>(null)
    private var playerError by mutableStateOf<String?>(null)
    private var buffering by mutableStateOf(false)
    private var videoSizeTick by mutableIntStateOf(0)
    private var currentUrl: String? = null
    private var currentLevel = -1
    private var failures = 0
    /** When the automatic restarts after a stall happened (last 2 minutes). */
    private val stallRestarts = ArrayDeque<Long>()

    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { restart() }

    /** Every 30 s (every 5 s in debug builds): a line with the player state for the journal. */
    private val stats = object : Runnable {
        override fun run() {
            player?.let { p ->
                AppLog.i("stats " + statsLine(p) + " · " + net.line())
                net = NetStats()
            }
            handler.postDelayed(this, if (BuildConfig.DEBUG) 5_000 else 30_000)
        }
    }

    private fun statsLine(p: ExoPlayer): String {
        // the provider has no EXT-X-PROGRAM-DATE-TIME, so currentLiveOffset is unknown:
        // measure the distance to the end of the playlist window instead
        val tl = p.currentTimeline
        val edge = if (!tl.isEmpty && p.isCurrentMediaItemLive) {
            val w = tl.getWindow(p.currentMediaItemIndex, Timeline.Window())
            "${(w.durationMs - p.currentPosition) / 1000}s/${w.durationMs / 1000}s"
        } else "-"
        // segment names of this provider are recording times (…/1793661299000.ts): the real stream time
        val seg = (p.currentManifest as? HlsManifest)?.mediaPlaylist?.segments
            ?.lastOrNull { it.relativeStartTimeUs <= p.currentPosition * 1000 }?.url
        val streamTime = seg?.let { SEGMENT_TIME.find(it)?.groupValues?.get(1)?.toLongOrNull() }?.let { hhmmss(it) } ?: "?"
        val v = p.videoFormat
        val video = if (v != null) "${v.width}x${v.height} ${if (v.bitrate > 0) "${v.bitrate / 1000}kbps" else ""}".trim() else "-"
        val dc = p.videoDecoderCounters
        return "ch=\"${vm.channel?.name}\" ${if (vm.archive != null) "ARCHIVE" else "live"} state=${stateName(p.playbackState)} " +
            "playing=${p.isPlaying} buffered=${p.totalBufferedDuration / 1000}s delay=${vm.liveDelaySec}s edge/window=$edge " +
            "streamTime=$streamTime video=$video frames=${dc?.renderedOutputBufferCount} dropped=${dc?.droppedBufferCount} " +
            "level=${vm.bufferLevel} ${ZmApp.memory()}"
    }

    private fun stateName(s: Int) = when (s) {
        Player.STATE_IDLE -> "idle"
        Player.STATE_BUFFERING -> "buffering"
        Player.STATE_READY -> "ready"
        else -> "ended"
    }

    /**
     * Network figures between two stats lines: how long 10-s segments take to download is what
     * decides whether the buffer grows or melts (download time > segment length = the server is too slow).
     */
    private class NetStats {
        var segments = 0; var bytes = 0L; var loadMs = 0L; var mediaMs = 0L; var maxLoadMs = 0L
        var playlists = 0; var playlistMs = 0L; var maxPlaylistMs = 0L; var errors = 0
        fun line(): String {
            if (segments == 0 && playlists == 0 && errors == 0) return "net: no loads"
            val mbps = if (loadMs > 0) bytes * 8.0 / loadMs / 1000 else 0.0
            val ratio = if (mediaMs > 0) loadMs * 100 / mediaMs else 0
            return "net: $segments segs ${bytes / 1024} KB, load ${if (segments > 0) loadMs / segments else 0} ms avg / $maxLoadMs ms max " +
                "for ${if (segments > 0) mediaMs / segments else 0} ms of video ($ratio% of real time), ${"%.1f".format(mbps)} Mbit/s · " +
                "playlist $playlists× ${if (playlists > 0) playlistMs / playlists else 0} ms avg / $maxPlaylistMs ms max · errors $errors"
        }
    }
    private var net = NetStats()

    private val netListener = object : AnalyticsListener {
        override fun onLoadCompleted(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData) {
            when (data.dataType) {
                C.DATA_TYPE_MEDIA -> net.apply {
                    segments++; bytes += info.bytesLoaded; loadMs += info.loadDurationMs
                    maxLoadMs = maxOf(maxLoadMs, info.loadDurationMs)
                    if (data.mediaEndTimeMs != C.TIME_UNSET && data.mediaStartTimeMs != C.TIME_UNSET) mediaMs += data.mediaEndTimeMs - data.mediaStartTimeMs
                }
                C.DATA_TYPE_MANIFEST -> net.apply {
                    playlists++; playlistMs += info.loadDurationMs; maxPlaylistMs = maxOf(maxPlaylistMs, info.loadDurationMs)
                }
            }
        }

        override fun onLoadError(t: AnalyticsListener.EventTime, info: LoadEventInfo, data: MediaLoadData, error: java.io.IOException, wasCanceled: Boolean) {
            net.errors++
            AppLog.w("load error (${if (data.dataType == C.DATA_TYPE_MANIFEST) "playlist" else "segment"}, after ${info.loadDurationMs} ms): " +
                "${error.javaClass.simpleName}: ${error.message?.take(160)}")
        }
    }

    /** Audio tracks are read from the stream itself (ExoPlayer shows only one per codec), see AudioPids.kt. */
    private val audioCtl = AudioPidControl()
    private var audioStream: List<TsAudio> = emptyList()

    private fun showAudioTracks(list: List<TsAudio>) {
        audioStream = list
        vm.audioTracks = AudioTrackItem.fromStream(list, audioCtl.match(list))
        if (list.size > 1) AppLog.i("audio tracks: " + vm.audioTracks.joinToString(" | ") { "${it.key} ${it.title()}${if (it.selected) " *" else ""}" })
    }

    /** Switches to [item] and remembers it for the current channel; the stream is re-opened (~1-2 s). */
    private fun chooseAudio(item: AudioTrackItem) {
        val ch = vm.channel ?: return
        val default = AudioTrackItem.fromStream(audioStream, null).firstOrNull { it.selected }
        // the default track needs no rewriting: store "no choice" so segments stream as usual
        audioCtl.target = if (item.pid == default?.pid) null else item.key
        vm.saveAudio(ch.name, audioCtl.target ?: "")
        AppLog.i("audio: \"${ch.name}\" -> ${item.key} (${item.title()})")
        vm.audioTracks = AudioTrackItem.fromStream(audioStream, audioCtl.match(audioStream))
        restart()
    }

    /** When the current buffering started (for logging how long the picture stood still). */
    private var bufferingSince = 0L
    private var everReady = false

    /** Archive: the server answers with an empty playlist when it has no recording — tell after 4 s, not 10. */
    private val archiveEmptyCheck = Runnable { checkEmptyArchive() }

    private fun checkEmptyArchive(): Boolean {
        val p = player ?: return false
        if (vm.archive == null || p.playbackState != Player.STATE_BUFFERING) return false
        val segments = (p.currentManifest as? HlsManifest)?.mediaPlaylist?.segments
        if (!segments.isNullOrEmpty()) return false
        AppLog.w("archive: the server has no recording for \"${vm.channel?.name}\" from ${vm.archive?.let { hhmmss(it.startSec * 1000) }} (empty playlist)")
        handler.removeCallbacks(stallCheck)
        buffering = false
        playerError = T.archiveUnavailable
        vm.channel?.let { vm.recheckArchive(it) }
        return true
    }

    /** Fires when the player has been buffering for STALL_MS: the stream is frozen or too slow. */
    private val stallCheck = Runnable {
        val p = player ?: return@Runnable
        if (p.playbackState != Player.STATE_BUFFERING || !p.playWhenReady) return@Runnable
        // archive the server has nothing for (empty playlist): say so instead of retrying forever
        if (checkEmptyArchive()) return@Runnable
        onStall("buffering")
    }

    /** Archive start (unix s) still to be aligned to the exact segment once the playlist arrives. */
    private var alignArchiveTo: Long? = null
    private var lastFrames = -1L
    private var frozenMs = 0L
    /** Start of the current JERKY_WINDOW_MS window and the decoder counters at that moment. */
    private var jerkyFrom = 0L
    private var jerkyRendered = 0
    private var jerkyDropped = 0

    /**
     * After a failed load ExoPlayer can stay READY and "playing" on a live stream while no new
     * frames arrive (the clock runs, the picture is frozen, no sound). Catch that by watching the
     * rendered video/audio buffer counters.
     */
    private val frozenCheck = object : Runnable {
        override fun run() {
            val p = player
            if (p != null) {
                liveDelay(p)?.let { if (it != vm.liveDelaySec) vm.liveDelaySec = it }
                // video is watched on its own: on the TV the picture froze for 30 s while the sound went on
                val frames = if (p.videoFormat != null) (p.videoDecoderCounters?.renderedOutputBufferCount ?: 0).toLong()
                else (p.audioDecoderCounters?.renderedOutputBufferCount ?: 0).toLong()
                if (p.isPlaying && frames == lastFrames) {
                    frozenMs += FROZEN_CHECK_MS
                    if (frozenMs >= STALL_MS) {
                        frozenMs = 0
                        onStall(if (p.videoFormat != null) "frozen picture" else "no sound")
                    }
                } else {
                    frozenMs = 0
                }
                lastFrames = frames
                checkJerky(p)
            }
            handler.postDelayed(this, FROZEN_CHECK_MS)
        }
    }

    /** Seconds the live picture is behind real time; null in archive or before the playlist is known. */
    private fun liveDelay(p: ExoPlayer): Long? {
        if (vm.archive != null || !p.isCurrentMediaItemLive || p.currentTimeline.isEmpty) return null
        val posMs = p.currentPosition
        val w = p.currentTimeline.getWindow(p.currentMediaItemIndex, Timeline.Window())
        // at least the distance to the live edge (all that is known for other providers)
        val edge = ((w.durationMs - posMs) / 1000).coerceAtLeast(0)
        val seg = (p.currentManifest as? HlsManifest)?.mediaPlaylist?.segments
            ?.lastOrNull { it.relativeStartTimeUs <= posMs * 1000 }
        val segMs = seg?.let { SEGMENT_TIME.find(it.url)?.groupValues?.get(1)?.toLongOrNull() }
        if (seg != null && segMs != null) {
            val shownMs = segMs + (posMs - seg.relativeStartTimeUs / 1000)
            val delay = (System.currentTimeMillis() - shownMs) / 1000
            // segment names are recording times, unless the server clock is off (Arena Esport: 8 min)
            if (delay in edge - 5..edge + MAX_ENCODER_DELAY_S) return delay
        }
        return edge
    }

    /**
     * Many dropped frames: the picture jerks. Seen on "Кино UHD": half of its audio frames are missing,
     * ExoPlayer follows the audio clock and runs the video twice as fast, dropping every second frame.
     */
    private fun checkJerky(p: ExoPlayer) {
        val dc = p.videoDecoderCounters
        if (!p.isPlaying || dc == null || vm.archive != null) { jerkyFrom = 0; return }
        dc.ensureUpdated()
        val now = SystemClock.elapsedRealtime()
        if (jerkyFrom == 0L || dc.renderedOutputBufferCount < jerkyRendered) {
            jerkyFrom = now
            jerkyRendered = dc.renderedOutputBufferCount
            jerkyDropped = dc.droppedBufferCount
            return
        }
        if (now - jerkyFrom < JERKY_WINDOW_MS) return
        val rendered = dc.renderedOutputBufferCount - jerkyRendered
        val dropped = dc.droppedBufferCount - jerkyDropped
        jerkyFrom = 0
        if (dropped >= JERKY_MIN_DROPPED && dropped * 4 >= rendered + dropped) {
            AppLog.w("jerky: $dropped of ${rendered + dropped} frames dropped in ${JERKY_WINDOW_MS / 1000} s · ${statsLine(p)}")
            switchToBackup(T.reasonJerky)
        }
    }

    private fun onStall(kind: String) {
        val now = SystemClock.elapsedRealtime()
        stallRestarts.addLast(now)
        while (stallRestarts.isNotEmpty() && now - stallRestarts.first() > STALL_WINDOW_MS) stallRestarts.removeFirst()
        val p = player
        // plenty of video loaded, not one frame shown: the decoder cannot play this stream, restarts do not help
        if (p != null && vm.archive == null && p.videoFormat != null &&
            (p.videoDecoderCounters?.renderedOutputBufferCount ?: 0) == 0 && p.totalBufferedDuration >= NO_PICTURE_BUFFER_MS
        ) {
            AppLog.w("no picture: ${p.totalBufferedDuration / 1000} s loaded, no frame decoded · ${statsLine(p)}")
            vm.channel?.let { vm.markNoPicture(it.name) }
            if (switchToBackup(T.reasonNoPicture)) return
        }
        AppLog.w("stalled ($kind) for ${STALL_MS / 1000} s, restart #${stallRestarts.size} · ${player?.let { statsLine(it) }}")
        if (stallRestarts.size >= STALLS_BEFORE_BACKUP && switchToBackup(T.reasonSlow)) return
        restart()
    }

    /** Re-opens the current stream: live jumps back to the live edge, archive continues where it was. */
    private fun restart() {
        val p = player ?: return
        AppLog.i("restart stream (${if (vm.archive == null) "live edge" else "archive, same position"})")
        // stop() drops the decoders too: a plain seek did not help when the picture never started (KHL, TV)
        val pos = p.currentPosition
        p.stop()
        if (vm.archive == null) p.seekToDefaultPosition() else p.seekTo(pos)
        p.prepare()
        p.play()
    }

    /** Live only: plays another variant of the same channel (e.g. HD instead of FHD). */
    private fun switchToBackup(reason: String): Boolean {
        if (vm.archive != null) return false
        val from = vm.channel ?: return false
        val to = vm.backupFor() ?: return false
        val name = vm.playlist?.channels?.getOrNull(to)?.name ?: return false
        AppLog.w("backup stream: \"${from.name}\" $reason -> \"$name\"")
        vm.notice = T.backup(from.name, reason, name)
        vm.playBackup(to)
        return true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm.selectAudio = ::chooseAudio
        audioCtl.onTracks = ::showAudioTracks
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // the splash picture of the theme covers the start; once Compose has drawn, the window goes black
        // so it never shows through behind the video
        window.decorView.post { window.setBackgroundDrawable(ColorDrawable(Color.BLACK)) }
        setContent {
            App(
                vm = vm,
                player = player,
                playerError = playerError,
                buffering = buffering,
                videoSizeTick = videoSizeTick,
                onPlay = ::playUrl,
            )
        }
    }

    override fun onStart() {
        super.onStart()
        AppLog.i("app in foreground")
        player = buildPlayer()
        handler.postDelayed(stats, 5_000)
        handler.postDelayed(frozenCheck, FROZEN_CHECK_MS)
        currentUrl = null // force re-prepare of the current stream
        vm.onForeground()
    }

    override fun onStop() {
        AppLog.i("app in background (player released)")
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stallCheck)
        handler.removeCallbacks(stats)
        handler.removeCallbacks(frozenCheck)
        handler.removeCallbacks(archiveEmptyCheck)
        player?.release()
        player = null
        super.onStop()
    }

    private fun playUrl(url: String) {
        val p = player ?: return
        val level = vm.bufferLevel
        if (url == currentUrl && level == currentLevel && p.playbackState != Player.STATE_IDLE) return
        currentUrl = url
        currentLevel = level
        // known not to show a picture on this TV: straight to the backup stream
        if (vm.archive == null && vm.channel?.let { vm.hasNoPicture(it.name) } == true) {
            handler.post { if (currentUrl == url && !switchToBackup(T.reasonNoPicture)) AppLog.i("no backup stream for \"${vm.channel?.name}\"") }
        }
        failures = 0
        stallRestarts.clear()
        frozenMs = 0
        lastFrames = -1
        jerkyFrom = 0
        handler.removeCallbacks(retry)
        handler.removeCallbacks(stallCheck)
        playerError = null
        p.stop() // drop the previous stream's surface/state before switching
        audioCtl.target = vm.channel?.let { vm.savedAudio(it.name) }?.ifEmpty { null }
        audioCtl.reset()
        audioStream = emptyList()
        vm.audioTracks = emptyList()
        // The provider serves the archive as a growing "live" playlist that starts at the requested
        // time; by default ExoPlayer would start ~30 s before its end, i.e. 30 s late. Start at the
        // beginning (1 ms, because a start position of 0 is treated as "default position").
        if (vm.archive != null) p.setMediaItem(mediaItem(url, level), 1L) else p.setMediaItem(mediaItem(url, level))
        alignArchiveTo = vm.archive?.startSec
        handler.removeCallbacks(archiveEmptyCheck)
        if (vm.archive != null) handler.postDelayed(archiveEmptyCheck, 4_000)
        everReady = false
        bufferingSince = 0
        val ch = vm.channel
        AppLog.i("play \"${ch?.name}\" #${ch?.number} [${ch?.group}] " +
            (vm.archive?.let { "ARCHIVE from ${hhmmss(it.startSec * 1000)}" } ?: "live") + " buffer level $level")
        p.prepare()
        p.play()
    }

    /**
     * Live streams are played further behind the live edge for a bigger buffer. The provider keeps
     * ~52-60 s of segments in a live playlist, so ~45 s is the most we can safely stay behind.
     */
    private fun mediaItem(url: String, level: Int): MediaItem {
        val target = when (level) { 1 -> 38_000L; 2 -> 45_000L; else -> 0L }
        if (target == 0L || vm.archive != null) return MediaItem.fromUri(url)
        return MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(
                MediaItem.LiveConfiguration.Builder()
                    .setTargetOffsetMs(target)
                    .setMinOffsetMs(target - 10_000)
                    .setMaxOffsetMs(target + 5_000)
                    .build()
            )
            .build()
    }

    private fun buildPlayer(): ExoPlayer {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Http.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        // Load as far ahead as the server allows: live is limited by the live edge (~30-50 s),
        // archive by the provider's sliding window (~90 s ahead).
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 60_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 2_500,
                /* bufferForPlaybackAfterRebufferMs = */ 5_000,
            )
            // The buffer lives on the Java heap (shared with the ~50 MB EPG): cap it in bytes too.
            // 64 MB ≈ 60 s of FHD or ≈ 25 s of UHD.
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(StreamSourceFactory(AudioPidDataSource.Factory(http, audioCtl)))
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                addAnalyticsListener(netListener)
                addListener(object : Player.Listener {
                    // Note: inside apply{} the ExoPlayer's own playerError shadows ours — qualify explicitly.
                    override fun onPlaybackStateChanged(state: Int) {
                        this@MainActivity.buffering = state == Player.STATE_BUFFERING
                        val now = SystemClock.elapsedRealtime()
                        if (state == Player.STATE_BUFFERING && bufferingSince == 0L) bufferingSince = now
                        if (state == Player.STATE_READY && bufferingSince > 0) {
                            val ms = now - bufferingSince
                            if (!everReady) AppLog.i("started in ${ms} ms")
                            else if (ms >= 500) AppLog.w("rebuffering ${ms} ms · buffer was empty")
                            bufferingSince = 0
                            everReady = true
                        }
                        handler.removeCallbacks(stallCheck)
                        if (state == Player.STATE_BUFFERING) handler.postDelayed(stallCheck, STALL_MS)
                        if (state == Player.STATE_READY) {
                            this@MainActivity.playerError = null
                            failures = 0
                        }
                    }

                    /**
                     * The provider's archive window can begin up to ~30 s before the requested time.
                     * Segment names are recording times (…/1793661299000.ts): jump to the requested one.
                     */
                    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                        val target = alignArchiveTo ?: return
                        val segments = (currentManifest as? HlsManifest)?.mediaPlaylist?.segments ?: return
                        alignArchiveTo = null
                        val seg = segments.firstOrNull { s ->
                            val ms = SEGMENT_TIME.find(s.url)?.groupValues?.get(1)?.toLongOrNull() ?: return@firstOrNull false
                            ms >= target * 1000 - 5_000
                        } ?: return
                        AppLog.i("archive: aligned to segment at ${seg.relativeStartTimeUs / 1_000_000} s of the window")
                        if (seg.relativeStartTimeUs > 1_000_000) seekTo(seg.relativeStartTimeUs / 1000)
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        videoSizeTick++
                        if (videoSize.width > 0) AppLog.i("video ${videoSize.width}x${videoSize.height}")
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        this@MainActivity.buffering = false
                        handler.removeCallbacks(retry)
                        handler.removeCallbacks(stallCheck)
                        // The server returns an empty / frozen playlist: retrying will not help.
                        val stuck = generateSequence<Throwable>(error) { it.cause }
                            .any { it is HlsPlaylistTracker.PlaylistStuckException }
                        failures++
                        val cause = generateSequence(error.cause) { it.cause }.lastOrNull()
                        AppLog.w("player error ${error.errorCodeName} (#$failures, stuck=$stuck) " +
                            "${error.cause?.javaClass?.simpleName}: ${error.cause?.message?.take(200)}" +
                            (cause?.takeIf { it !== error.cause }?.let { " / root ${it.javaClass.simpleName}: ${it.message?.take(200)}" } ?: ""))
                        if (stuck || failures > RETRY_DELAYS_S.size) {
                            if (switchToBackup(if (stuck) T.reasonStuck else T.reasonDown)) return
                            this@MainActivity.playerError = T.streamUnavailable(error.errorCodeName)
                        } else {
                            val delayS = RETRY_DELAYS_S[failures - 1]
                            this@MainActivity.playerError =
                                T.playbackError(error.errorCodeName, failures, RETRY_DELAYS_S.size, delayS)
                            handler.postDelayed(retry, delayS * 1000L)
                        }
                    }
                })
            }
    }

    companion object {
        /** Buffering longer than this counts as a stall and restarts the stream. */
        private const val STALL_MS = 10_000L
        private const val STALL_WINDOW_MS = 120_000L
        private const val FROZEN_CHECK_MS = 2_000L
        private val SEGMENT_TIME = Regex("""/(\d{13})\.ts""")
        /** Recording time from segment names is trusted only up to this much behind the live edge. */
        private const val MAX_ENCODER_DELAY_S = 90L
        /** This many stall restarts within STALL_WINDOW_MS -> switch to a backup stream. */
        private const val STALLS_BEFORE_BACKUP = 3
        /** Buffered video with no frame decoded at a stall: the TV cannot decode the stream. */
        private const val NO_PICTURE_BUFFER_MS = 15_000L
        /** A quarter or more of the frames dropped over this window (and at least the minimum) = jerky. */
        private const val JERKY_WINDOW_MS = 10_000L
        private const val JERKY_MIN_DROPPED = 50
        /** Pause before each retry after a playback error, seconds. */
        private val RETRY_DELAYS_S = intArrayOf(3, 5, 10, 15, 30, 30)
    }
}
