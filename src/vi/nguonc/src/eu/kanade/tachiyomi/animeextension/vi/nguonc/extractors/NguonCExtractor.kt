package eu.kanade.tachiyomi.animeextension.vi.nguonc.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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
import java.net.URLDecoder
import java.net.URLEncoder
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

        // Store segment bridge name for fetch scripts
        segFetcher.bridgeName = segBridgeName

        handler.post {
            // Destroy previous WebView if any
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
        Log.d(TAG, "m3u8 base: $baseUrl")

        // Navigate WebView to CDN origin so segment fetches are same-origin (bypass CORS)
        val cdnOrigin = extractCdnOrigin(m3u8Content, baseUrl)
        if (cdnOrigin != null) {
            Log.d(TAG, "Switching WebView origin to: $cdnOrigin")
            val navLatch = CountDownLatch(1)
            handler.post {
                activeWebView?.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        navLatch.countDown()
                    }
                }
                activeWebView?.loadDataWithBaseURL(
                    cdnOrigin,
                    "<html></html>",
                    "text/html",
                    "UTF-8",
                    null,
                )
            }
            navLatch.await(5, TimeUnit.SECONDS)
        }

        val server = ensureProxyRunning()
        server.cachedPlaylist = rewriteForProxy(m3u8Content, server.port, baseUrl)

        val proxyUrl = "http://127.0.0.1:${server.port}/playlist.m3u8"
        return listOf(Video(proxyUrl, "Video", proxyUrl))
    }

    // Fetch a segment via the active WebView's fetch() API (bypasses TLS fingerprinting)
    fun fetchSegment(url: String): ByteArray? {
        val wv = activeWebView ?: return null
        val bridgeName = segFetcher.bridgeName ?: return null

        return segFetcher.fetch(url, bridgeName, wv, handler)
    }

    private fun extractCdnOrigin(m3u8: String, baseUrl: String): String? {
        val firstSeg = m3u8.lines().firstOrNull {
            it.isNotBlank() && !it.startsWith("#")
        }?.trim() ?: return null
        val url = when {
            firstSeg.startsWith("http") -> firstSeg
            firstSeg.startsWith("/") -> baseUrl.split("/").take(3).joinToString("/") + firstSeg
            else -> baseUrl + firstSeg
        }
        val origin = url.split("/").take(3).joinToString("/")
        // Only switch if it's a different origin
        val embedOrigin = baseUrl.split("/").take(3).joinToString("/")
        return if (origin != embedOrigin) origin else null
    }

    private fun rewriteForProxy(m3u8: String, port: Int, baseUrl: String): String = m3u8.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            val absoluteUrl = when {
                trimmed.startsWith("http") -> trimmed
                trimmed.startsWith("/") -> {
                    val origin = baseUrl.split("/").take(3).joinToString("/")
                    "$origin$trimmed"
                }
                else -> "$baseUrl$trimmed"
            }
            "http://127.0.0.1:$port/seg?u=${URLEncoder.encode(absoluteUrl, "UTF-8")}"
        } else {
            line
        }
    }

    private fun generateBridgeName(): String {
        val pool = ('a'..'z') + ('A'..'Z')
        return (1..(10..20).random()).map { pool.random() }.joinToString("")
    }

    private fun ensureProxyRunning(): HlsProxyServer {
        proxy?.let { if (!it.isClosed) return it }
        val server = HlsProxyServer(this)
        server.start()
        proxy = server
        return server
    }

    // Bridge for fetching segments via WebView JS. Serializes fetches with a lock.
    class SegmentFetcher {
        private val lock = Object()

        @Volatile var bridgeName: String? = null

        @Volatile private var segmentData: ByteArray? = null

        @Volatile private var segmentError: String? = null

        @Volatile private var segmentLatch: CountDownLatch? = null

        @JavascriptInterface
        fun onSegment(base64: String) {
            segmentData = Base64.decode(base64, Base64.DEFAULT)
            segmentLatch?.countDown()
        }

        @JavascriptInterface
        fun onSegmentError(msg: String) {
            Log.e(TAG, "Segment fetch error: $msg")
            segmentError = msg
            segmentLatch?.countDown()
        }

        fun fetch(url: String, bridge: String, webView: WebView, handler: Handler): ByteArray? {
            synchronized(lock) {
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

                val completed = latch.await(SEGMENT_TIMEOUT_SEC, TimeUnit.SECONDS)
                if (!completed) Log.e(TAG, "Segment fetch timed out: $url")
                if (segmentError != null) Log.e(TAG, "Segment error for: $url -> $segmentError")
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
        fun onError(msg: String) {
            Log.e(TAG, "JS error: $msg")
            latch.countDown()
        }
    }

    private class HlsProxyServer(private val extractor: NguonCExtractor) {
        private var serverSocket: ServerSocket? = null

        @Volatile var cachedPlaylist: String? = null
        val port: Int get() = serverSocket?.localPort ?: 0
        val isClosed: Boolean get() = serverSocket?.isClosed != false

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
                    path.startsWith("/seg?u=") -> serveSegment(path, output)
                    else -> writeHttp(output, 404, "text/plain", "Not Found".toByteArray())
                }
            } catch (_: Exception) {
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {}
            }
        }

        private fun servePlaylist(output: OutputStream) {
            val body = (cachedPlaylist ?: "#EXTM3U\n").toByteArray()
            writeHttp(output, 200, "application/vnd.apple.mpegurl", body)
        }

        private fun serveSegment(path: String, output: OutputStream) {
            val encodedUrl = path.substringAfter("u=")
            val url = URLDecoder.decode(encodedUrl, "UTF-8")

            try {
                val bytes = extractor.fetchSegment(url)
                if (bytes == null) {
                    Log.e(TAG, "Segment fetch returned null: $url")
                    writeHttp(output, 502, "text/plain", "Fetch failed".toByteArray())
                    return
                }

                Log.d(TAG, "Segment OK size=${bytes.size} first4=${bytes.take(4).map { it.toInt() and 0xFF }}")

                val result = if (bytes.size > PNG_HEADER_SIZE && isPng(bytes)) {
                    bytes.copyOfRange(PNG_HEADER_SIZE, bytes.size)
                } else {
                    bytes
                }
                writeHttp(output, 200, "video/mp2t", result)
            } catch (e: Exception) {
                Log.e(TAG, "Segment exception: ${e.message}", e)
                writeHttp(output, 502, "text/plain", "Error".toByteArray())
            }
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

        private fun isPng(bytes: ByteArray): Boolean = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    }

    companion object {
        private const val TAG = "NguonCExtractor"
        private const val TIMEOUT_SEC = 15L
        private const val SEGMENT_TIMEOUT_SEC = 30L
        private const val PNG_HEADER_SIZE = 127

        private const val EXTRACT_SCRIPT_TEMPLATE = """(function() {
    try {
        var el = document.querySelector('[data-obf]');
        if (!el) { __BRIDGE__.onError('no data-obf'); return; }
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
})();"""
    }
}
