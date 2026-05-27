package eu.kanade.tachiyomi.animeextension.vi.animevietsub.extractors

import android.annotation.SuppressLint
import android.app.Application
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import uy.kohesive.injekt.injectLazy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AnimeVietsubExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    private val context: Application by injectLazy()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    private class JsBridge(private val latch: CountDownLatch) {
        @Volatile var decryptedMaster: String? = null

        @Volatile var decryptedMasterUrl: String? = null
        private val directM3u8Urls = linkedSetOf<String>()

        @JavascriptInterface
        fun onDecrypted(masterUrl: String, playlistText: String) {
            decryptedMasterUrl = masterUrl
            decryptedMaster = playlistText
            latch.countDown()
        }

        @JavascriptInterface
        fun onDirectM3u8(url: String) {
            synchronized(directM3u8Urls) {
                directM3u8Urls.add(url)
            }
            latch.countDown()
        }

        @JavascriptInterface
        fun onDone() {
            if (decryptedMaster != null || directM3u8Urls.isNotEmpty()) {
                latch.countDown()
            }
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
            newView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val url = request?.url.toString()
                    if (M3U8_REGEX.containsMatchIn(url)) {
                        var hasNewUrl = false
                        synchronized(capturedM3u8) {
                            hasNewUrl = capturedM3u8.add(url)
                        }
                        if (hasNewUrl) {
                            latch.countDown()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url == null) return
                    view?.evaluateJavascript(DECRYPT_SCRIPT_TEMPLATE.replace("__BRIDGE__", JS_BRIDGE_NAME), null)
                }
            }

            webView?.loadUrl(episodeUrl, requestHeaders)
        }

        latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)

        handler.post {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }

        val decryptedMaster = jsBridge.decryptedMaster
        val decryptedMasterUrl = jsBridge.decryptedMasterUrl

        if (decryptedMaster != null && decryptedMasterUrl != null) {
            val parsedFromDecrypted = parseDecryptedMasterPlaylist(decryptedMasterUrl, decryptedMaster)
            if (parsedFromDecrypted.isNotEmpty()) {
                return parsedFromDecrypted
            }
        }

        val candidateM3u8Urls = linkedSetOf<String>().apply {
            addAll(jsBridge.directUrls())
            addAll(capturedM3u8)
        }.toList()
            .sortedByDescending { url -> MASTER_M3U8_HINT_REGEX.containsMatchIn(url) }

        if (candidateM3u8Urls.isEmpty()) return emptyList()

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
        if (streamInfos.isEmpty()) return emptyList()
        return streamInfos.flatMap { (quality, videoUrl) ->
            playlistUtils.extractFromHls(
                playlistUrl = videoUrl,
                referer = masterUrl,
                videoNameGen = { "AnimeVsub:$quality" },
            )
        }.distinctBy { it.videoUrl }
    }

    private fun normalizeUrl(baseUrl: String, candidate: String): String {
        if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate
        return baseUrl.toHttpUrl().resolve(candidate)?.toString() ?: candidate
    }

    companion object {
        private const val TIMEOUT_SEC: Long = 20
        private const val JS_BRIDGE_NAME = "AnimeVietsubBridge"
        private val M3U8_REGEX = Regex(""".*\.m3u8(\?.*)?$""", RegexOption.IGNORE_CASE)
        private val MASTER_M3U8_HINT_REGEX = Regex("""playlist\.m3u8|master\.m3u8""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)

        private const val DECRYPT_SCRIPT_TEMPLATE = """
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
                return fetch(playerUrl, { headers: { Referer: playerUrl } })
                  .then(function (res) {
                    if (!res.ok) throw new Error('HTTP ' + res.status);
                    return res.text();
                  })
                  .then(function (html) {
                    try { iframe.remove(); } catch (e) {}
                    var tokenMatch = html.match(/const\\s+avsToken\\s*=\\s*\"([^\"]+)\"/);
                    var hashMatch = playerUrl.match(/\\/player\\/([0-9a-f]+)/);
                    if (!tokenMatch || !hashMatch) return false;

                    var avsToken = tokenMatch[1];
                    var videoHash = hashMatch[1];
                    var baseUrl = playerUrl.match(/^(https?:\\/\\/[^/]+)/)[1];
                    var loaderUrlMatch = html.match(/<script[^>]+src=\"([^\"]*avs-loader\\.min\\.js[^\"]*)\"/);
                    var loaderUrl = loaderUrlMatch
                      ? loaderUrlMatch[1]
                      : 'https://storage.googleapiscdn.com/static/avs-loader.min.js?v=1.3.7';
                    if (loaderUrl.startsWith('/')) loaderUrl = baseUrl + loaderUrl;

                    return fetch(loaderUrl, { headers: { Referer: playerUrl } })
                      .then(function (r) { return r.text(); })
                      .then(function (scriptText) {
                        var s = document.createElement('script');
                        s.textContent = scriptText;
                        document.body.appendChild(s);

                        if (typeof window.AvsDecryptPlaylist !== 'function') {
                          return false;
                        }

                        var m3u8Url = baseUrl + '/playlist/' + videoHash + '/playlist.m3u8?token=' + encodeURIComponent(avsToken);
                        return window.AvsDecryptPlaylist(m3u8Url).then(function (decryptedM3u8) {
                          if (decryptedM3u8 && decryptedM3u8.indexOf('#EXTM3U') !== -1) {
                            notifyDecrypted(m3u8Url, decryptedM3u8);
                            return true;
                          } else {
                            return false;
                          }
                        });
                      })
                      .catch(function () { return false; });
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
                    try {
                      return JSON.parse(text);
                    } catch (e) {
                      return null;
                    }
                  })
                  .then(handlePlayerResponse)
                  .catch(function () { return false; });
              }

              function parseFromPlayerData(pd) {
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
