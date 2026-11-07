@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package com.zmeuk.zmiptv

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.ByteArrayOutputStream

/**
 * Several audio tracks muxed into one HLS/TS stream (e.g. Eurosport: rus + eng, both AAC).
 *
 * ExoPlayer's HLS pipeline keeps only ONE audio track per stream type (TsExtractor in HLS mode keys
 * tracks by stream type and keeps the lowest PID), so the other languages are invisible to it.
 * Work-around: read the PAT/PMT of each segment ourselves (that gives the list for the menu) and, when
 * another track is wanted, swap its PID with the lowest one in the segment bytes before ExoPlayer sees them.
 */

/** One audio elementary stream from the PMT. [kind] is the stream type with AC3/E-AC3 descriptors resolved. */
data class TsAudio(val pid: Int, val kind: Int, val language: String?)

/** Shared between the data sources (loader threads) and the activity (main thread). */
class AudioPidControl {
    /** Wanted track: "lang:eng" or "idx:2" (see AudioTrackItem.key); null = provider default. */
    @Volatile var target: String? = null

    /** Called on the main thread when the list of audio tracks of the stream changes. */
    var onTracks: ((List<TsAudio>) -> Unit)? = null

    @Volatile private var last: List<TsAudio> = emptyList()
    private val main = Handler(Looper.getMainLooper())

    fun reset() { last = emptyList() }

    fun report(list: List<TsAudio>) {
        if (list.isEmpty() || list == last) return
        last = list
        main.post { onTracks?.invoke(list) }
    }

    /** The stream the target points at, or null for the default. */
    fun match(list: List<TsAudio>, key: String? = target): TsAudio? = when {
        key == null -> null
        key.startsWith("lang:") -> list.firstOrNull { it.language == key.removePrefix("lang:") }
        key.startsWith("idx:") -> key.removePrefix("idx:").toIntOrNull()?.let { list.getOrNull(it - 1) }
        else -> null
    }
}

object Ts {
    private const val PACKET = 188
    private val AUDIO_KINDS = setOf(0x03, 0x04, 0x0F, 0x11, 0x81, 0x87, 0xAC)

    /** Audio streams of the first program, from the PAT/PMT found in [data]; empty if none found. */
    fun parseAudio(data: ByteArray, size: Int): List<TsAudio> {
        val start = firstSync(data, size) ?: return emptyList()
        var pmtPid = -1
        var i = start
        while (i + PACKET <= size) {
            if (data[i] != 0x47.toByte()) { i++; continue }
            val pid = pidAt(data, i)
            val pusi = data[i + 1].toInt() and 0x40 != 0
            if (pusi) {
                if (pid == 0 && pmtPid < 0) pmtPid = parsePat(data, i)
                else if (pid == pmtPid) return parsePmt(data, i)
            }
            i += PACKET
        }
        return emptyList()
    }

    /** Swaps PIDs [a] and [b] in all packets (both streams change places, continuity counters stay consistent). */
    fun swapPids(data: ByteArray, size: Int, a: Int, b: Int) {
        var i = firstSync(data, size) ?: return
        while (i + PACKET <= size) {
            if (data[i] != 0x47.toByte()) { i++; continue }
            val pid = pidAt(data, i)
            val to = when (pid) { a -> b; b -> a; else -> -1 }
            if (to >= 0) {
                data[i + 1] = ((data[i + 1].toInt() and 0xE0) or ((to shr 8) and 0x1F)).toByte()
                data[i + 2] = (to and 0xFF).toByte()
            }
            i += PACKET
        }
    }

    private fun firstSync(d: ByteArray, size: Int): Int? {
        for (i in 0 until minOf(size, PACKET)) {
            if (d[i] == 0x47.toByte() && (i + PACKET >= size || d[i + PACKET] == 0x47.toByte())) return i
        }
        return null
    }

    private fun pidAt(d: ByteArray, i: Int) = ((d[i + 1].toInt() and 0x1F) shl 8) or (d[i + 2].toInt() and 0xFF)

    private fun u8(d: ByteArray, i: Int) = d[i].toInt() and 0xFF

    /** Offset of the PSI section start in the packet at [i] (after adaptation field and pointer field). */
    private fun sectionStart(d: ByteArray, i: Int): Int? {
        var p = i + 4
        if (u8(d, i + 3) and 0x20 != 0) p += 1 + u8(d, p) // adaptation field
        if (p >= i + PACKET) return null
        p += 1 + u8(d, p) // pointer field
        return p.takeIf { it + 3 <= i + PACKET }
    }

    private fun parsePat(d: ByteArray, i: Int): Int {
        val p = sectionStart(d, i) ?: return -1
        if (u8(d, p) != 0x00) return -1
        val len = ((u8(d, p + 1) and 0x0F) shl 8) or u8(d, p + 2)
        val end = minOf(p + 3 + len - 4, i + PACKET)
        var q = p + 8
        while (q + 4 <= end) {
            val program = (u8(d, q) shl 8) or u8(d, q + 1)
            val pid = ((u8(d, q + 2) and 0x1F) shl 8) or u8(d, q + 3)
            if (program != 0) return pid
            q += 4
        }
        return -1
    }

