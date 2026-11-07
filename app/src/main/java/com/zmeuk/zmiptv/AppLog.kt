package com.zmeuk.zmiptv

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * App journal: everything goes to logcat (tag ZmIPTV) and to a file on the device, so it can be
 * read after the fact (scripts/pull-logs.sh):
 *   /sdcard/Android/data/com.zmeuk.zmiptv/files/logs/zmiptv.log  (+ zmiptv.1.log, zmiptv.2.log)
 * Up to 3 files of 1 MB, the oldest is dropped. Stream URLs are never logged (they contain the account key).
 */
object AppLog {
    private const val TAG = "ZmIPTV"
    private const val MAX_BYTES = 1_000_000L
    private const val FILES = 3

    @Volatile private var dir: File? = null
    private val lock = Any()
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "AppLog").apply { isDaemon = true } }
    private val time = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    fun init(context: Context) {
        dir = (context.getExternalFilesDir("logs") ?: File(context.filesDir, "logs")).apply { mkdirs() }
    }

    val file: File? get() = dir?.let { File(it, "zmiptv.log") }

    /** Size of all log files, bytes. */
    fun totalBytes(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0

    fun i(msg: String) {
        val m = scrub(msg)
        Log.i(TAG, m)
        enqueue("I", m, null)
    }

    fun w(msg: String, t: Throwable? = null) {
        val m = scrub(msg)
        Log.w(TAG, m, t)
        enqueue("W", m, t)
    }

    fun e(msg: String, t: Throwable? = null) {
        val m = scrub(msg)
        Log.e(TAG, m, t)
        enqueue("E", m, t)
    }

    /** Called from the crash handler: written synchronously, the process is about to die. */
    fun crash(thread: Thread, t: Throwable) {
        Log.e(TAG, "CRASH in ${thread.name}", t)
        append(format("E", "CRASH in thread ${thread.name}", t))
    }

    private val URL = Regex("""(https?://[^/\s"'<>]+)[^\s"'<>]*""")

    /**
     * URLs are cut down to the host: the playlist and stream links carry the account key, and exception
     * messages (FileNotFoundException, UnknownHostException…) quote the full URL.
     */
    fun scrub(s: String): String = URL.replace(s) { "${it.groupValues[1]}/…" }

    private fun enqueue(level: String, msg: String, t: Throwable?) {
        val line = format(level, msg, t)
        runCatching { writer.execute { append(line) } }
    }

    private fun format(level: String, msg: String, t: Throwable?): String {
        val head = "${time.get()!!.format(Date())} $level $msg"
        return if (t == null) head else head + "\n" + scrub(t.stackTraceToString().trimEnd())
    }

    private fun append(line: String) = synchronized(lock) {
        val f = file ?: return@synchronized
        runCatching {
            if (f.length() > MAX_BYTES) rotate()
            f.appendText(line + "\n")
        }
    }

    private fun rotate() {
        val d = dir ?: return
        File(d, "zmiptv.${FILES - 1}.log").delete()
        for (i in FILES - 2 downTo 1) File(d, "zmiptv.$i.log").renameTo(File(d, "zmiptv.${i + 1}.log"))
        File(d, "zmiptv.log").renameTo(File(d, "zmiptv.1.log"))
    }
}
