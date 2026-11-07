package com.zmeuk.zmiptv

import java.util.Calendar
import java.util.TimeZone

/**
 * Builds archive (catch-up) URLs.
 * Many providers use "shift": the live URL + ?utc=<start>&lutc=<now> (unix seconds).
 * Other common schemes (append/default templates, flussonic) are supported too.
 */
object Catchup {
    private val placeholder = Regex("""\$?\{([A-Za-z]+)(?::([^}]*))?\}""")

    fun url(ch: Channel, startSec: Long, durationSec: Long, nowSec: Long = System.currentTimeMillis() / 1000): String {
        val type = ch.catchupType?.lowercase()?.trim()
        val src = ch.catchupSource
        return when {
            type != null && type.startsWith("flussonic") || type == "fs" -> flussonic(ch.url, startSec, durationSec)
            type == "append" && src != null -> ch.url + fill(src, startSec, durationSec, nowSec)
            src != null && src.startsWith("http") -> fill(src, startSec, durationSec, nowSec)
            src != null && type == "default" -> ch.url + fill(src, startSec, durationSec, nowSec)
            else -> ch.url + (if ('?' in ch.url) "&" else "?") + "utc=$startSec&lutc=$nowSec"
        }
    }

    private fun fill(template: String, start: Long, duration: Long, now: Long): String =
        placeholder.replace(template) { m ->
            val key = m.groupValues[1]
            val fmt = m.groupValues[2]
            val t = when (key.lowercase()) {
                "utc", "start" -> start
                "utcend", "end" -> start + duration
                "lutc", "now", "timestamp", "current" -> now
                "duration" -> return@replace duration.toString()
                "offset" -> return@replace (now - start).toString()
                else -> null
            }
            when {
                t != null && fmt.isNotEmpty() -> format(t, fmt)
                t != null -> t.toString()
                key in listOf("Y", "m", "d", "H", "M", "S") -> format(start, key)
                else -> m.value
            }
        }

    /** Format like "Y-m-d H:M:S" (UTC). */
    private fun format(sec: Long, fmt: String): String {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = sec * 1000 }
        val sb = StringBuilder()
        for (ch in fmt) {
            sb.append(
                when (ch) {
                    'Y' -> "%04d".format(c.get(Calendar.YEAR))
                    'm' -> "%02d".format(c.get(Calendar.MONTH) + 1)
                    'd' -> "%02d".format(c.get(Calendar.DAY_OF_MONTH))
                    'H' -> "%02d".format(c.get(Calendar.HOUR_OF_DAY))
                    'M' -> "%02d".format(c.get(Calendar.MINUTE))
                    'S' -> "%02d".format(c.get(Calendar.SECOND))
                    else -> ch.toString()
                }
            )
        }
        return sb.toString()
    }

    private fun flussonic(url: String, start: Long, duration: Long): String {
        val q = url.indexOf('?')
        val path = if (q >= 0) url.substring(0, q) else url
        val query = if (q >= 0) url.substring(q) else ""
        val dir = path.substringBeforeLast('/')
        val file = path.substringAfterLast('/')
        val newFile = when {
            file == "mpegts" -> "timeshift_abs-$start.ts"
            file == "index.m3u8" -> "archive-$start-$duration.m3u8"
            file.endsWith(".m3u8") -> file.removeSuffix(".m3u8") + "-$start-$duration.m3u8"
            else -> "archive-$start-$duration.m3u8"
        }
        return "$dir/$newFile$query"
    }
}