    private fun parsePmt(d: ByteArray, i: Int): List<TsAudio> {
        val p = sectionStart(d, i) ?: return emptyList()
        if (u8(d, p) != 0x02 || p + 12 > i + PACKET) return emptyList()
        val len = ((u8(d, p + 1) and 0x0F) shl 8) or u8(d, p + 2)
        val end = minOf(p + 3 + len - 4, i + PACKET)
        val progInfo = ((u8(d, p + 10) and 0x0F) shl 8) or u8(d, p + 11)
        var q = p + 12 + progInfo
        val out = ArrayList<TsAudio>()
        while (q + 5 <= end) {
            var kind = u8(d, q)
            val pid = ((u8(d, q + 1) and 0x1F) shl 8) or u8(d, q + 2)
            val esLen = ((u8(d, q + 3) and 0x0F) shl 8) or u8(d, q + 4)
            var lang: String? = null
            var r = q + 5
            val esEnd = minOf(q + 5 + esLen, end)
            while (r + 2 <= esEnd) {
                val tag = u8(d, r)
                val dl = u8(d, r + 1)
                if (r + 2 + dl > esEnd) break
                when (tag) {
                    0x0A -> if (dl >= 3) lang = String(d, r + 2, 3, Charsets.ISO_8859_1).lowercase().takeIf { it.all(Char::isLetter) && it != "und" }
                    0x6A -> if (kind == 0x06) kind = 0x81
                    0x7A -> if (kind == 0x06) kind = 0x87
                    0x05 -> if (kind == 0x06 && dl >= 4) {
                        when (String(d, r + 2, 4, Charsets.ISO_8859_1)) { "AC-3" -> kind = 0x81; "EAC3" -> kind = 0x87 }
                    }
                }
                r += 2 + dl
            }
            if (kind in AUDIO_KINDS) out += TsAudio(pid, kind, lang)
            q += 5 + esLen
        }
        return out
    }
}

/**
 * Wraps the HTTP data source. For .ts segments: always reads the PAT/PMT from the first bytes (track list);
 * when a non-default track is wanted, downloads the whole segment, swaps the PIDs and serves it from memory.
 */
class AudioPidDataSource(private val upstream: DataSource, private val ctl: AudioPidControl) : DataSource {
    private var mem: ByteArray? = null
    private var memPos = 0
    private var sniff: ByteArray? = null
    private var sniffLen = 0
    private var upstreamOpen = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val isTs = dataSpec.position == 0L && dataSpec.uri.lastPathSegment?.endsWith(".ts") == true
        mem = null
        sniff = null
        sniffLen = 0
        val len = upstream.open(dataSpec)
        upstreamOpen = true
        if (!isTs) return len
        if (ctl.target == null) {
            sniff = ByteArray(SNIFF_BYTES)
            return len
        }
        val out = ByteArrayOutputStream(if (len > 0) len.toInt() else 4 shl 20)
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = upstream.read(buf, 0, buf.size)
            if (n == C.RESULT_END_OF_INPUT) break
            out.write(buf, 0, n)
        }
        val data = out.toByteArray()
        val tracks = Ts.parseAudio(data, minOf(data.size, SNIFF_BYTES))
        ctl.report(tracks)
        val want = ctl.match(tracks)
        if (want != null) {
            val lowest = tracks.filter { it.kind == want.kind }.minByOrNull { it.pid }
            if (lowest != null && lowest.pid != want.pid) Ts.swapPids(data, data.size, want.pid, lowest.pid)
        }
        mem = data
        memPos = 0
        return data.size.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        mem?.let { m ->
            if (memPos >= m.size) return C.RESULT_END_OF_INPUT
            val n = minOf(length, m.size - memPos)
            System.arraycopy(m, memPos, buffer, offset, n)
            memPos += n
            return n
        }
        val n = upstream.read(buffer, offset, length)
        val s = sniff
        if (s != null && n > 0) {
            val k = minOf(n, s.size - sniffLen)
            System.arraycopy(buffer, offset, s, sniffLen, k)
            sniffLen += k
            if (sniffLen >= s.size) flushSniff()
        }
        return n
    }

    private fun flushSniff() {
        val s = sniff ?: return
        sniff = null
        ctl.report(Ts.parseAudio(s, sniffLen))
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (sniffLen > 0) flushSniff()
        mem = null
        if (upstreamOpen) {
            upstreamOpen = false
            upstream.close()
        }
    }

    class Factory(private val upstream: DataSource.Factory, private val ctl: AudioPidControl) : DataSource.Factory {
        override fun createDataSource(): DataSource = AudioPidDataSource(upstream.createDataSource(), ctl)
    }

    companion object {
        /** PAT + PMT are in the first packets of a segment. */
        private const val SNIFF_BYTES = 188 * 32
    }
}
