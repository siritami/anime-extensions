package eu.kanade.tachiyomi.animeextension.vi.nguonc.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class NguonCExtractor(private val client: OkHttpClient, private val headers: Headers) {

    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    @Volatile private var proxy: HlsProxyServer? = null

    @Volatile private var activeWebView: WebView? = null
    private val segFetcher = SegmentFetcher()

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromUrl(embedUrl: String): List<Video> {
        val latch = CountDownLatch(1)
        val bridge = JsBridge(latch)
        val bridgeName = generateBridgeName()
        val segBridgeName = generateBridgeName()
        val script = EXTRACT_SCRIPT_TEMPLATE.replace("__BRIDGE__", bridgeName)

        segFetcher.bridgeName = segBridgeName
        segFetcher.abort()

        handler.post {
            activeWebView?.let {
                it.stopLoading()
                it.destroy()
            }

            val wv = WebView(context)
            activeWebView = wv

            with(wv.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = headers["user-agent"]
            }

            wv.addJavascriptInterface(bridge, bridgeName)
            wv.addJavascriptInterface(segFetcher, segBridgeName)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    view?.evaluateJavascript(script, null)
                }
            }

            wv.loadUrl(embedUrl)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        val m3u8Content = bridge.m3u8Content
        if (m3u8Content == null) {
            handler.post {
                activeWebView?.stopLoading()
                activeWebView?.destroy()
                activeWebView = null
            }
            return emptyList()
        }

        val baseUrl = bridge.m3u8BaseUrl ?: ""
        val segmentUrls = mutableListOf<String>()
        val lines = m3u8Content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val url = when {
                    trimmed.startsWith("http") -> trimmed
                    trimmed.startsWith("/") -> {
                        val origin = baseUrl.split("/").take(3).joinToString("/")
                        "$origin$trimmed"
                    }
                    else -> "$baseUrl$trimmed"
                }
                segmentUrls.add(url)
            }
        }
        val server = ensureProxyRunning()
        server.reset()
        server.segmentUrls = segmentUrls

        var segIdx = 0
        val rewritten = buildString {
            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.equals("#EXT-X-DISCONTINUITY", ignoreCase = true)) continue
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    appendLine("http://127.0.0.1:${server.port}/seg/$segIdx.ts")
                    segIdx++
                } else {
                    appendLine(line)
                }
            }
            if (!this.contains("#EXT-X-ENDLIST")) appendLine("#EXT-X-ENDLIST")
        }
        server.cachedPlaylist = rewritten

        Thread {
            val bytes = fetchSegment(segmentUrls[0])
            if (bytes != null) {
                val result = if (bytes.size > PNG_HEADER_SIZE && isPng(bytes)) {
                    bytes.copyOfRange(PNG_HEADER_SIZE, bytes.size)
                } else {
                    bytes
                }
                server.segmentCache[0] = result
            }
        }.start()

        val proxyUrl = "http://127.0.0.1:${server.port}/playlist.m3u8"
        return listOf(Video(proxyUrl, "Video", proxyUrl))
    }

    fun fetchSegment(url: String): ByteArray? {
        val wv = activeWebView ?: return null
        val bridge = segFetcher.bridgeName ?: return null
        return segFetcher.fetch(url, bridge, wv, handler)
    }

    private fun generateBridgeName(): String {
        val pool = ('a'..'z') + ('A'..'Z')
        return (1..(10..20).random()).map { pool.random() }.joinToString("")
    }

    private fun isPng(bytes: ByteArray): Boolean = bytes.size > 4 &&
        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

    private fun ensureProxyRunning(): HlsProxyServer {
        proxy?.let { if (!it.isClosed) return it }
        val server = HlsProxyServer(this)
        server.start()
        proxy = server
        return server
    }

    class SegmentFetcher {
        private val lock = Any()

        @Volatile var bridgeName: String? = null

        @Volatile private var segmentData: ByteArray? = null

        @Volatile private var segmentError: String? = null

        @Volatile private var segmentLatch: CountDownLatch? = null

        @Volatile private var aborted = false

        fun abort() {
            aborted = true
            segmentLatch?.countDown()
        }

        @JavascriptInterface
        fun onSegment(base64: String) {
            segmentData = Base64.decode(base64, Base64.DEFAULT)
            segmentLatch?.countDown()
        }

        @JavascriptInterface
        fun onSegmentError(msg: String) {
            segmentError = msg
            segmentLatch?.countDown()
        }

        fun fetch(url: String, bridge: String, webView: WebView, handler: Handler): ByteArray? {
            synchronized(lock) {
                if (aborted) {
                    aborted = false
                    return null
                }
                segmentData = null
                segmentError = null
                val latch = CountDownLatch(1)
                segmentLatch = latch

                val escapedUrl = url.replace("\\", "\\\\").replace("'", "\\'")
                val script = """(function() {
                    fetch('$escapedUrl').then(function(r) {
                        if (!r.ok) { $bridge.onSegmentError('HTTP ' + r.status); return; }
                        return r.arrayBuffer();
                    }).then(function(buf) {
                        if (!buf) return;
                        var bytes = new Uint8Array(buf);
                        var binary = '';
                        var chunk = 8192;
                        for (var i = 0; i < bytes.length; i += chunk) {
                            binary += String.fromCharCode.apply(null, bytes.subarray(i, Math.min(i + chunk, bytes.length)));
                        }
                        $bridge.onSegment(btoa(binary));
                    }).catch(function(e) {
                        $bridge.onSegmentError(e.toString());
                    });
                })();"""

                handler.post {
                    webView.evaluateJavascript(script, null)
                }

                latch.await(SEGMENT_TIMEOUT_SEC, TimeUnit.SECONDS)
                return segmentData
            }
        }
    }

    private class JsBridge(private val latch: CountDownLatch) {
        @Volatile var m3u8Content: String? = null

        @Volatile var m3u8BaseUrl: String? = null

        @JavascriptInterface
        fun onM3u8(content: String, baseUrl: String) {
            m3u8Content = content
            m3u8BaseUrl = baseUrl
            latch.countDown()
        }

        @JavascriptInterface
        fun onError(@Suppress("UNUSED_PARAMETER") msg: String) {
            latch.countDown()
        }
    }

    private class HlsProxyServer(private val extractor: NguonCExtractor) {
        private var serverSocket: ServerSocket? = null

        @Volatile var segmentUrls: List<String> = emptyList()

        @Volatile var cachedPlaylist: String? = null
        val segmentCache = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()

        @Volatile private var tsHeader: ByteArray? = null

        val port: Int get() = serverSocket?.localPort ?: 0
        val isClosed: Boolean get() = serverSocket?.isClosed != false

        fun reset() {
            segmentCache.clear()
            tsHeader = null
            cachedPlaylist = null
        }

        fun start() {
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            Thread({
                while (!ss.isClosed) {
                    try {
                        val conn = ss.accept()
                        Thread { handleConnection(conn) }.start()
                    } catch (_: Exception) {
                        break
                    }
                }
            }, "NguonC-Proxy").start()
        }

        private fun handleConnection(socket: Socket) {
            try {
                socket.soTimeout = 60_000
                val input = socket.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                while (input.readLine()?.isEmpty() == false) { /* consume headers */ }

                val path = requestLine.split(" ").getOrNull(1) ?: return
                val output = socket.getOutputStream()

                when {
                    path == "/playlist.m3u8" -> servePlaylist(output)
                    path.startsWith("/seg/") -> serveSegment(path, output)
                    else -> writeHttp(output, 404, "text/plain", "Not Found".toByteArray())
                }
            } catch (_: Exception) {
            } finally {
                try {
                    socket.shutdownOutput()
                    socket.close()
                } catch (_: Exception) {}
            }
        }

        private fun servePlaylist(output: OutputStream) {
            val body = (cachedPlaylist ?: "#EXTM3U\n").toByteArray()
            writeHttp(output, 200, "application/vnd.apple.mpegurl", body)
        }

        private fun serveSegment(path: String, output: OutputStream) {
            val idx = path.removePrefix("/seg/").removeSuffix(".ts").toIntOrNull()
            if (idx == null || idx < 0 || idx >= segmentUrls.size) {
                writeHttp(output, 404, "text/plain", "Invalid segment".toByteArray())
                return
            }

            try {
                var data = segmentCache[idx]
                if (data == null) {
                    val url = segmentUrls[idx]
                    val bytes = extractor.fetchSegment(url)
                    if (bytes == null) {
                        writeHttp(output, 502, "text/plain", "Fetch failed".toByteArray())
                        return
                    }
                    data = if (bytes.size > PNG_HEADER_SIZE && extractor.isPng(bytes)) {
                        bytes.copyOfRange(PNG_HEADER_SIZE, bytes.size)
                    } else {
                        bytes
                    }
                    segmentCache[idx] = data
                }

                if (tsHeader == null && data.size > 188) {
                    tsHeader = extractTsHeader(data)
                }

                val header = tsHeader
                val body = if (header != null && !startsWithPat(data)) {
                    header + data
                } else {
                    data
                }

                writeHttp(output, 200, "video/mp2t", body)

                if (idx + 1 < segmentUrls.size && !segmentCache.containsKey(idx + 1)) {
                    Thread {
                        try {
                            val nextUrl = segmentUrls[idx + 1]
                            val nextBytes = extractor.fetchSegment(nextUrl)
                            if (nextBytes != null) {
                                val result = if (nextBytes.size > PNG_HEADER_SIZE && extractor.isPng(nextBytes)) {
                                    nextBytes.copyOfRange(PNG_HEADER_SIZE, nextBytes.size)
                                } else {
                                    nextBytes
                                }
                                segmentCache[idx + 1] = result
                            }
                        } catch (_: Exception) {}
                    }.start()
                }

                if (idx > 3) segmentCache.remove(idx - 3)
            } catch (_: Exception) {
                writeHttp(output, 502, "text/plain", "Error".toByteArray())
            }
        }

        private fun extractTsHeader(data: ByteArray): ByteArray? {
            var patPacket: ByteArray? = null
            var pmtPid = -1
            var pmtPacket: ByteArray? = null

            val packetCount = minOf(data.size / 188, 200)
            for (i in 0 until packetCount) {
                val offset = i * 188
                if (data[offset].toInt() and 0xFF != 0x47) continue
                val pid = ((data[offset + 1].toInt() and 0x1F) shl 8) or (data[offset + 2].toInt() and 0xFF)
                if (pid == 0 && patPacket == null) {
                    patPacket = data.copyOfRange(offset, offset + 188)
                    val adaptFlag = (data[offset + 3].toInt() shr 4) and 0x03
                    var payloadStart = offset + 4
                    if (adaptFlag and 0x02 != 0) {
                        payloadStart += 1 + (data[offset + 4].toInt() and 0xFF)
                    }
                    val pusi = (data[offset + 1].toInt() and 0x40) != 0
                    if (pusi && payloadStart < offset + 188) {
                        payloadStart += 1 + (data[payloadStart].toInt() and 0xFF)
                    }
                    val tableStart = payloadStart
                    if (tableStart + 12 < offset + 188) {
                        val sectionLen = ((data[tableStart + 1].toInt() and 0x0F) shl 8) or (data[tableStart + 2].toInt() and 0xFF)
                        val programStart = tableStart + 8
                        val programEnd = tableStart + 3 + sectionLen - 4
                        if (programStart + 4 <= minOf(programEnd, offset + 188)) {
                            pmtPid = ((data[programStart + 2].toInt() and 0x1F) shl 8) or (data[programStart + 3].toInt() and 0xFF)
                        }
                    }
                }
                if (pmtPid > 0 && pid == pmtPid && pmtPacket == null) {
                    pmtPacket = data.copyOfRange(offset, offset + 188)
                    break
                }
            }
            if (patPacket == null) return null
            return if (pmtPacket != null) patPacket + pmtPacket else patPacket
        }

        private fun startsWithPat(data: ByteArray): Boolean {
            if (data.size < 3) return false
            if (data[0].toInt() and 0xFF != 0x47) return false
            val pid = ((data[1].toInt() and 0x1F) shl 8) or (data[2].toInt() and 0xFF)
            return pid == 0
        }

        private fun writeHttp(output: OutputStream, code: Int, contentType: String, body: ByteArray) {
            val status = if (code == 200) "OK" else "Error"
            val header = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(body)
            output.flush()
        }
    }

    companion object {
        private const val TIMEOUT_SEC = 15L
        private const val SEGMENT_TIMEOUT_SEC = 30L
        private const val PNG_HEADER_SIZE = 127

        private const val EXTRACT_SCRIPT_TEMPLATE = """(function() {
    function tryExtract(retries) {
        try {
            var el = document.querySelector('[data-obf]');
            if (!el) {
                if (retries > 0) { setTimeout(function() { tryExtract(retries - 1); }, 1000); return; }
                __BRIDGE__.onError('no data-obf'); return;
            }
            var decoded = JSON.parse(atob(el.getAttribute('data-obf')));
            var m3u8Url = window.location.origin + '/' + decoded.sUb + '.m3u8';
            var baseUrl = m3u8Url.substring(0, m3u8Url.lastIndexOf('/') + 1);
            fetch(m3u8Url).then(function(r) {
                if (!r.ok) { __BRIDGE__.onError('fetch ' + r.status); return; }
                return r.text();
            }).then(function(text) {
                if (text) __BRIDGE__.onM3u8(text, baseUrl);
            }).catch(function(e) {
                __BRIDGE__.onError(e.toString());
            });
        } catch(e) {
            __BRIDGE__.onError(e.toString());
        }
    }
    tryExtract(3);
})();"""
    }
}
