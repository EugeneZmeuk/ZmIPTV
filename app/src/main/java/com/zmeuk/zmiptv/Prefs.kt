package com.zmeuk.zmiptv

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("zmiptv", Context.MODE_PRIVATE)

    var playlistUrl: String
        get() = sp.getString("playlistUrl", null) ?: BuildConfig.PLAYLIST_URL
        set(v) = sp.edit().putString("playlistUrl", v.trim()).apply()

    /** Empty = take EPG url from the playlist header. */
    var epgUrl: String
        get() = sp.getString("epgUrl", null) ?: BuildConfig.EPG_URL
        set(v) = sp.edit().putString("epgUrl", v.trim()).apply()

    /** UI language code (en, ru, pl, be, uk); English by default. */
    var lang: String
        get() = sp.getString("lang", null) ?: "en"
        set(v) = sp.edit().putString("lang", v).apply()

    var lastChannelName: String?
        get() = sp.getString("lastChannelName", null)
        set(v) = sp.edit().putString("lastChannelName", v).apply()

    /** Group (or GROUP_FAV / GROUP_ALL) the user zaps within: ▲▼ and channel numbers are relative to it. */
    var lastGroup: String?
        get() = sp.getString("lastGroup", null)
        set(v) = sp.edit().putString("lastGroup", v).apply()

    /** Favourite channel names in the user's order (older versions kept an unordered set). */
    var favorites: List<String>
        get() = sp.getString("favoritesOrdered", null)?.split('\n')?.filter { it.isNotEmpty() }
            ?: sp.getStringSet("favorites", emptySet())!!.toList()
        set(v) = sp.edit().putString("favoritesOrdered", v.joinToString("\n")).apply()

    val hasOrderedFavorites: Boolean get() = sp.contains("favoritesOrdered")

    /** false: ▲ = previous channel (lower number); true: ▲ = next channel. */
    var invertUpDown: Boolean
        get() = sp.getBoolean("invertUpDown", false)
        set(v) = sp.edit().putBoolean("invertUpDown", v).apply()

    /** Live buffer: 0 = normal (player default, ~30 s behind live), 1 = larger (~38 s), 2 = maximum (~45 s). */
    var bufferLevel: Int
        get() = sp.getInt("bufferLevel", 0).coerceIn(0, 2)
        set(v) = sp.edit().putInt("bufferLevel", v.coerceIn(0, 2)).apply()

    /**
     * Automatic switch to another stream of the same channel:
     * 0 = lower quality allowed (FHD -> HD, default), 1 = same quality or better only, 2 = off.
     * (0.5 kept a boolean "allowDowngrade": false there means mode 1.)
     */
    var backupMode: Int
        get() = when {
            sp.contains("backupMode") -> sp.getInt("backupMode", BACKUP_LOWER).coerceIn(0, 2)
            !sp.getBoolean("allowDowngrade", true) -> BACKUP_SAME_OR_BETTER
            else -> BACKUP_LOWER
        }
        set(v) = sp.edit().putInt("backupMode", v.coerceIn(0, 2)).remove("allowDowngrade").apply()

    companion object {
        const val BACKUP_LOWER = 0
        const val BACKUP_SAME_OR_BETTER = 1
        const val BACKUP_OFF = 2
    }

    /** Chosen audio track per channel name: "lang:en" or "idx:2" (see AudioTrackItem.key). */
    var audioChoices: Map<String, String>
        get() = sp.getString("audioChoices", null).orEmpty().lineSequence()
            .mapNotNull { line -> line.split('\u0001').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap()
        set(v) = sp.edit().putString("audioChoices", v.entries.joinToString("\n") { "${it.key}\u0001${it.value}" }).apply()

    /** Channels found without archive recordings: name -> when (ms). See ArchiveStatus. */
    var deadArchives: Map<String, Long>
        get() = sp.getString("deadArchives", null).orEmpty().lineSequence()
            .mapNotNull { line -> line.split('\u0001').takeIf { it.size == 2 }?.let { p -> p[1].toLongOrNull()?.let { p[0] to it } } }
            .toMap()
        set(v) = sp.edit().putString("deadArchives", v.entries.joinToString("\n") { "${it.key}\u0001${it.value}" }).apply()

    /** Channels whose picture never started on this TV: name -> when (ms). See TvViewModel.markNoPicture. */
    var noPicture: Map<String, Long>
        get() = sp.getString("noPicture", null).orEmpty().lineSequence()
            .mapNotNull { line -> line.split('\u0001').takeIf { it.size == 2 }?.let { p -> p[1].toLongOrNull()?.let { p[0] to it } } }
            .toMap()
        set(v) = sp.edit().putString("noPicture", v.entries.joinToString("\n") { "${it.key}\u0001${it.value}" }).apply()

    /** When all archive channels were last checked (ms). */
    var archiveScanTime: Long
        get() = sp.getLong("archiveScanTime", 0)
        set(v) = sp.edit().putLong("archiveScanTime", v).apply()

    var playlistCacheUrl: String?
        get() = sp.getString("playlistCacheUrl", null)
        set(v) = sp.edit().putString("playlistCacheUrl", v).apply()

    var epgCacheUrl: String?
        get() = sp.getString("epgCacheUrl", null)
        set(v) = sp.edit().putString("epgCacheUrl", v).apply()

    var epgCacheTime: Long
        get() = sp.getLong("epgCacheTime", 0)
        set(v) = sp.edit().putLong("epgCacheTime", v).apply()
}
