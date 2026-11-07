package com.zmeuk.zmiptv

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Texts of the page opened on the phone; plain values, so the server thread never touches Compose state. */
class InboxTexts(
    val lang: String,
    val title: String,
    val playlist: String,
    val epg: String,
    val send: String,
    val done: String,
    val bad: String,
)

/**
 * A tiny web server that lives only while the QR screen is open. It serves one form on a random
 * address and takes one submission: the phone never sees the current URLs, and a device on the
 * same network cannot post without the token from the QR code.
 */
class PlaylistInbox(
    private val host: String,
    private val texts: InboxTexts,
    private val onReceive: (playlistUrl: String, epgUrl: String) -> Unit,
) {
    private val token = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    private val socket = bind()
    private val pool: ExecutorService = Executors.newFixedThreadPool(MAX_CONNECTIONS)
    private val received = AtomicBoolean(false)

    val url: String = "http://$host:${socket.localPort}/$token"

    init {
        Thread({ acceptLoop() }, "PlaylistInbox").apply { isDaemon = true }.start()
    }

    fun stop() {
        runCatching { socket.close() }
        pool.shutdownNow()
    }

    private fun bind(): ServerSocket {
        val s = ServerSocket()
        s.reuseAddress = true
        try {
            s.bind(InetSocketAddress(PORT))
        } catch (e: IOException) {
            s.bind(InetSocketAddress(0)) // the usual port is taken: any free one, the QR code has it anyway
        }
        return s
    }

    private fun acceptLoop() {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                return
            }
            try {
                pool.execute { runCatching { handle(client) } }
            } catch (e: RuntimeException) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(client: Socket) = client.use { s ->
        s.soTimeout = READ_TIMEOUT_MS
        val input = BufferedInputStream(s.getInputStream())
        val head = readHead(input)
        val requestLine = head?.lineSequence()?.firstOrNull()?.split(' ')
        if (requestLine == null || requestLine.size < 2) return@use respond(s, 400, "Bad Request", text(texts.bad))
        val method = requestLine[0]
        val path = requestLine[1].substringBefore('?')
        if (!MessageDigest.isEqual(path.toByteArray(), "/$token".toByteArray())) {
            return@use respond(s, 404, "Not Found", text("404"))
        }
        when (method) {
            "GET" -> respond(s, 200, "OK", form(error = false))
            "POST" -> handlePost(s, input, head)
            else -> respond(s, 405, "Method Not Allowed", text("405"))
        }
    }

    private fun handlePost(s: Socket, input: InputStream, head: String) {
        val length = head.lineSequence()
            .firstOrNull { it.startsWith("content-length:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.toIntOrNull()
        if (length == null || length < 0) return respond(s, 400, "Bad Request", form(error = true))
        if (length > MAX_BODY) return respond(s, 413, "Payload Too Large", form(error = true))
        val body = String(input.readNBytesCompat(length), Charsets.UTF_8)
        val fields = runCatching { parseForm(body) }.getOrNull()
            ?: return respond(s, 400, "Bad Request", form(error = true))
        val playlist = fields["playlist"].orEmpty().trim()
        val epg = fields["epg"].orEmpty().trim()
        if (!isHttpUrl(playlist) || (epg.isNotEmpty() && !isHttpUrl(epg))) {
            return respond(s, 400, "Bad Request", form(error = true))
        }
        if (!received.compareAndSet(false, true)) return respond(s, 410, "Gone", text(texts.done))
        respond(s, 200, "OK", text(texts.done))
        onReceive(playlist, epg)
    }

    private fun readHead(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var tail = 0
        while (out.size() < MAX_HEAD) {
            val b = input.read()
            if (b < 0) return null
            out.write(b)
            tail = (tail shl 8 or b) and 0x7FFFFFFF
            if (tail == 0x0D0A0D0A) return out.toString("UTF-8")
        }
        return null
    }

    private fun InputStream.readNBytesCompat(n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        return buf.copyOf(read)
    }

    private fun respond(s: Socket, code: Int, reason: String, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        s.getOutputStream().apply {
            write(head.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    private fun form(error: Boolean): String {
        val message = if (error) "<p class=e>${esc(texts.bad)}</p>" else ""
        return page(
            """
            <h1>${esc(texts.title)}</h1>$message
            <form method="post" action="/$token" autocomplete="off">
            <label>${esc(texts.playlist)}<input type="url" name="playlist" required autofocus></label>
            <label>${esc(texts.epg)}<input type="url" name="epg"></label>
            <button type="submit">${esc(texts.send)}</button>
            </form>
            """.trimIndent(),
        )
    }

    private fun text(message: String) = page("<h1>${esc(message)}</h1>")

    private fun page(content: String) = """
        <!doctype html><html lang="${esc(texts.lang)}"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1"><title>ZmIPTV</title>
        <style>
        body{margin:0;padding:24px 16px;background:#0b0e14;color:#fff;font:17px/1.4 system-ui,sans-serif}
        h1{font-size:22px;margin:0 0 20px}.e{color:#ff9d9d}
        label{display:block;margin:0 0 18px;color:#aeb6c4;font-size:15px}
        input{display:block;box-sizing:border-box;width:100%;margin-top:6px;padding:12px;font-size:16px;
        color:#fff;background:#171c26;border:1px solid #3a4456;border-radius:8px}
        button{width:100%;padding:14px;font-size:17px;color:#fff;background:#2f6fed;border:0;border-radius:8px}
        </style></head><body>$content</body></html>
    """.trimIndent()

    companion object {
        private const val PORT = 8642
        private const val MAX_CONNECTIONS = 4
        private const val READ_TIMEOUT_MS = 5_000
        private const val MAX_HEAD = 8 * 1024
        private const val MAX_BODY = 16 * 1024
        private const val MAX_URL = 4096
        private val HTTP_URL = Regex("""^https?://[^\s/?#]+\S*$""", RegexOption.IGNORE_CASE)

        fun isHttpUrl(url: String) = url.length <= MAX_URL && HTTP_URL.matches(url)

        fun parseForm(body: String): Map<String, String> = body.split('&')
            .filter { it.isNotEmpty() }
            .associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to
                    URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
            .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

        /** The address a phone on the same network can reach; null when the TV has no local network. */
        fun localAddress(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("eth") || it.name.startsWith("wlan")) 0 else 1 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()
    }
}

private fun qrBitmap(text: String): Bitmap {
    val hints = mapOf(
        EncodeHintType.MARGIN to 4,
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix.get(i % matrix.width, i / matrix.width)) AndroidColor.BLACK else AndroidColor.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
}

/** Settings → enter the playlist from a phone: scan the code, paste the links, they arrive here. */
@Composable
fun QrInputScreen(vm: TvViewModel, onClose: () -> Unit) {
    val strings = T
    val focus = remember { FocusRequester() }
    var inbox by remember { mutableStateOf<PlaylistInbox?>(null) }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { focus.requestFocus() } }

    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val texts = InboxTexts(
            lang = vm.lang.code,
            title = strings.qrTitle,
            playlist = strings.qrPagePlaylist,
            epg = strings.qrPageEpg,
            send = strings.qrPageSend,
            done = strings.qrPageDone,
            bad = strings.qrPageBad,
        )
        val started = PlaylistInbox.localAddress()?.let { host ->
            runCatching {
                PlaylistInbox(host, texts) { playlist, epg ->
                    main.post {
                        vm.saveFromPhone(playlist, epg)
                        onClose()
                    }
                }
            }.onFailure { AppLog.w("qr input: server failed: ${it.javaClass.simpleName}") }.getOrNull()
        }
        inbox = started
        onDispose { started?.stop() }
    }

    val url = inbox?.url
    val qr = remember(url) { url?.let { qrBitmap(it).asImageBitmap() } }

    Row(
        Modifier.fillMaxSize().background(Color(0xFF0B0E14)).padding(48.dp)
            .focusRequester(focus).focusable(),
        horizontalArrangement = Arrangement.spacedBy(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (qr != null) {
            Image(
                bitmap = qr,
                contentDescription = null,
                filterQuality = FilterQuality.None,
                modifier = Modifier.size(340.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(strings.qrTitle, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            if (url != null) {
                Text(strings.qrSteps, color = Color.White, fontSize = 18.sp)
                Text(strings.qrManual, color = Dim, fontSize = 15.sp)
                Text(url, color = Color.White, fontSize = 15.sp)
            } else {
                Text(strings.qrNoNetwork, color = Color.White, fontSize = 18.sp)
            }
            Text(strings.qrFooter, color = Dim, fontSize = 15.sp)
        }
    }
}
