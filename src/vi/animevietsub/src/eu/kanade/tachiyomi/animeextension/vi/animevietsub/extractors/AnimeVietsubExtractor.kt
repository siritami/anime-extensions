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

    private class JsBridge(private val latch: CountDownLatch) {
        @Volatile var decryptedMaster: String? = null

        @Volatile var decryptedMasterUrl: String? = null
        private val directM3u8Urls = linkedSetOf<String>()

        @JavascriptInterface
        fun onDecrypted(masterUrl: String, playlistText: String) {
            Log.d(TAG, "onDecrypted: url=$masterUrl, text=${playlistText.take(200)}")
            decryptedMasterUrl = masterUrl
            decryptedMaster = playlistText
            latch.countDown()
        }

        @JavascriptInterface
        fun onDirectM3u8(url: String) {
            Log.d(TAG, "onDirectM3u8: $url")
            synchronized(directM3u8Urls) {
                directM3u8Urls.add(url)
            }
            latch.countDown()
        }

        @JavascriptInterface
        fun onDone() {
            Log.d(TAG, "onDone: decrypted=${decryptedMaster != null}, directUrls=${directM3u8Urls.size}")
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

                    // Capture m3u8 URLs from network traffic (skip encrypted googleapiscdn ones)
                    if (M3U8_REGEX.containsMatchIn(url) && !url.contains("googleapiscdn.com")) {
                        Log.d(TAG, "Captured m3u8: $url")
                        synchronized(capturedM3u8) {
                            if (capturedM3u8.add(url)) latch.countDown()
                        }
                    }

                    // Proxy cross-origin fetch requests to googleapiscdn.com
                    // through OkHttp to bypass CORS and add permissive headers
                    if (url.contains("googleapiscdn.com") && !isNavigationRequest(request)) {
                        Log.d(TAG, "Proxying googleapiscdn: $url")
                        return proxyWithCors(url, request)
                    }

                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null) return
                    Log.d(TAG, "onPageFinished: $url")
                    view?.evaluateJavascript(DECRYPT_SCRIPT_TEMPLATE.replace("__BRIDGE__", JS_BRIDGE_NAME), null)
                }
            }

            webView?.loadUrl(episodeUrl, requestHeaders)
        }

        val latchResult = latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)
        Log.d(TAG, "Latch result: $latchResult (true=signaled, false=timeout)")

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
        Log.d(TAG, debugMsg)
        handler.post { Toast.makeText(context, "AVS: $debugMsg", Toast.LENGTH_LONG).show() }

        if (decryptedMaster != null && decryptedMasterUrl != null) {
            Log.d(TAG, "Decrypted master URL: $decryptedMasterUrl")
            Log.d(TAG, "Decrypted master text (first 300): ${decryptedMaster.take(300)}")
            val parsedFromDecrypted = parseDecryptedMasterPlaylist(decryptedMasterUrl, decryptedMaster)
            Log.d(TAG, "Parsed from decrypted: ${parsedFromDecrypted.size} videos")
            if (parsedFromDecrypted.isNotEmpty()) {
                return parsedFromDecrypted
            }
        }

        val candidateM3u8Urls = linkedSetOf<String>().apply {
            addAll(directUrls)
            addAll(captured)
        }.toList()
            .sortedByDescending { url -> MASTER_M3U8_HINT_REGEX.containsMatchIn(url) }

        Log.d(TAG, "Candidate m3u8 URLs: $candidateM3u8Urls")

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
        Log.d(TAG, "Proxy response: ${response.code} for $url")
        val body = response.body.bytes()
        Log.d(TAG, "Proxy body size: ${body.size} bytes")

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
        val responseHeaders = mutableMapOf<String, String>()
        response.headers.names().forEach { name ->
            response.header(name)?.let { responseHeaders[name] = it }
        }
        responseHeaders["Access-Control-Allow-Origin"] = "*"
        responseHeaders["Access-Control-Allow-Headers"] = "*"
        responseHeaders["Access-Control-Allow-Methods"] = "GET, POST, OPTIONS"

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
                  var iframe = document.createElement('iframe');
                  iframe.style.cssText = 'position:absolute;width:1px;height:1px;opacity:0;pointer-events:none;';
                  iframe.src = playerUrl;
                  (document.body || document.documentElement).appendChild(iframe);

                  setTimeout(function () {
                    fetchPlayerPageAndRunLoader(playerUrl, iframe)
                      .then(resolve)
                      .catch(function () { resolve(false); });
                  }, 1000);
                });
              }

              function fetchPlayerPageAndRunLoader(playerUrl, iframe) {
                console.log('AVS: fetchPlayerPage: ' + playerUrl);
                return fetch(playerUrl, { headers: { Referer: playerUrl } })
                  .then(function (res) {
                    console.log('AVS: playerPage response: ' + res.status);
                    if (!res.ok) throw new Error('HTTP ' + res.status);
                    return res.text();
                  })
                  .then(function (html) {
                    console.log('AVS: playerPage html length: ' + html.length);
                    try { iframe.remove(); } catch (e) {}
                    var tokenMatch = html.match(/const\\s+avsToken\\s*=\\s*\"([^\"]+)\"/);
                    var hashMatch = playerUrl.match(/\\/player\\/([0-9a-f]+)/);
                    console.log('AVS: tokenMatch=' + !!tokenMatch + ' hashMatch=' + !!hashMatch);
                    if (!tokenMatch || !hashMatch) return false;

                    var avsToken = tokenMatch[1];
                    console.log('AVS: token=' + avsToken.substring(0,20) + '... hash=' + hashMatch[1]);
                    var videoHash = hashMatch[1];
                    var baseUrl = playerUrl.match(/^(https?:\\/\\/[^/]+)/)[1];
                    var loaderUrlMatch = html.match(/<script[^>]+src=\"([^\"]*avs-loader\\.min\\.js[^\"]*)\"/);
                    var loaderUrl = loaderUrlMatch
                      ? loaderUrlMatch[1]
                      : 'https://storage.googleapiscdn.com/static/avs-loader.min.js?v=1.3.7';
                    if (loaderUrl.startsWith('/')) loaderUrl = baseUrl + loaderUrl;

                    console.log('AVS: loading avs-loader from: ' + loaderUrl);
                    return fetch(loaderUrl, { headers: { Referer: playerUrl } })
                      .then(function (r) { console.log('AVS: loader response: ' + r.status); return r.text(); })
                      .then(function (scriptText) {
                        console.log('AVS: loader script length: ' + scriptText.length);
                        var s = document.createElement('script');
                        s.textContent = scriptText;
                        document.body.appendChild(s);

                        console.log('AVS: AvsDecryptPlaylist available: ' + (typeof window.AvsDecryptPlaylist));
                        if (typeof window.AvsDecryptPlaylist !== 'function') {
                          return false;
                        }

                        var m3u8Url = baseUrl + '/playlist/' + videoHash + '/playlist.m3u8?token=' + encodeURIComponent(avsToken);
                        console.log('AVS: decrypting m3u8: ' + m3u8Url);
                        return window.AvsDecryptPlaylist(m3u8Url).then(function (decryptedM3u8) {
                          console.log('AVS: decrypt result length=' + (decryptedM3u8 ? decryptedM3u8.length : 0) + ' hasM3U8=' + (decryptedM3u8 && decryptedM3u8.indexOf('#EXTM3U') !== -1));
                          if (decryptedM3u8 && decryptedM3u8.indexOf('#EXTM3U') !== -1) {
                            notifyDecrypted(m3u8Url, decryptedM3u8);
                            return true;
                          } else {
                            console.log('AVS: decrypt FAILED, first 200: ' + (decryptedM3u8 ? decryptedM3u8.substring(0,200) : 'null'));
                            return false;
                          }
                        }).catch(function(err) { console.log('AVS: AvsDecryptPlaylist error: ' + err); return false; });
                      })
                      .catch(function (err) { console.log('AVS: loader fetch error: ' + err); return false; });
                  })
                  .catch(function () { return false; });
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
    }
}
