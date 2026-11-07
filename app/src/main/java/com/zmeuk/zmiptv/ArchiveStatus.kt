package com.zmeuk.zmiptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.HttpURLConnection
import java.net.URL

/**
 * Channels whose archive is declared in the playlist (tvg-rec) but the server has no recordings for.
 * They are shown and handled as channels without archive (no ⟲, no rewind), so nobody waits for nothing.
 *
 * Filled by a background check of all archive channels once a day and by a re-check after a failed rewind.
 * Entries expire after a day, so a repaired archive comes back by itself.
 */
object ArchiveStatus {
    const val TTL_MS = 24 * 3600_000L

    /** channel name -> when it was found without recordings (ms). A Compose state: the UI follows it. */
    var dead by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    private var prefs: Prefs? = null

    fun init(p: Prefs) {
        prefs = p
        val now = System.currentTimeMillis()
        dead = p.deadArchives.filterValues { now - it < TTL_MS }
    }

    fun isDead(name: String): Boolean = dead.containsKey(name)

    fun mark(name: String, noRecordings: Boolean) {
        val next = if (noRecordings) dead + (name to System.currentTimeMillis()) else dead - name
        if (next != dead) save(next)
    }

    /** Applies a full scan: [checked] names with their result; channels that could not be checked keep their state. */
    fun applyScan(checked: Map<String, Boolean>) {
        val now = System.currentTimeMillis()
        val next = dead.toMutableMap()
        for ((name, noRecordings) in checked) if (noRecordings) next[name] = now else next.remove(name)
        save(next)
    }

    private fun save(map: Map<String, Long>) {
        dead = map
        prefs?.deadArchives = map
    }

    /**
     * Does the server have recordings for [ch]? Asks two hours back and, if that is empty, a day back
     * (on ~4% of channels only older recordings exist). true / false, null = could not check.
     */
    fun probe(ch: Channel): Boolean? {
        val recent = probeAt(ch, 2 * 3600L)
        if (recent != false || ch.catchupDays < 2) return recent
        return probeAt(ch, 26 * 3600L)
    }

    private fun probeAt(ch: Channel, backSec: Long): Boolean? {
        val now = System.currentTimeMillis() / 1000
        val url = Catchup.url(ch, now - backSec, backSec, now)
        return runCatching {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Http.USER_AGENT)
            }
            try {
                if (conn.responseCode !in 200..299) return@runCatching null
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                body.contains(".ts") || body.contains("#EXTINF")
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
