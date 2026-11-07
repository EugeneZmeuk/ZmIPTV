package com.zmeuk.zmiptv

import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

object Http {
    /** Host part only, for logs (full URLs carry the account key). */
    fun hostOf(url: String): String = runCatching { java.net.URI(url.trim()).host }.getOrNull().orEmpty()

    const val USER_AGENT = "ZmIPTV/0.3 (Android TV)"

    /** Opens a URL following redirects (including http↔https). Raw body, not un-gzipped. */
    private fun openRaw(url: String): InputStream {
        var current = url
        repeat(6) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: error("Redirect without Location")
                current = URL(URL(current), loc).toString()
                conn.disconnect()
                return@repeat
            }
            if (code !in 200..299) {
                conn.disconnect()
                error("HTTP $code")
            }
            return conn.inputStream
        }
        error("Too many redirects")
    }

    /** Body stream; transparently un-gzips if the payload is gzip. */
    fun open(url: String): InputStream = maybeGunzip(openRaw(url))

    fun getText(url: String): String = open(url).use { it.readBytes().toString(Charsets.UTF_8) }

    /** Downloads the raw body (still gzipped if it was) into [dest] atomically. */
    fun download(url: String, dest: File) {
        val tmp = File(dest.parentFile, dest.name + ".part")
        openRaw(url).use { input -> tmp.outputStream().use { input.copyTo(it, 64 * 1024) } }
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) error("Cannot save ${dest.name}")
    }

    fun maybeGunzip(raw: InputStream): InputStream {
        val input = BufferedInputStream(raw, 64 * 1024)
        input.mark(2)
        val b1 = input.read()
        val b2 = input.read()
        input.reset()
        return if (b1 == 0x1f && b2 == 0x8b) GZIPInputStream(input, 64 * 1024) else input
    }
}
