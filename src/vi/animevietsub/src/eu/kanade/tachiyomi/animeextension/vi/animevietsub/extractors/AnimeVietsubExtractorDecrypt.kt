package eu.kanade.tachiyomi.animeextension.vi.animevietsub.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import uy.kohesive.injekt.injectLazy
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class AnimeVietsubExtractorDecrypt(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    private val hlsClient by lazy {
        client.newBuilder()
            .addInterceptor(PngStripInterceptor())
            .build()
    }
    private val playlistUtils by lazy { PlaylistUtils(hlsClient, headers) }

    @Volatile private var segmentProxy: SimpleSegmentProxy? = null

    private fun ensureProxyRunning(): SimpleSegmentProxy {
        segmentProxy?.let { if (!it.isClosed) return it }
        val proxy = SimpleSegmentProxy(client, headers)
        proxy.start()
        segmentProxy = proxy
        return proxy
    }

    private fun rewritePlaylistForProxy(m3u8: String, port: Int): String = m3u8.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        if (trimmed.startsWith("http") && !trimmed.startsWith("#")) {
            "http://127.0.0.1:$port/seg?u=${URLEncoder.encode(trimmed, "UTF-8")}"
        } else {
            line
        }
    }

    private class JsBridge(private val latch: CountDownLatch) {
        @Volatile var playerUrl: String? = null

        @Volatile var decryptedMasterUrl: String? = null

        @Volatile var decryptedMaster: String? = null
        private val directM3u8Urls = linkedSetOf<String>()

        @JavascriptInterface
        fun onPlayerUrl(url: String) {
            playerUrl = url
            latch.countDown()
        }

        @JavascriptInterface
        fun onDirectM3u8(url: String) {
            synchronized(directM3u8Urls) { directM3u8Urls.add(url) }
            latch.countDown()
        }

        @JavascriptInterface
        fun onDone() {
            latch.countDown()
        }

        fun directUrls(): List<String> = synchronized(directM3u8Urls) { directM3u8Urls.toList() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun videosFromEpisodeUrl(episodeUrl: String): List<Video> {
        val latch = CountDownLatch(1)
        val capturedM3u8 = linkedSetOf<String>()
        var webView: WebView? = null
        val jsBridge = JsBridge(latch)

        val requestHeaders = headers.names()
            .mapNotNull { name -> headers[name]?.let { name to it } }
            .toMap()
            .toMutableMap()

        handler.post {
            val newView = WebView(context)
            webView = newView

            with(newView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = false
                loadWithOverviewMode = false
                userAgentString = headers["User-Agent"]
            }

            newView.addJavascriptInterface(jsBridge, JS_BRIDGE_NAME)
            newView.webChromeClient = object : android.webkit.WebChromeClient() {
                override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
                    msg?.let { Log.d(TAG, "JS[${it.sourceId()}:${it.lineNumber()}] ${it.message()}") }
                    return true
                }
            }
            newView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    if (M3U8_REGEX.containsMatchIn(url) && !url.contains("googleapiscdn.com")) {
                        synchronized(capturedM3u8) {
                            if (capturedM3u8.add(url)) latch.countDown()
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null) return
                    view?.evaluateJavascript(DECRYPT_SCRIPT.replace("__BRIDGE__", JS_BRIDGE_NAME), null)
                }
            }

            webView?.loadUrl(episodeUrl, requestHeaders)
        }

        val latchResult = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val playerUrl = jsBridge.playerUrl
        val directUrls = jsBridge.directUrls()
        val captured = synchronized(capturedM3u8) { capturedM3u8.toList() }

        if (playerUrl != null) {
            try {
                val result = fetchAndDecrypt(playerUrl)
                if (result != null) {
                    val proxy = ensureProxyRunning()
                    val rewritten = rewritePlaylistForProxy(result, proxy.port)
                    proxy.cachedPlaylist = rewritten
                    val proxyUrl = "http://127.0.0.1:${proxy.port}/playlist.m3u8"
                    return listOf(Video(proxyUrl, "AnimeVsub", proxyUrl))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Decrypt mode failed", e)
            }
        }

        val candidateM3u8Urls = linkedSetOf<String>().apply {
            addAll(directUrls)
            addAll(captured)
        }.toList()
            .sortedByDescending { url -> MASTER_M3U8_HINT_REGEX.containsMatchIn(url) }

        if (candidateM3u8Urls.isEmpty()) {
            Log.e(TAG, "No video URLs found")
            return emptyList()
        }

        val videos = buildList {
            candidateM3u8Urls.forEach { playlistUrl ->
                addAll(
                    playlistUtils.extractFromHls(
                        playlistUrl = playlistUrl,
                        referer = episodeUrl,
                        videoNameGen = { quality -> "AnimeVsub:$quality" },
                    ),
                )
            }
        }

        return videos.distinctBy { it.videoUrl }
    }

    // Fetch player page, extract token, fetch m3u8, Layer 1 + Layer 2 decrypt
    private fun fetchAndDecrypt(playerUrl: String): String? {
        val reqBuilder = Request.Builder().url(playerUrl)
        headers.names().forEach { name ->
            if (!name.equals("Host", ignoreCase = true)) {
                headers[name]?.let { reqBuilder.header(name, it) }
            }
        }
        reqBuilder.header("Referer", playerUrl)
        val cookies = CookieManager.getInstance().getCookie(playerUrl)
        if (!cookies.isNullOrBlank()) reqBuilder.header("Cookie", cookies)

        val pageResponse = client.newCall(reqBuilder.build()).execute()
        val html = pageResponse.body.string()
        pageResponse.headers("Set-Cookie").forEach { cookie ->
            CookieManager.getInstance().setCookie(playerUrl, cookie)
        }

        val tokenMatch = Regex("""const\s+avsToken\s*=\s*"([^"]+)"""").find(html)
            ?: run {
                Log.e(TAG, "No avsToken in player page")
                return null
            }
        val avsToken = tokenMatch.groupValues[1]

        val hashMatch = Regex("""/player/([0-9a-f]+)""").find(playerUrl)
            ?: run {
                Log.e(TAG, "No hash in player URL")
                return null
            }
        val videoHash = hashMatch.groupValues[1]

        val baseUrl = playerUrl.toHttpUrl().let { "${it.scheme}://${it.host}" }
        val m3u8Url = "$baseUrl/playlist/$videoHash/playlist.m3u8?token=${URLEncoder.encode(avsToken, "UTF-8")}"

        val m3u8ReqBuilder = Request.Builder().url(m3u8Url)
        headers.names().forEach { name ->
            if (!name.equals("Host", ignoreCase = true)) {
                headers[name]?.let { m3u8ReqBuilder.header(name, it) }
            }
        }
        m3u8ReqBuilder.header("Referer", playerUrl)
        val m3u8Cookies = CookieManager.getInstance().getCookie(m3u8Url)
        if (!m3u8Cookies.isNullOrBlank()) m3u8ReqBuilder.header("Cookie", m3u8Cookies)

        val m3u8Response = client.newCall(m3u8ReqBuilder.build()).execute()
        val m3u8Text = m3u8Response.body.string()

        val envelope = m3u8Response.header("X-Envelope")
            ?: m3u8Response.header("x-envelope")

        var cn = ""
        var sk = ""
        var ts = "0"
        var uid = "anon"

        if (envelope != null) {
            try {
                val envJson = parseUsdkEnvelope(envelope)
                if (envJson != null) {
                    cn = envJson.optString("cn", "")
                    sk = envJson.optString("sk", "")
                    ts = envJson.optString("ts", "0")
                    uid = envJson.optString("uid", "anon")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Envelope parse error", e)
            }
        }

        if (cn.isEmpty()) cn = m3u8Response.header("X-Edge-Tag") ?: ""
        if (sk.isEmpty()) sk = m3u8Response.header("X-Cache-Node") ?: ""
        if (ts == "0") ts = m3u8Response.header("X-Request-Trace") ?: "0"
        if (uid == "anon") {
            m3u8Response.header("X-Proxy-Digest")?.let { uid = it }
        }

        if (cn.isEmpty() || sk.isEmpty()) {
            Log.e(TAG, "Missing cn/sk for decryption")
            return null
        }

        // Layer 1: AES-GCM
        val intermediateM3u8 = decryptLayer1(m3u8Text, cn, sk, ts, uid) ?: return null

        // Parse JWT jti for Layer 2
        val jwtParts = avsToken.split(".")
        val payloadJson = String(base64UrlDecode(jwtParts[1]), Charsets.UTF_8)
        val jti = JSONObject(payloadJson).getString("jti")
        val jtiOdd = buildString {
            for (idx in jti.indices) {
                if (idx % 2 == 1) append(jti[idx])
            }
        }

        // Layer 2: AES-CTR decrypt segment URLs
        val finalM3u8 = decryptLayer2(intermediateM3u8, jtiOdd, baseUrl)
        return finalM3u8
    }

    private fun parseUsdkEnvelope(envB64: String): JSONObject? {
        val bytes = base64UrlDecode(envB64)
        if (bytes.size < 11) return null
        if (bytes[0] != 85.toByte() || bytes[1] != 83.toByte() ||
            bytes[2] != 68.toByte() || bytes[3] != 75.toByte()
        ) {
            return null
        }
        if (bytes[4] != 1.toByte()) return null
        val payloadLen = (bytes[5].toInt() and 0xFF shl 8) or (bytes[6].toInt() and 0xFF)
        if (bytes.size < 7 + payloadLen + 4) return null
        val payload = bytes.copyOfRange(7, 7 + payloadLen)
        val str = String(payload, Charsets.UTF_8)
        return JSONObject(str)
    }

    // Layer 1: collect _t params, unshuffle, AES-GCM decrypt
    private fun decryptLayer1(m3u8Text: String, cn: String, sk: String, ts: String, uid: String): String? {
        val lines = m3u8Text.split("\n")
        val headerLines = mutableListOf<String>()
        val tValues = mutableListOf<String>()

        for (line in lines) {
            if (line.startsWith("#") || line.trim().isEmpty()) {
                if (!line.startsWith("#EXTINF:") && !line.startsWith("#EXT-X-ENDLIST") && !line.startsWith("#EXT-X-KEY")) {
                    headerLines.add(line)
                }
            } else {
                val tMatch = Regex("""[?&]_t=([^&\s]+)""").find(line)
                if (tMatch != null) tValues.add(tMatch.groupValues[1])
            }
        }

        if (tValues.isEmpty()) {
            Log.e(TAG, "No _t params in m3u8")
            return null
        }

        val concatenated = tValues.joinToString("")
        val cnBytes = base64UrlDecode(cn)
        val iv = cnBytes.copyOfRange(0, 12)

        data class Attempt(val unshuffleFn: (String) -> String, val hmacData: String)

        val attempts = listOf(
            Attempt({ s -> stringUnshuffle(s, sk) }, "$uid:$ts:$sk:0"),
            Attempt({ s -> stringUnshuffle(s, sk) }, "$uid:$ts:$sk"),
            Attempt({ s -> s }, "$uid:$ts:$sk:0"),
            Attempt({ s -> s }, "$uid:$ts:$sk"),
        )

        for (attempt in attempts) {
            try {
                val unshuffled = attempt.unshuffleFn(concatenated)
                val encryptedBlob = base64UrlDecode(unshuffled)

                val hmacKey = SecretKeySpec(cnBytes, "HmacSHA256")
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(hmacKey)
                val gcmKeyBytes = mac.doFinal(attempt.hmacData.toByteArray(Charsets.UTF_8))

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val gcmSpec = GCMParameterSpec(128, iv)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(gcmKeyBytes, "AES"), gcmSpec)
                val decrypted = cipher.doFinal(encryptedBlob)

                val m3u8Body = String(decrypted, Charsets.UTF_8)
                val result = headerLines.joinToString("\n") + "\n" + m3u8Body
                val finalResult = if (!result.contains("#EXT-X-ENDLIST")) "$result\n#EXT-X-ENDLIST" else result

                if (finalResult.contains("#EXTINF") || finalResult.contains("/hls/")) {
                    return finalResult
                }
            } catch (_: Exception) {
                continue
            }
        }

        Log.e(TAG, "All Layer 1 decrypt attempts failed")
        return null
    }

    // LCG-based string unshuffle (Fisher-Yates reversal)
    private fun stringUnshuffle(str: String, sk: String): String {
        val chars = str.toCharArray()
        val len = chars.size
        var state = deriveSeed(sk)
        val swaps = mutableListOf<Pair<Int, Int>>()

        for (i in len - 1 downTo 1) {
            state = lcgNext(state)
            swaps.add(i to (state.toLong() and 0xFFFFFFFFL).rem(i + 1).toInt())
        }

        for ((a, b) in swaps.reversed()) {
            val tmp = chars[a]
            chars[a] = chars[b]
            chars[b] = tmp
        }
        return String(chars)
    }

    private fun deriveSeed(sk: String): Int {
        val hex = sk.substring(0, minOf(8, sk.length))
        return try {
            java.lang.Long.parseLong(hex, 16).toInt()
        } catch (_: Exception) {
            0
        }
    }

    private fun lcgNext(state: Int): Int = (state.toLong() * 1664525L + 1013904223L).toInt()

    // Layer 2: AES-CTR decrypt segment URLs
    private fun decryptLayer2(intermediateM3u8: String, jtiOdd: String, baseUrl: String): String {
        val lines = intermediateM3u8.split("\n").toMutableList()
        val hlsRe = Regex("""/hls/([0-9a-f]{24})\.ts[^#\s]*""")

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#") || line.isEmpty()) continue

            val match = hlsRe.find(line) ?: continue
            val fileId = match.groupValues[1]

            val url = if (line.startsWith("http")) line else "$baseUrl$line"
            val httpUrl = url.toHttpUrl()
            val eParam = httpUrl.queryParameter("e") ?: continue
            val iParam = httpUrl.queryParameter("i")?.toIntOrNull() ?: 0

            try {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(SecretKeySpec(jtiOdd.toByteArray(Charsets.UTF_8), "HmacSHA256"))
                val aesKey = mac.doFinal("url-cipher|$fileId".toByteArray(Charsets.UTF_8))

                val counter = ByteArray(16)
                counter[12] = (iParam shr 24 and 0xFF).toByte()
                counter[13] = (iParam shr 16 and 0xFF).toByte()
                counter[14] = (iParam shr 8 and 0xFF).toByte()
                counter[15] = (iParam and 0xFF).toByte()

                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(counter))
                val decrypted = cipher.doFinal(base64UrlDecode(eParam))
                val realUrl = String(decrypted, Charsets.UTF_8)

                if (realUrl.startsWith("http")) {
                    lines[i] = realUrl
                }
            } catch (e: Exception) {
                Log.e(TAG, "Layer2 decrypt failed for segment $i", e)
            }
        }

        // Remove avs-shield KEY lines and undecrypted /hls/ placeholders
        return lines.filter { line ->
            !(line.contains("#EXT-X-KEY:") && line.contains("urn:avs:shield")) &&
                !Regex("""/hls/[0-9a-f]{24}\.ts""").containsMatchIn(line)
        }.joinToString("\n")
    }

    private fun base64UrlDecode(input: String): ByteArray {
        val base64 = input.replace('-', '+').replace('_', '/')
        val padded = when (base64.length % 4) {
            2 -> "$base64=="
            3 -> "$base64="
            else -> base64
        }
        return android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
    }

    private class PngStripInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val body = response.body
            val contentType = response.header("Content-Type") ?: ""

            if (contentType.startsWith("text/") ||
                contentType.contains("json") ||
                contentType.contains("mpegurl")
            ) {
                return response
            }

            val bytes = body.bytes()
            if (bytes.size > PNG_HEADER_SIZE && isPngMagic(bytes)) {
                val stripped = bytes.copyOfRange(PNG_HEADER_SIZE, bytes.size)
                val newBody = stripped.toResponseBody("video/mp2t".toMediaType())
                return response.newBuilder().body(newBody).build()
            }

            val newBody = bytes.toResponseBody(body.contentType())
            return response.newBuilder().body(newBody).build()
        }

        private fun isPngMagic(bytes: ByteArray): Boolean = bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() && bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
            bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte()
    }

    // Simple proxy that serves m3u8 and strips PNG headers from segments (no Layer 2 needed)
    private class SimpleSegmentProxy(private val httpClient: OkHttpClient, private val headers: Headers) {
        private var serverSocket: ServerSocket? = null

        @Volatile var cachedPlaylist: String? = null
        val port: Int get() = serverSocket?.localPort ?: 0
        val isClosed: Boolean get() = serverSocket?.isClosed != false

        fun start(): Int {
            if (serverSocket != null && !serverSocket!!.isClosed) return port
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            Thread({
                while (!ss.isClosed) {
                    try {
                        val conn = ss.accept()
                        Thread { handleConnection(conn) }.start()
                    } catch (_: Exception) {}
                }
            }, "AVS-DecryptProxy").start()
            return ss.localPort
        }

        fun stop() {
            try {
                serverSocket?.close()
            } catch (_: Exception) {}
            serverSocket = null
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
                    path.startsWith("/seg?u=") -> {
                        try {
                            serveSegment(path, output)
                        } catch (e: Exception) {
                            Log.e(TAG, "Proxy serveSegment error", e)
                            writeHttp(output, 502, "text/plain", "Segment fetch error: ${e.message}".toByteArray())
                        }
                    }
                    else -> writeHttp(output, 404, "text/plain", "Not Found".toByteArray())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy connection error", e)
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
            val url = java.net.URLDecoder.decode(encodedUrl, "UTF-8")

            val reqBuilder = Request.Builder().url(url)
            headers.names().forEach { name ->
                if (!name.equals("Host", ignoreCase = true)) {
                    headers[name]?.let { reqBuilder.header(name, it) }
                }
            }
            reqBuilder.header("Referer", "https://stream.googleapiscdn.com/")
            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrBlank()) reqBuilder.header("Cookie", cookies)

            val response = httpClient.newCall(reqBuilder.build()).execute()
            val bytes = response.body.bytes()
            val result = if (bytes.size > PNG_HEADER_SIZE && isPng(bytes)) {
                bytes.copyOfRange(PNG_HEADER_SIZE, bytes.size)
            } else {
                bytes
            }
            writeHttp(output, 200, "video/mp2t", result)
        }

        private fun writeHttp(output: OutputStream, code: Int, contentType: String, body: ByteArray) {
            val status = if (code == 200) "OK" else "Error"
            val header = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(body)
            output.flush()
        }

        private fun isPng(bytes: ByteArray): Boolean = bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    }

    companion object {
        private const val TAG = "AnimeVietsubDec"
        private const val PNG_HEADER_SIZE = 127
        private const val TIMEOUT_SEC: Long = 30
        private const val JS_BRIDGE_NAME = "AnimeVietsubBridge"
        private val M3U8_REGEX = Regex(""".*\.m3u8(\?.*)?$""", RegexOption.IGNORE_CASE)
        private val MASTER_M3U8_HINT_REGEX = Regex("""playlist\.m3u8|master\.m3u8""", RegexOption.IGNORE_CASE)

        // JS for episode page: extracts player URL and reports to Kotlin instead of iframe
        private const val DECRYPT_SCRIPT = """
            (function () {
              var bridge = window.__BRIDGE__;
              if (!bridge || !window.fetch) return;
              if (window.__avsDecryptStarted) return;
              window.__avsDecryptStarted = true;
              var done = false;

              function notifyDone() {
                if (done) return;
                done = true;
                try { bridge.onDone(); } catch (e) {}
              }

              function notifyPlayerUrl(url) {
                try { bridge.onPlayerUrl(url); } catch (e) {}
              }

              function notifyDirect(url) {
                try { bridge.onDirectM3u8(url); } catch (e) {}
              }

              function normalizeLink(link) {
                if (typeof link !== 'string') return null;
                return link.replace(/^&http/, 'http');
              }

              function handleDirectLink(link) {
                var fixed = normalizeLink(link);
                if (!fixed) return false;
                if (/\.m3u8(\?|$)/i.test(fixed)) {
                  notifyDirect(fixed);
                  return true;
                }
                return false;
              }

              function handlePlayerResponse(json) {
                if (!json || !json.success) return Promise.resolve(false);

                if (json.playTech === 'iframe' && typeof json.link === 'string') {
                  if (json.link.indexOf('googleapiscdn.com') !== -1) {
                    notifyPlayerUrl(json.link);
                    return Promise.resolve(true);
                  }
                  return Promise.resolve(handleDirectLink(json.link));
                }

                if (Array.isArray(json.link)) {
                  var found = false;
                  json.link.forEach(function (item) {
                    var file = item && item.file;
                    if (handleDirectLink(file)) found = true;
                  });
                  return Promise.resolve(found);
                }

                if (typeof json.link === 'string') {
                  return Promise.resolve(handleDirectLink(json.link));
                }

                return Promise.resolve(false);
              }

              function callAjaxPlayer(hash, id, referer, site) {
                var postBody = 'link=' + encodeURIComponent(hash);
                if (id) postBody += '&id=' + encodeURIComponent(id);

                return fetch(site + '/ajax/player', {
                  method: 'POST',
                  headers: {
                    'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
                    'X-Requested-With': 'XMLHttpRequest',
                    'Referer': referer || (site + '/'),
                  },
                  body: postBody,
                })
                  .then(function (res) { return res.text(); })
                  .then(function (text) {
                    try { return JSON.parse(text); } catch (e) { return null; }
                  })
                  .then(handlePlayerResponse)
                  .catch(function () { return false; });
              }

              function parseFromPlayerData(pd) {
                if (!pd) return Promise.resolve(false);

                if (pd.playTech === 'iframe' && typeof pd.link === 'string') {
                  if (pd.link.indexOf('googleapiscdn.com') !== -1) {
                    notifyPlayerUrl(pd.link);
                    return Promise.resolve(true);
                  }
                  return Promise.resolve(handleDirectLink(pd.link));
                }

                if ((pd.playTech === 'api' || pd.playTech === 'all') && Array.isArray(pd.link)) {
                  var foundFromArray = false;
                  pd.link.forEach(function (item) {
                    var file = item && item.file;
                    if (handleDirectLink(file)) foundFromArray = true;
                  });
                  return Promise.resolve(foundFromArray);
                }

                if ((pd.playTech === 'api' || pd.playTech === 'all') && typeof pd.link === 'string') {
                  return Promise.resolve(handleDirectLink(pd.link));
                }

                return Promise.resolve(false);
              }

              function findActiveEpisodeData() {
                var active = document.querySelector('#list-server .server-group a.btn-episode.episode-link.active, a.btn-episode.episode-link.active');
                if (!active) active = document.querySelector('#list-server .server-group a.btn-episode.active, a.btn-episode.active');
                if (!active) return null;

                var hash = active.getAttribute('data-hash');
                if (!hash) return null;

                return {
                  hash: hash,
                  id: active.getAttribute('data-id'),
                  referer: window.location.href,
                  site: window.location.origin,
                };
              }

              function start() {
                Promise.resolve()
                  .then(function () {
                    return parseFromPlayerData(window.PLAYER_DATA);
                  })
                  .then(function (found) {
                    if (found) return true;
                    var meta = findActiveEpisodeData();
                    if (!meta) return false;
                    return callAjaxPlayer(meta.hash, meta.id, meta.referer, meta.site);
                  })
                  .then(
                    function () { notifyDone(); },
                    function () { notifyDone(); }
                  );
              }

              start();
            })();
        """
    }
}
