package com.zmeuk.zmiptv

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Times are epoch milliseconds. */
data class Programme(val start: Long, val stop: Long, val title: String, val desc: String?)

class Epg(
    internal val programmes: Map<String, List<Programme>>,
    /** playlist channel name -> XMLTV channel id */
    internal val nameToId: Map<String, String>,
    internal val icons: Map<String, String>,
) {
    val matchedChannels get() = nameToId.size

    fun programmes(ch: Channel): List<Programme> = nameToId[ch.name]?.let { programmes[it] }.orEmpty()

    fun icon(ch: Channel): String? = nameToId[ch.name]?.let { icons[it] }

    /** Programme running at time [t]. */
    fun at(ch: Channel, t: Long): Programme? {
        val list = programmes(ch)
        var lo = 0
        var hi = list.lastIndex
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].start <= t) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return list.getOrNull(found)?.takeIf { t < it.stop }
    }

    fun indexAt(ch: Channel, t: Long): Int {
        val list = programmes(ch)
        return list.indexOfFirst { t < it.stop }.let { if (it < 0) list.lastIndex else it }
    }

    fun nowNext(ch: Channel, t: Long = System.currentTimeMillis()): Pair<Programme?, Programme?> {
        val list = programmes(ch)
        val i = list.indexOfFirst { t < it.stop }
        if (i < 0) return null to null
        val cur = list[i].takeIf { it.start <= t }
        val next = if (cur != null) list.getOrNull(i + 1) else list[i]
        return cur to next
    }
}

/**
 * Streaming XMLTV parser. Matches playlist channels by tvg-id, exact display-name or a
 * normalised name ("Первый канал HD" ≈ "Первый канал"), keeps icons and a time window:
 * archive depth in the past (min 3 h) and 2 days ahead.
 */
object EpgParser {
    private const val HOUR = 3600_000L
    private const val DAY = 24 * HOUR

    fun normalize(name: String): String =
        name.lowercase()
            .replace('ё', 'е')
            .replace(Regex("""\b(uhd|fhd|hd|sd|4k|8k|hevc|orig|original)\b"""), " ")
            .filter { it.isLetterOrDigit() || it == '+' }

    fun parse(input: InputStream, channels: List<Channel>, cancelled: () -> Boolean = { false }): Epg {
        val now = System.currentTimeMillis()
        val ahead = now + 2 * DAY

        // XMLTV channel catalogue collected before the first <programme>
        val xmlIds = HashSet<String>()
        val exact = HashMap<String, String>()     // lowercase display-name -> id
        val normal = HashMap<String, String>()    // normalised display-name -> id
        val icons = HashMap<String, String>()

        var nameToId: Map<String, String> = emptyMap()
        var keepFrom: Map<String, Long> = emptyMap()
        var finalized = false

        fun finalizeMapping() {
            val map = HashMap<String, String>()
            for (ch in channels) {
                val id = ch.tvgId?.takeIf { it in xmlIds }
                    ?: exact[ch.name.lowercase().trim()]
                    ?: normal[normalize(ch.name)]
                if (id != null) map[ch.name] = id
            }
            val from = HashMap<String, Long>()
            for (ch in channels) {
                val id = map[ch.name] ?: continue
                val past = maxOf(3 * HOUR, ch.catchupDays.coerceAtMost(14) * DAY + 3 * HOUR)
                val f = now - past
                from[id] = minOf(from[id] ?: Long.MAX_VALUE, f)
            }
            nameToId = map
            keepFrom = from
            finalized = true
        }

        val programmes = HashMap<String, MutableList<Programme>>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(Http.maybeGunzip(input), null)

        var chId: String? = null
        var progChannel: String? = null
        var progStart = 0L
        var progStop = 0L
        var title: String? = null
        var desc: String? = null

        var events = 0
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "channel" -> {
                        chId = p.getAttributeValue(null, "id")
                        chId?.let { xmlIds += it }
                    }
                    "display-name" -> {
                        val id = chId
                        if (id != null) {
                            val dn = p.nextText().trim()
                            exact.putIfAbsent(dn.lowercase(), id)
                            normal.putIfAbsent(normalize(dn), id)
                        }
                    }
                    "icon" -> {
                        val id = chId
                        val src = p.getAttributeValue(null, "src")
                        if (id != null && progChannel == null && !src.isNullOrBlank()) icons.putIfAbsent(id, src)
                    }
                    "programme" -> {
                        if (!finalized) finalizeMapping()
                        val c = p.getAttributeValue(null, "channel")
                        val from = c?.let { keepFrom[it] }
                        if (from != null) {
                            progStart = parseTime(p.getAttributeValue(null, "start"))
                            progStop = parseTime(p.getAttributeValue(null, "stop"))
                            progChannel = if (progStop > from && progStart in 1 until ahead) c else null
                        } else {
                            progChannel = null
                        }
                        title = null
                        desc = null
                    }
                    "title" -> if (progChannel != null && title == null) title = p.nextText()
                    "desc" -> if (progChannel != null && desc == null) desc = p.nextText()
                }
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "channel" -> chId = null
                    "programme" -> {
                        val c = progChannel
                        if (c != null) {
                            programmes.getOrPut(c) { ArrayList() } +=
                                Programme(progStart, progStop.coerceAtLeast(progStart + 60_000), title.orEmpty(), desc?.trim())
                        }
                        progChannel = null
                    }
                }
            }
            if (++events % 5000 == 0 && cancelled()) throw java.util.concurrent.CancellationException("EPG parse cancelled")
            ev = p.next()
        }
        if (!finalized) finalizeMapping()
        programmes.values.forEach { list -> list.sortBy { it.start } }
        val usedIcons = icons.filterKeys { it in nameToId.values.toHashSet() }
        return Epg(programmes, nameToId, usedIcons)
    }

    private val fmtUtc = ThreadLocal.withInitial {
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    }

    /** "20261103183000 +0300" -> epoch ms. Hand-written: SimpleDateFormat made the 50 MB guide parse twice as slow. */
    internal fun parseTime(s: String?): Long {
        if (s.isNullOrBlank()) return 0
        val t = s.trim()
        if (t.length >= 14 && (0 until 14).all { t[it].isDigit() }) {
            fun num(from: Int, len: Int): Int { var v = 0; for (i in from until from + len) v = v * 10 + (t[i] - '0'); return v }
            val utc = daysFromCivil(num(0, 4), num(4, 2), num(6, 2)) * 86_400_000L +
                num(8, 2) * 3_600_000L + num(10, 2) * 60_000L + num(12, 2) * 1000L
            val z = t.substring(14).trim()
            if (z.isEmpty()) return utc
            if (z.length == 5 && (z[0] == '+' || z[0] == '-') && (1..4).all { z[it].isDigit() }) {
                val off = ((z[1] - '0') * 10 + (z[2] - '0')) * 3_600_000L + ((z[3] - '0') * 10 + (z[4] - '0')) * 60_000L
                return if (z[0] == '+') utc - off else utc + off
            }
        }
        return runCatching { fmtUtc.get()!!.parse(t.take(14))!!.time }.getOrDefault(0)
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (H. Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468
    }
}

