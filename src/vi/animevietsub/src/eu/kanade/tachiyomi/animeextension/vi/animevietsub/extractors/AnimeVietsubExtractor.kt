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
import android.widget.Toast
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
import uy.kohesive.injekt.injectLazy
import java.io.ByteArrayInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnimeVietsubExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    // Client with PNG-stripping interceptor for HLS playlist/segment fetching
    private val hlsClient by lazy {
        client.newBuilder()
            .addInterceptor(PngStripInterceptor())
            .build()
    }
    private val playlistUtils by lazy { PlaylistUtils(hlsClient, headers) }

    @Volatile private var segmentProxy: SegmentProxyServer? = null

    private fun ensureProxyRunning(): SegmentProxyServer {
        segmentProxy?.let { if (!it.isClosed) return it }
        val proxy = SegmentProxyServer(client)
        proxy.start()
        segmentProxy = proxy
        return proxy
    }

    private fun rewritePlaylistForProxy(m3u8: String, port: Int): String =
        m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("http") && !trimmed.startsWith("#")) {
                "http://127.0.0.1:$port/seg?u=${URLEncoder.encode(trimmed, "UTF-8")}"
            } else {
                line
            }
        }

    private class JsBridge(private val latch: CountDownLatch) {
        @Volatile var decryptedMaster: String? = null

        @Volatile var decryptedMasterUrl: String? = null
        private val directM3u8Urls = linkedSetOf<String>()

        @JavascriptInterface
        fun onDecrypted(masterUrl: String, playlistText: String) {
            Log.e(TAG, "onDecrypted: url=$masterUrl, text=${playlistText.take(200)}")
            decryptedMasterUrl = masterUrl
            decryptedMaster = playlistText
            latch.countDown()
        }

        @JavascriptInterface
        fun onDirectM3u8(url: String) {
            Log.e(TAG, "onDirectM3u8: $url")
            synchronized(directM3u8Urls) {
                directM3u8Urls.add(url)
            }
            latch.countDown()
        }

        @JavascriptInterface
        fun onDone() {
            Log.e(TAG, "onDone: decrypted=${decryptedMaster != null}, directUrls=${directM3u8Urls.size}")
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
                    msg?.let { Log.e(TAG, "JS[${it.sourceId()}:${it.lineNumber()}] ${it.message()}") }
                    return true
                }
            }
            newView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url?.toString() ?: return super.shouldInterceptRequest(view, request)

                    // Capture m3u8 URLs from network traffic (skip encrypted googleapiscdn ones)
                    if (M3U8_REGEX.containsMatchIn(url) && !url.contains("googleapiscdn.com")) {
                        Log.e(TAG, "Captured m3u8: $url")
                        synchronized(capturedM3u8) {
                            if (capturedM3u8.add(url)) latch.countDown()
                        }
                    }

                    // Proxy requests to googleapiscdn.com
                    if (url.contains("googleapiscdn.com")) {
                        // Player page navigation: proxy HTML and inject capture script
                        if (isNavigationRequest(request) && url.contains("/player/")) {
                            Log.e(TAG, "Proxying player page: $url")
                            return proxyPlayerPage(url, request)
                        }
                        // Non-navigation: proxy with CORS headers for JS fetch
                        if (!isNavigationRequest(request)) {
                            Log.e(TAG, "Proxying googleapiscdn: $url")
                            return proxyWithCors(url, request)
                        }
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null) return
                    Log.e(TAG, "onPageFinished: $url")
                    view?.evaluateJavascript(DECRYPT_SCRIPT_TEMPLATE.replace("__BRIDGE__", JS_BRIDGE_NAME), null)
                }
            }

            webView?.loadUrl(episodeUrl, requestHeaders)
        }

        val latchResult = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)
        Log.e(TAG, "Latch result: $latchResult (true=signaled, false=timeout)")

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val decryptedMaster = jsBridge.decryptedMaster
        val decryptedMasterUrl = jsBridge.decryptedMasterUrl
        val directUrls = jsBridge.directUrls()
        val captured = synchronized(capturedM3u8) { capturedM3u8.toList() }

        val debugMsg = "latch=$latchResult, decrypted=${decryptedMaster != null}, " +
            "directUrls=${directUrls.size}, captured=${captured.size}"
        Log.e(TAG, debugMsg)
        handler.post { Toast.makeText(context, "AVS: $debugMsg", Toast.LENGTH_LONG).show() }

        if (decryptedMaster != null && decryptedMasterUrl != null) {
            Log.e(TAG, "Decrypted master URL: $decryptedMasterUrl")
            Log.e(TAG, "Decrypted master text (first 300): ${decryptedMaster.take(300)}")
            try {
                val proxy = ensureProxyRunning()
                val rewritten = rewritePlaylistForProxy(decryptedMaster, proxy.port)
                proxy.cachedPlaylist = rewritten
                val proxyUrl = "http://127.0.0.1:${proxy.port}/playlist.m3u8"
                Log.e(TAG, "Serving decrypted m3u8 via local proxy: $proxyUrl")
                handler.post { Toast.makeText(context, "AVS: proxy on port ${proxy.port}", Toast.LENGTH_SHORT).show() }
                return listOf(Video(proxyUrl, "AnimeVsub", proxyUrl))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start proxy, falling back to extractFromHls", e)
                val parsedFromDecrypted = parseDecryptedMasterPlaylist(decryptedMasterUrl, decryptedMaster)
                Log.e(TAG, "Parsed from decrypted: ${parsedFromDecrypted.size} videos")
                if (parsedFromDecrypted.isNotEmpty()) {
                    return parsedFromDecrypted
                }
            }
        }

        val candidateM3u8Urls = linkedSetOf<String>().apply {
            addAll(directUrls)
            addAll(captured)
        }.toList()
            .sortedByDescending { url -> MASTER_M3U8_HINT_REGEX.containsMatchIn(url) }

        Log.e(TAG, "Candidate m3u8 URLs: $candidateM3u8Urls")

        if (candidateM3u8Urls.isEmpty()) {
            Log.e(TAG, "No video URLs found!")
            handler.post { Toast.makeText(context, "AVS: No video URLs found", Toast.LENGTH_LONG).show() }
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

    private fun parseDecryptedMasterPlaylist(masterUrl: String, playlistText: String): List<Video> {
        val lines = playlistText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.none { it.startsWith("#EXTM3U") }) return emptyList()

        val streamInfos = buildList {
            for (index in lines.indices) {
                val line = lines[index]
                if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true)) continue
                val streamUrl = lines.getOrNull(index + 1)
                    ?.takeIf { !it.startsWith("#") }
                    ?: continue

                val resolution = RESOLUTION_REGEX.find(line)?.groupValues?.get(1)
                val quality = resolution
                    ?.substringAfter('x', "")
                    ?.takeIf { it.isNotBlank() }
                    ?.let { "${it}p" }
                    ?: "Auto"

                val absoluteStreamUrl = normalizeUrl(masterUrl, streamUrl)
                add(quality to absoluteStreamUrl)
            }
        }

        // Master playlist with variants
        if (streamInfos.isNotEmpty()) {
            return streamInfos.flatMap { (quality, videoUrl) ->
                playlistUtils.extractFromHls(
                    playlistUrl = videoUrl,
                    referer = masterUrl,
                    videoNameGen = { "AnimeVsub:$quality" },
                )
            }.distinctBy { it.videoUrl }
        }

        // Media playlist (no STREAM-INF) — pass the URL directly to playlistUtils
        // which will return a single Video pointing to the original m3u8 URL
        if (lines.any { it.startsWith("#EXTINF:", ignoreCase = true) || it.startsWith("#EXT-X-TARGETDURATION:", ignoreCase = true) }) {
            return playlistUtils.extractFromHls(
                playlistUrl = masterUrl,
                referer = masterUrl,
                videoNameGen = { "AnimeVsub:$it" },
            )
        }

        return emptyList()
    }

    private fun normalizeUrl(baseUrl: String, candidate: String): String {
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate
        return baseUrl.toHttpUrl().resolve(candidate)?.toString() ?: candidate
    }

    // Distinguish navigation requests (iframe loads) from JS fetch calls.
    // Navigation: Accept contains "text/html" and doesn't start with wildcard
    // JS fetch: Accept is wildcard or absent
    private fun isNavigationRequest(request: WebResourceRequest): Boolean {
        val accept = request.requestHeaders?.get("Accept") ?: return false
        return accept.contains("text/html") && !accept.startsWith("*/*")
    }

    // Proxy a request through OkHttp and inject CORS-permissive headers
    // so the WebView allows JS to read the cross-origin response.
    private fun proxyWithCors(url: String, request: WebResourceRequest): WebResourceResponse? = try {
        val reqBuilder = Request.Builder().url(url)

        // Copy request headers from WebView
        request.requestHeaders?.forEach { (key, value) ->
            if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                reqBuilder.header(key, value)
            }
        }

        // Include cookies from WebView CookieManager (CF clearance, etc.)
        val cookies = CookieManager.getInstance().getCookie(url)
        if (!cookies.isNullOrBlank()) {
            reqBuilder.header("Cookie", cookies)
        }

        val response = client.newCall(reqBuilder.build()).execute()
        Log.e(TAG, "Proxy response: ${response.code} for $url")
        val body = response.body.bytes()
        Log.e(TAG, "Proxy body size: ${body.size} bytes")

        // Sync Set-Cookie from response back to CookieManager
        response.headers("Set-Cookie").forEach { cookie ->
            CookieManager.getInstance().setCookie(url, cookie)
        }

        val contentType = response.header("Content-Type") ?: "application/octet-stream"
        val mimeType = contentType.substringBefore(";").trim()
        val charset = if (contentType.contains("charset=")) {
            contentType.substringAfter("charset=").substringBefore(";").trim()
        } else {
            "UTF-8"
        }

        // Build response headers with CORS permissions
        // Skip existing CORS headers to avoid duplicates (case-sensitive map keys)
        val corsHeaders = setOf("access-control-allow-origin", "access-control-allow-headers", "access-control-allow-methods", "access-control-allow-credentials", "access-control-expose-headers")
        val responseHeaders = mutableMapOf<String, String>()
        response.headers.names().forEach { name ->
            if (name.lowercase() !in corsHeaders) {
                response.header(name)?.let { responseHeaders[name] = it }
            }
        }
        responseHeaders["Access-Control-Allow-Origin"] = "*"
        responseHeaders["Access-Control-Allow-Headers"] = "*"
        responseHeaders["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"
        responseHeaders["Access-Control-Expose-Headers"] = "*"

        WebResourceResponse(
            mimeType,
            charset,
            response.code,
            response.message.ifEmpty { "OK" },
            responseHeaders,
            ByteArrayInputStream(body),
        )
    } catch (e: Exception) {
        Log.e(TAG, "proxyWithCors error for $url", e)
        null
    }

    // Proxy the player page HTML and inject a capture script that uses
    // the site's own _avsDecryptM3u8 to decrypt the playlist, then
    // posts the result back to the parent page via postMessage.
    private fun proxyPlayerPage(url: String, request: WebResourceRequest): WebResourceResponse? = try {
        val reqBuilder = Request.Builder().url(url)
        request.requestHeaders?.forEach { (key, value) ->
            if (!key.equals("Accept-Encoding", ignoreCase = true)) {
                reqBuilder.header(key, value)
            }
        }
        val cookies = CookieManager.getInstance().getCookie(url)
        if (!cookies.isNullOrBlank()) reqBuilder.header("Cookie", cookies)

        val response = client.newCall(reqBuilder.build()).execute()
        Log.e(TAG, "Player page response: ${response.code} for $url")

        var html = response.body.string()
        Log.e(TAG, "Player page HTML size: ${html.length}")

        response.headers("Set-Cookie").forEach { cookie ->
            CookieManager.getInstance().setCookie(url, cookie)
        }

        // Inject capture script before </body>
        html = html.replace("</body>", "$PLAYER_CAPTURE_SCRIPT</body>")

        val bodyBytes = html.toByteArray(Charsets.UTF_8)
        WebResourceResponse(
            "text/html",
            "UTF-8",
            response.code,
            response.message.ifEmpty { "OK" },
            mapOf(
                "Content-Type" to "text/html; charset=utf-8",
                "Cache-Control" to "no-cache",
            ),
            ByteArrayInputStream(bodyBytes),
        )
    } catch (e: Exception) {
        Log.e(TAG, "proxyPlayerPage error for $url", e)
        null
    }

    // OkHttp interceptor that strips the 127-byte PNG prefix from responses
    // whose body starts with PNG magic bytes (0x89504E47).
    // AnimeVietsub disguises HLS segments as PNG files.
    private class PngStripInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val response = chain.proceed(chain.request())
            val body = response.body
            val contentType = response.header("Content-Type") ?: ""

            // Only process responses that could be disguised segments
            // Skip known text types (playlists, HTML, JSON)
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

            // Not PNG-wrapped, return original bytes as-is
            val newBody = bytes.toResponseBody(body.contentType())
            return response.newBuilder().body(newBody).build()
        }

        private fun isPngMagic(bytes: ByteArray): Boolean = bytes[0] == 0x89.toByte() &&
            bytes[1] == 0x50.toByte() && // P
            bytes[2] == 0x4E.toByte() && // N
            bytes[3] == 0x47.toByte() && // G
            bytes[4] == 0x0D.toByte() &&
            bytes[5] == 0x0A.toByte() &&
            bytes[6] == 0x1A.toByte() &&
            bytes[7] == 0x0A.toByte()
    }

    // Local HTTP server that serves the decrypted m3u8 playlist and
    // proxies segment requests with PNG-header stripping so mpv can play.
    private class SegmentProxyServer(private val httpClient: OkHttpClient) {
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
            }, "AVS-ProxyAccept").start()
            return ss.localPort
        }

        fun stop() {
            try { serverSocket?.close() } catch (_: Exception) {}
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
                    path.startsWith("/seg?u=") -> serveSegment(path, output)
                    else -> serve404(output)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Proxy connection error", e)
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        private fun servePlaylist(output: OutputStream) {
            val body = (cachedPlaylist ?: "#EXTM3U\n").toByteArray()
            writeHttp(output, 200, "application/vnd.apple.mpegurl", body)
        }

        private fun serveSegment(path: String, output: OutputStream) {
            val url = URLDecoder.decode(path.substringAfter("u="), "UTF-8")
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val bytes = response.body.bytes()
            val result = if (bytes.size > PNG_HEADER_SIZE && isPng(bytes)) {
                bytes.copyOfRange(PNG_HEADER_SIZE, bytes.size)
            } else {
                bytes
            }
            writeHttp(output, 200, "video/mp2t", result)
        }

        private fun serve404(output: OutputStream) {
            writeHttp(output, 404, "text/plain", "Not Found".toByteArray())
        }

        private fun writeHttp(output: OutputStream, code: Int, contentType: String, body: ByteArray) {
            val status = if (code == 200) "OK" else "Not Found"
            val header = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(body)
            output.flush()
        }

        private fun isPng(bytes: ByteArray): Boolean =
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()
    }

    companion object {
        private const val TAG = "AnimeVietsubExt"
        private const val PNG_HEADER_SIZE = 127
        private const val TIMEOUT_SEC: Long = 30
        private const val JS_BRIDGE_NAME = "AnimeVietsubBridge"
        private val M3U8_REGEX = Regex(""".*\.m3u8(\?.*)?$""", RegexOption.IGNORE_CASE)
        private val MASTER_M3U8_HINT_REGEX = Regex("""playlist\.m3u8|master\.m3u8""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

        private const val DECRYPT_SCRIPT_TEMPLATE = """
            (function () {
              var bridge = window.__BRIDGE__;
              if (!bridge || !window.fetch) { console.log('AVS: no bridge or fetch'); return; }
              if (window.__avsDecryptStarted) { console.log('AVS: already started'); return; }
              window.__avsDecryptStarted = true;
              console.log('AVS: script started, PLAYER_DATA=' + JSON.stringify(window.PLAYER_DATA));
              var done = false;

              function notifyDone() {
                if (done) return;
                done = true;
                try { bridge.onDone(); } catch (e) {}
              }

              function notifyDecrypted(url, text) {
                try { bridge.onDecrypted(url, text); } catch (e) {}
              }

              function notifyDirect(url) {
                try { bridge.onDirectM3u8(url); } catch (e) {}
              }

              function normalizeLink(link) {
                if (typeof link !== 'string') return null;
                return link.replace(/^&http/, 'http');
              }

              function tryGoogleApisCdn(playerUrl) {
                console.log('AVS: tryGoogleApisCdn: ' + playerUrl);
                return new Promise(function (resolve) {
                  function onMessage(e) {
                    if (e.data && e.data.type === 'avs-decrypted-m3u8') {
                      window.removeEventListener('message', onMessage);
                      console.log('AVS: got decrypted m3u8, len=' + (e.data.text ? e.data.text.length : 0));
                      if (e.data.text && e.data.text.indexOf('#EXTM3U') !== -1) {
                        notifyDecrypted(e.data.url, e.data.text);
                        resolve(true);
                      } else {
                        resolve(false);
                      }
                    } else if (e.data && e.data.type === 'avs-decrypt-timeout') {
                      window.removeEventListener('message', onMessage);
                      console.log('AVS: iframe decrypt timeout');
                      resolve(false);
                    }
                  }
                  window.addEventListener('message', onMessage);
                  var iframe = document.createElement('iframe');
                  iframe.style.cssText = 'position:absolute;width:1px;height:1px;opacity:0;pointer-events:none;';
                  iframe.src = playerUrl;
                  (document.body || document.documentElement).appendChild(iframe);
                });
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
                    return tryGoogleApisCdn(json.link);
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
                console.log('AVS: callAjaxPlayer hash=' + hash + ' id=' + id);
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
                  .then(function (res) { console.log('AVS: ajax/player response: ' + res.status); return res.text(); })
                  .then(function (text) {
                    console.log('AVS: ajax/player body: ' + text.substring(0,200));
                    try {
                      return JSON.parse(text);
                    } catch (e) {
                      return null;
                    }
                  })
                  .then(handlePlayerResponse)
                  .catch(function (err) { console.log('AVS: ajax/player error: ' + err); return false; });
              }

              function parseFromPlayerData(pd) {
                console.log('AVS: parseFromPlayerData playTech=' + (pd && pd.playTech) + ' linkType=' + (pd && typeof pd.link));
                if (!pd) return Promise.resolve(false);

                if (pd.playTech === 'iframe' && typeof pd.link === 'string') {
                  if (pd.link.indexOf('googleapiscdn.com') !== -1) {
                    return tryGoogleApisCdn(pd.link);
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
                    function (value) {
                      notifyDone();
                      return value;
                    },
                    function () {
                      notifyDone();
                      return false;
                    }
                  );
              }

              start();
            })();
        """

        // Script injected into the player page HTML by proxyPlayerPage.
        // Waits for the site's own _avsDecryptM3u8 (defined by avs-loader.min.js),
        // fetches the m3u8 playlist, decrypts it, and posts the result to the parent.
        private const val PLAYER_CAPTURE_SCRIPT = """
<script>
(function(){
  var maxWait=200,count=0;
  var check=setInterval(function(){
    if(typeof window._avsDecryptM3u8==='function'){
      clearInterval(check);
      doCapture();
    }else if(++count>=maxWait){
      clearInterval(check);
      console.log('AVS-inject: timeout waiting for _avsDecryptM3u8');
      try{window.parent.postMessage({type:'avs-decrypt-timeout'},'*');}catch(x){}
    }
  },50);
  function doCapture(){
    console.log('AVS-inject: _avsDecryptM3u8 ready');
    var html=document.documentElement.innerHTML;
    var tokenMatch=html.match(/const\s+avsToken\s*=\s*"([^"]+)"/);
    if(!tokenMatch){console.log('AVS-inject: no token found');return;}
    var hashMatch=location.pathname.match(/\/player\/([0-9a-f]+)/);
    if(!hashMatch){console.log('AVS-inject: no hash found');return;}
    var m3u8Path='/playlist/'+hashMatch[1]+'/playlist.m3u8?token='+encodeURIComponent(tokenMatch[1]);
    console.log('AVS-inject: fetching playlist');
    fetch(m3u8Path).then(function(r){
      var headers={};
      r.headers.forEach(function(v,k){headers[k.toLowerCase()]=v;});
      return r.text().then(function(t){return{text:t,headers:headers};});
    }).then(function(res){
      console.log('AVS-inject: m3u8 fetched, size='+res.text.length+', has-envelope='+!!res.headers['x-envelope']);
      return window._avsDecryptM3u8(res.text,res.headers);
    }).then(function(decrypted){
      console.log('AVS-inject: decrypted, len='+(decrypted?decrypted.length:0));
      var masterUrl=location.origin+m3u8Path;
      window.parent.postMessage({type:'avs-decrypted-m3u8',url:masterUrl,text:decrypted},'*');
    }).catch(function(e){
      console.log('AVS-inject: error: '+e);
      try{window.parent.postMessage({type:'avs-decrypt-timeout'},'*');}catch(x){}
    });
  }
})();
</script>
"""
    }
}
