package com.zmeuk.zmiptv

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import java.io.File

/** Archive playback that started at [startSec] (unix seconds) on channel index [channel]. */
data class ArchiveSession(val channel: Int, val startSec: Long)

class TvViewModel(private val app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)

    private val playlistFile = File(app.filesDir, "playlist.m3u8")
    private val epgFile = File(app.filesDir, "epg.xml.cache")
    /** The parsed guide (EpgSnapshot): read in seconds instead of parsing the XML for minutes. */
    private val epgSnapshotFile = File(app.filesDir, "epg.parsed")

    var playlist by mutableStateOf<Playlist?>(null); private set
    var epg by mutableStateOf<Epg?>(null); private set
    // Status lines are kept as text builders, so they follow a language change immediately.
    private var epgStatusFn by mutableStateOf<((Strings) -> String)?>(null)
    private var statusFn by mutableStateOf<((Strings) -> String)?>(null)
    val epgStatus: String? get() = epgStatusFn?.invoke(T)
    val status: String? get() = statusFn?.invoke(T)
    var lang by mutableStateOf(Lang.of(prefs.lang)); private set
    var loading by mutableStateOf(false); private set
    var showSetup by mutableStateOf(prefs.playlistUrl.isBlank())
    /** Grows when the URLs change from outside the settings fields, so the fields reload them. */
    var settingsRevision by mutableStateOf(0)
        private set
    var current by mutableIntStateOf(0); private set
    var archive by mutableStateOf<ArchiveSession?>(null); private set
    var favorites by mutableStateOf(prefs.favorites); private set
    var invertUpDown by mutableStateOf(prefs.invertUpDown); private set
    var bufferLevel by mutableIntStateOf(prefs.bufferLevel); private set
    var backupMode by mutableIntStateOf(prefs.backupMode); private set
    /**
     * How far (s) the live picture is behind real time. The player sits 30-50 s behind the live edge,
     * so "now" on screen is not the wall clock; pause and rewind in live are counted from this.
     */
    var liveDelaySec by mutableStateOf(0L)
    /** Audio tracks of the current stream (filled by the player). */
    var audioTracks by mutableStateOf<List<AudioTrackItem>>(emptyList())
    /** Set by the activity: switches the player to this audio track. */
    var selectAudio: (AudioTrackItem) -> Unit = {}
    private var audioChoices = prefs.audioChoices

    fun savedAudio(channelName: String): String? = audioChoices[channelName]

    fun saveAudio(channelName: String, key: String) {
        audioChoices = audioChoices + (channelName to key)
        prefs.audioChoices = audioChoices
    }

    /** One-shot message for the player screen (e.g. automatic switch to a backup stream). */
    var notice by mutableStateOf<String?>(null)
    /** The list the user zaps within (a provider group, GROUP_FAV or GROUP_ALL); null = current channel's group. */
    var contextGroup by mutableStateOf(prefs.lastGroup); private set

    private var previous = 0
    private var epgJob: Job? = null
    /** Channels already tried by the automatic backup-stream switch since the user last picked a channel. */
    private val fallbackTried = HashSet<Int>()
    /** normalised name ("онтby") -> indices of all variants of that channel (FHD, HD, orig…). */
    private var variants: Map<String, List<Int>>? = null
    private var byName: Map<String, Channel>? = null

    val channel: Channel? get() = playlist?.channels?.getOrNull(current)

    init {
        I18n.lang = lang
        ArchiveStatus.init(prefs)
        AppLog.i("settings: language ${lang.code}, buffer level $bufferLevel, backup mode $backupMode, " +
            "▲ inverted $invertUpDown, favourites ${favorites.size}, playlist ${Http.hostOf(prefs.playlistUrl)}")
        if (prefs.playlistUrl.isNotBlank()) {
            loadCachedPlaylist()
            load()
        }
    }

    // ------------------------------------------------------------ loading

    private fun loadCachedPlaylist() {
        if (!playlistFile.exists() || prefs.playlistCacheUrl != prefs.playlistUrl) return
        viewModelScope.launch {
            val pl = runCatching { withContext(Dispatchers.IO) { M3uParser.parse(playlistFile.readText()) } }.getOrNull()
            if (pl != null && pl.channels.isNotEmpty() && playlist == null) {
                AppLog.i("playlist: ${pl.channels.size} channels from the saved copy")
                applyPlaylist(pl)
                loadEpg(pl, force = false)
            }
        }
    }

    /** Fetches a fresh playlist from the network (keeps the cached one on failure). */
    fun load() {
        val url = prefs.playlistUrl
        if (url.isBlank()) { showSetup = true; return }
        viewModelScope.launch {
            loading = true
            statusFn = { it.loadingPlaylist }
            val t0 = System.currentTimeMillis()
            AppLog.i("playlist: loading from ${Http.hostOf(url)}")
            try {
                val text = withContext(Dispatchers.IO) { Http.getText(url) }
                val pl = withContext(Dispatchers.Default) { M3uParser.parse(text) }
                if (pl.channels.isEmpty()) error(T.playlistEmpty)
                withContext(Dispatchers.IO) {
                    playlistFile.writeText(text)
                    prefs.playlistCacheUrl = url
                }
                // The cached playlist may already be parsing the EPG: restart only if the channels changed.
                val sameChannels = playlist?.channels?.map { it.name } == pl.channels.map { it.name }
                AppLog.i("playlist: ${pl.channels.size} channels, ${pl.groups.size} groups in ${System.currentTimeMillis() - t0} ms")
                applyPlaylist(pl)
                statusFn = null
                showSetup = false
                if (!sameChannels || (epg == null && epgJob?.isActive != true)) loadEpg(pl, force = false)
                scheduleArchiveScan(pl)
                watchEventChannels(pl)
            } catch (e: Exception) {
                val err = e.message ?: e.javaClass.simpleName
                val cached = playlist != null
                AppLog.w("playlist: load failed (${if (cached) "using the saved one" else "nothing to show"}): ${e.javaClass.simpleName}: ${e.message}")
                statusFn = { s -> s.playlistFailed(err).let { if (cached) s.showingCached(it) else it } }
                if (playlist == null) showSetup = true
            } finally {
                loading = false
            }
        }
    }

    private fun applyPlaylist(pl: Playlist) {
        val keepName = channel?.name ?: prefs.lastChannelName
        playlist = pl
        variants = null
        byName = null
        fallbackTried.clear()
        if (!prefs.hasOrderedFavorites && favorites.isNotEmpty()) {
            // first run after the update: keep the old (unordered) favourites in playlist order
            val pos = pl.channels.withIndex().associate { it.value.name to it.index }
            favorites = favorites.sortedBy { pos[it] ?: Int.MAX_VALUE }
            prefs.favorites = favorites
        }
        current = pl.channels.indexOfFirst { it.name == keepName }.coerceAtLeast(0)
        previous = current
        archive = null
    }

    fun loadEpg(pl: Playlist? = playlist, force: Boolean) {
        pl ?: return
        val url = prefs.epgUrl.ifBlank { pl.epgUrls.firstOrNull().orEmpty() }
        if (url.isBlank()) {
            epgStatusFn = { it.epgNoUrl }
            return
        }
        epgJob?.cancel()
        epgJob = viewModelScope.launch {
            val t0 = System.currentTimeMillis()
            val cacheValid = epgFile.exists() && prefs.epgCacheUrl == url
            val stale = force || !cacheValid || System.currentTimeMillis() - prefs.epgCacheTime > EPG_MAX_AGE
            // the guide parsed last time: on screen in seconds; the XML is parsed again only for a new download
            if (!force && cacheValid && epg == null) {
                val saved = withContext(Dispatchers.IO) {
                    EpgSnapshot.read(epgSnapshotFile, EpgSnapshot.key(url, prefs.epgCacheTime, pl.channels))
                }
                if (saved != null) {
                    epg = saved
                    epgStatusFn = { it.epgLoaded(saved.matchedChannels, pl.channels.size) }
                    AppLog.i("EPG: ${saved.matchedChannels} of ${pl.channels.size} channels from the saved guide in " +
                        "${System.currentTimeMillis() - t0} ms · ${ZmApp.memory()}")
                    if (!stale) return@launch
                }
            }
            if (epg == null) epgStatusFn = { it.epgLoading }
            AppLog.i("EPG: loading from ${Http.hostOf(url)}")
            try {
                val result = withContext(Dispatchers.IO) {
                    if (stale) {
                        try {
                            Http.download(url, epgFile)
                            prefs.epgCacheUrl = url
                            prefs.epgCacheTime = System.currentTimeMillis()
                        } catch (e: Exception) {
                            if (!cacheValid) throw e
                            AppLog.w("EPG: download failed, using the saved copy: ${e.javaClass.simpleName}: ${e.message}")
                        }
                    }
                    val job = coroutineContext[Job]
                    epgFile.inputStream().use { EpgParser.parse(it, pl.channels) { job?.isActive == false } }.also { parsed ->
                        runCatching { EpgSnapshot.write(epgSnapshotFile, EpgSnapshot.key(url, prefs.epgCacheTime, pl.channels), parsed) }
                            .onFailure { AppLog.w("EPG: could not save the parsed guide: ${it.message}") }
                    }
                }
                epg = result
                AppLog.i("EPG: ${result.matchedChannels} of ${pl.channels.size} channels in ${(System.currentTimeMillis() - t0) / 1000} s · ${ZmApp.memory()}")
                epgStatusFn = { it.epgLoaded(result.matchedChannels, pl.channels.size) }
            } catch (e: CancellationException) {
                throw e // replaced by a newer load: do not report it as an error
            } catch (e: Exception) {
                AppLog.w("EPG: load failed", e)
                val err = e.message ?: e.javaClass.simpleName
                epgStatusFn = { it.epgFailed(err) }
            }
        }
    }

    /** Called when the app comes to the foreground: refresh a stale EPG. */
    fun onForeground() {
        val pl = playlist ?: return
        if (System.currentTimeMillis() - prefs.epgCacheTime > EPG_MAX_AGE && epgJob?.isActive != true) {
            loadEpg(pl, force = false)
        }
    }

    // ------------------------------------------------------------ playback

    private fun listFor(group: String?): List<Channel>? {
        val pl = playlist ?: return null
        return when (group) {
            null -> null
            GROUP_FAV -> favoriteChannels()
            GROUP_ALL -> withoutIdleEvents(pl.channels)
            else -> pl.byGroup[group]?.let(::withoutIdleEvents)
        }
    }

    /** Group key whose list contains the current channel (falls back to the channel's own group). */
    fun effectiveContextGroup(): String {
        val ch = channel ?: return GROUP_ALL
        val list = listFor(contextGroup)
        return if (list != null && list.any { it.number == ch.number }) contextGroup!! else ch.group
    }

    fun contextList(): List<Channel> = listFor(effectiveContextGroup()).orEmpty()

    /** 1-based position of the current channel inside its context list. */
    fun positionInContext(): Int = contextList().indexOfFirst { it.number - 1 == current } + 1

    private fun setContext(group: String?) {
        if (group == null || group == GROUP_SEARCH) return
        contextGroup = group
        prefs.lastGroup = group
    }

    fun play(index: Int, group: String? = null) {
        fallbackTried.clear()
        switchTo(index, group)
    }

    private fun switchTo(index: Int, group: String?) {
        val pl = playlist ?: return
        val i = index.mod(pl.channels.size)
        if (i != current) previous = current
        current = i
        archive = null
        prefs.lastChannelName = pl.channels[i].name
        setContext(group)
    }

    fun step(delta: Int) {
        val list = contextList()
        if (list.isEmpty()) return
        val pos = list.indexOfFirst { it.number - 1 == current }.coerceAtLeast(0)
        play(list[(pos + delta).mod(list.size)].number - 1)
    }

    /** Channel number typed on the remote = position inside the current context list. */
    fun playNumber(n: Int): Boolean {
        val list = contextList()
        if (n !in 1..list.size) return false
        play(list[n - 1].number - 1)
        return true
    }

    fun back() = play(previous)

    /**
     * Another stream of the same channel to try when the current one stalls or dies:
     * same name without quality marks ("ОНТ FHD BY" -> "ОНТ HD BY"), lower quality first, then higher.
     */
    fun backupFor(index: Int = current): Int? {
        if (backupMode == Prefs.BACKUP_OFF) return null
        val pl = playlist ?: return null
        val ch = pl.channels.getOrNull(index) ?: return null
        val map = variants ?: pl.channels.indices
            .groupBy { EpgParser.normalize(pl.channels[it].name) }
            .also { variants = it }
        val rank = qualityRank(ch.name)
        return map[EpgParser.normalize(ch.name)].orEmpty()
            .filter { it != index && it !in fallbackTried && pl.channels[it].url != ch.url }
            .filter { backupMode == Prefs.BACKUP_LOWER || qualityRank(pl.channels[it].name) >= rank }
            .sortedWith(compareBy<Int> { qualityRank(pl.channels[it].name) > rank }
                .thenBy { val r = qualityRank(pl.channels[it].name); if (r > rank) r else -r })
            .firstOrNull()
    }

    /** Switches to a backup stream without forgetting which variants were already tried. */
    fun playBackup(index: Int) {
        fallbackTried.add(current)
        switchTo(index, null)
    }

    private fun qualityRank(name: String): Int {
        val n = name.lowercase()
        fun has(w: String) = Regex("""(^|[^\p{L}\p{N}])$w([^\p{L}\p{N}]|$)""").containsMatchIn(n)
        return when {
            has("uhd") || has("4k") || has("8k") -> 4
            has("fhd") -> 3
            has("hd") -> 2
            has("sd") -> 0
            else -> 1
        }
    }

    fun changeBufferLevel(v: Int) {
        bufferLevel = v.coerceIn(0, 2)
        AppLog.i("settings: live buffer level $bufferLevel")
        prefs.bufferLevel = bufferLevel
    }

    fun startArchive(index: Int, startSec: Long, group: String? = null) {
        setContext(group)
        val pl = playlist ?: return
        val ch = pl.channels.getOrNull(index) ?: return
        if (!ch.hasArchive) return
        val nowSec = System.currentTimeMillis() / 1000
        val minSec = nowSec - ch.catchupDays * 86_400L + 60
        if (index != current) {
            previous = current
            current = index
            prefs.lastChannelName = ch.name
        }
        archive = if (startSec >= nowSec - 15) null else ArchiveSession(index, startSec.coerceAtLeast(minSec))
    }

    fun stopArchive() { archive = null }

    // ------------------------------------------------------------ archive check

    private var scanJob: Job? = null

    /** Once a day: ask the server for every archive channel; those with nothing recorded lose ⟲ and rewind. */
    fun scheduleArchiveScan(pl: Playlist) {
        if (scanJob?.isActive == true) return
        if (System.currentTimeMillis() - prefs.archiveScanTime < ArchiveStatus.TTL_MS) return
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            delay(90_000) // after start-up and the TV guide, not competing with them
            val list = pl.channels.filter { it.catchupDays > 0 }.distinctBy { it.name }
            val t0 = System.currentTimeMillis()
            AppLog.i("archive check: ${list.size} channels with declared archive")
            val gate = Semaphore(ARCHIVE_SCAN_PARALLEL)
            val results = list.map { ch ->
                async { gate.withPermit { ch.name to ArchiveStatus.probe(ch) } }
            }.awaitAll()
            val checked = results.mapNotNull { (name, ok) -> ok?.let { name to !it } }.toMap()
            withContext(Dispatchers.Main) { ArchiveStatus.applyScan(checked) }
            prefs.archiveScanTime = System.currentTimeMillis()
            val empty = checked.filterValues { it }.keys
            AppLog.i("archive check: ${checked.size - empty.size} with recordings, ${empty.size} empty, " +
                "${results.size - checked.size} not checked (errors), in ${(System.currentTimeMillis() - t0) / 1000} s")
            if (empty.isNotEmpty()) AppLog.i("archive check: empty: " + empty.sorted().joinToString(", "))
        }
    }

    private var eventJob: Job? = null

    /** Checks the event channels (EventChannels) now and every EVENT_CHECK_MS while the playlist is the same. */
    private fun watchEventChannels(pl: Playlist) {
        eventJob?.cancel()
        val list = pl.channels.filter { EventChannels.isEvent(it) }.distinctBy { it.name }
        if (list.isEmpty()) return
        eventJob = viewModelScope.launch(Dispatchers.IO) {
            var last = -1
            while (true) {
                val gate = Semaphore(ARCHIVE_SCAN_PARALLEL)
                val checked = list.map { ch -> async { gate.withPermit { ch.name to EventChannels.probe(ch) } } }
                    .awaitAll().mapNotNull { (name, onAir) -> onAir?.let { name to it } }.toMap()
                withContext(Dispatchers.Main) { EventChannels.apply(checked) }
                val onAir = checked.count { it.value }
                if (onAir != last) AppLog.i("event channels: $onAir of ${list.size} on air, ${checked.size - onAir} empty (hidden)")
                last = onAir
                delay(EVENT_CHECK_MS)
            }
        }
    }

    /** Lists without the event channels that have nothing on air (the playing one stays). */
    fun withoutIdleEvents(list: List<Channel>): List<Channel> {
        if (EventChannels.idle.isEmpty()) return list
        return list.filter { !EventChannels.isIdle(it) || it.number - 1 == current }
    }

    /** After a rewind found nothing: check the channel two hours back; empty there too = no archive for a day. */
    fun recheckArchive(ch: Channel) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = ArchiveStatus.probe(ch) ?: return@launch
            AppLog.i("archive check: \"${ch.name}\" ${if (ok) "has recordings (only the requested time is missing)" else "has no recordings -> marked without archive"}")
            withContext(Dispatchers.Main) { ArchiveStatus.mark(ch.name, noRecordings = !ok) }
        }
    }

    // ------------------------------------------------------------ channels without picture

    /**
     * Channels whose video never starts on this TV although the stream loads fine (Матч ТВ FHD,
     * KHL Prime HD orig: interlaced H.264 coded as separate fields, the MediaTek decoder outputs nothing).
     * Their backup stream is played right away; entries expire after a week in case the stream changes.
     */
    private var noPicture = prefs.noPicture.filterValues { System.currentTimeMillis() - it < NO_PICTURE_TTL_MS }

    fun hasNoPicture(name: String) = name in noPicture

    fun markNoPicture(name: String) {
        noPicture = noPicture + (name to System.currentTimeMillis())
        prefs.noPicture = noPicture
    }

    // ------------------------------------------------------------ favourites & settings

    fun isFavorite(ch: Channel) = ch.name in favorites

    fun toggleFavorite(ch: Channel) {
        favorites = if (ch.name in favorites) favorites - ch.name else favorites + ch.name
        prefs.favorites = favorites
    }

    /** Favourite channels in the user's order. */
    fun favoriteChannels(): List<Channel> {
        val pl = playlist ?: return emptyList()
        val map = byName ?: HashMap<String, Channel>().also { m ->
            pl.channels.forEach { m.putIfAbsent(it.name, it) }
            byName = m
        }
        return favorites.mapNotNull { map[it] }
    }

    /** Moves a favourite one step up (-1) or down (+1); returns false at the edge. */
    fun moveFavorite(ch: Channel, delta: Int): Boolean {
        val i = favorites.indexOf(ch.name)
        val j = i + delta
        if (i < 0 || j !in favorites.indices) return false
        favorites = favorites.toMutableList().apply { add(j, removeAt(i)) }
        prefs.favorites = favorites
        return true
    }

    fun nextBackupMode() {
        backupMode = (backupMode + 1) % 3
        prefs.backupMode = backupMode
        AppLog.i("settings: backup stream mode $backupMode")
    }

    fun changeLang(l: Lang) {
        AppLog.i("settings: language ${l.code}")
        lang = l
        prefs.lang = l.code
        I18n.lang = l
    }

    fun setInvert(v: Boolean) {
        invertUpDown = v
        prefs.invertUpDown = v
    }

    fun saveSettings(playlistUrl: String, epgUrl: String) {
        AppLog.i("settings: saved (playlist ${Http.hostOf(playlistUrl)}, EPG ${Http.hostOf(epgUrl).ifEmpty { "from playlist" }})")
        val epgChanged = epgUrl.trim() != prefs.epgUrl
        val plChanged = playlistUrl.trim() != prefs.playlistUrl
        prefs.playlistUrl = playlistUrl
        prefs.epgUrl = epgUrl
        if (plChanged) {
            load()
        } else {
            if (epgChanged) { epg = null; loadEpg(force = true) }
            if (playlist != null) showSetup = false
        }
    }

    /** URLs from the phone (QrInput.kt). An empty guide URL keeps the current one. */
    fun saveFromPhone(playlistUrl: String, epgUrl: String) {
        saveSettings(playlistUrl, epgUrl.ifBlank { prefs.epgUrl })
        settingsRevision++
    }

    fun reloadAll() {
        prefs.epgCacheTime = 0 // makes the EPG load triggered by load() re-download
        load()
    }

    companion object {
        private const val TAG = "ZmIPTV"
        private const val EPG_MAX_AGE = 12 * 3600_000L
        /** Gentle on the provider: ~1000 small requests in a few minutes, once a day. */
        private const val ARCHIVE_SCAN_PARALLEL = 4
        private const val EVENT_CHECK_MS = 10 * 60_000L
        private const val NO_PICTURE_TTL_MS = 7 * 24 * 3600_000L
    }
}