/**
 * The parsed guide saved in a compact binary file: reading it takes seconds, parsing the 50 MB XMLTV
 * on the TV takes ~3 minutes. Valid for one downloaded guide and one channel list ([key]).
 */
object EpgSnapshot {
    private const val MAGIC = 0x5A4D4550 // "ZMEP"
    private const val VERSION = 1
    private const val MAX_TEXT = 16_000 // writeUTF is limited to 64 KB of UTF-8

    fun key(url: String, downloadedAt: Long, channels: List<Channel>): String =
        "$url|$downloadedAt|" + channels.joinToString("\n") { "${it.name}\t${it.tvgId}\t${it.catchupDays}" }.hashCode()

    fun write(file: File, key: String, epg: Epg) {
        val tmp = File(file.path + ".tmp")
        DataOutputStream(BufferedOutputStream(FileOutputStream(tmp), 1 shl 16)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeUTF(key)
            out.writeInt(epg.nameToId.size)
            for ((name, id) in epg.nameToId) { out.writeUTF(name); out.writeUTF(id) }
            out.writeInt(epg.icons.size)
            for ((id, url) in epg.icons) { out.writeUTF(id); out.writeUTF(url) }
            out.writeInt(epg.programmes.size)
            for ((id, list) in epg.programmes) {
                out.writeUTF(id)
                out.writeInt(list.size)
                for (pr in list) {
                    out.writeLong(pr.start)
                    out.writeInt(((pr.stop - pr.start) / 1000).toInt())
                    out.writeUTF(pr.title.take(MAX_TEXT))
                    out.writeUTF(pr.desc?.take(MAX_TEXT) ?: "")
                }
            }
        }
        if (!tmp.renameTo(file)) tmp.delete()
    }

    /** The saved guide, or null if there is none for [key] (another download or channel list). */
    fun read(file: File, key: String): Epg? {
        if (!file.exists()) return null
        return runCatching {
            DataInputStream(BufferedInputStream(FileInputStream(file), 1 shl 16)).use { inp ->
                if (inp.readInt() != MAGIC || inp.readInt() != VERSION || inp.readUTF() != key) return null
                val nameToId = HashMap<String, String>()
                repeat(inp.readInt()) { nameToId[inp.readUTF()] = inp.readUTF() }
                val icons = HashMap<String, String>()
                repeat(inp.readInt()) { icons[inp.readUTF()] = inp.readUTF() }
                val programmes = HashMap<String, List<Programme>>()
                repeat(inp.readInt()) {
                    val id = inp.readUTF()
                    val n = inp.readInt()
                    val list = ArrayList<Programme>(n)
                    repeat(n) {
                        val start = inp.readLong()
                        val stop = start + inp.readInt() * 1000L
                        val title = inp.readUTF()
                        val desc = inp.readUTF().ifEmpty { null }
                        list += Programme(start, stop, title, desc)
                    }
                    programmes[id] = list
                }
                Epg(programmes, nameToId, icons)
            }
        }.onFailure { AppLog.w("EPG: saved guide unreadable: ${it.javaClass.simpleName}: ${it.message}") }.getOrNull()
    }
}
