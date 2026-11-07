package com.zmeuk.zmiptv

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.HttpURLConnection
import java.net.URL

/**
 * Event channels ("Окко Event 1…28"): one per live match, a stream only while the match is on;
 * otherwise the server returns an empty playlist. Empty ones are checked every few minutes and
 * hidden from the channel lists, so nobody zaps through twenty black screens.
 */
object EventChannels {
    private val EVENT = Regex("""(?i)\bevent\s*\d+\s*$""")

    fun isEvent(ch: Channel) = EVENT.containsMatchIn(ch.name)

    /** Names of event channels without a broadcast right now. A Compose state: the lists follow it. */
    var idle by mutableStateOf<Set<String>>(emptySet())
        private set

    fun isIdle(ch: Channel) = ch.name in idle

    fun apply(checked: Map<String, Boolean>) {
        val next = idle.toMutableSet()
        for ((name, onAir) in checked) if (onAir) next -= name else next += name
        idle = next
    }

    /** true = on air, false = empty playlist, null = could not check (stays as it was). */
    fun probe(ch: Channel): Boolean? = runCatching {
        val conn = (URL(ch.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", Http.USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) return@runCatching null
            val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            when {
                body.contains("#EXTINF") || body.contains("#EXT-X-STREAM-INF") -> true
                body.contains("#EXTM3U") -> false
                else -> null
            }
        } finally {
            conn.disconnect()
        }
    }.getOrNull()
}
