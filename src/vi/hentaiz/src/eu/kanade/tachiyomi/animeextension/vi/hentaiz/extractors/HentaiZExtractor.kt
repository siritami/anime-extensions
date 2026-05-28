package eu.kanade.tachiyomi.animeextension.vi.hentaiz.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class HentaiZExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
) {
    @Volatile private var proxyServer: PlaylistProxyServer? = null

    private fun ensureProxyRunning(): PlaylistProxyServer {
        proxyServer?.let { if (!it.isClosed) return it }
        val proxy = PlaylistProxyServer()
        proxy.start()
        proxyServer = proxy
        return proxy
    }

    fun videosFromEmbedUrl(embedUrl: String): List<Video> {
        val videoId = VIDEO_ID_REGEX.find(embedUrl)?.groupValues?.get(1)
            ?: return emptyList()
        return videosFromVideoId(videoId)
    }

    private fun videosFromVideoId(videoId: String): List<Video> {
        val videoData = decryptVideoData(videoId) ?: return emptyList()
        val proxy = ensureProxyRunning()
        val videos = mutableListOf<Video>()

        val masterLines = videoData.m3u8Master.lines()
        var variantIdx = 0

        for (i in masterLines.indices) {
            val line = masterLines[i]
            if (!line.startsWith("#EXT-X-STREAM-INF:")) continue

            val resolution = RESOLUTION_REGEX.find(line)?.groupValues?.get(1)
            val quality = resolution
                ?.substringAfter('x', "")
                ?.takeIf { it.isNotBlank() }
                ?.let { "${it}p" }
                ?: "Auto"

            val playlist = videoData.m3u8Playlists.getOrNull(variantIdx)
            if (playlist != null) {
                val folder = videoData.variantFolders.getOrNull(variantIdx)
                    ?: videoData.variantFolders.firstOrNull() ?: ""
                val baseSegUrl = "${videoData.segDomain}/${videoData.id}/$folder/"

                val rewrittenPlaylist = rewritePlaylist(playlist, baseSegUrl)
                val playlistKey = "v${variantIdx}_$quality"
                proxy.cachePlaylist(playlistKey, rewrittenPlaylist)

                val proxyUrl = "http://127.0.0.1:${proxy.port}/playlist/$playlistKey.m3u8"
                videos.add(Video(proxyUrl, "HentaiZ:$quality", proxyUrl))
            }
            variantIdx++
        }

        if (videos.isEmpty() && videoData.m3u8Playlists.isNotEmpty()) {
            val folder = videoData.variantFolders.firstOrNull() ?: ""
            val baseSegUrl = "${videoData.segDomain}/${videoData.id}/$folder/"
            val rewrittenPlaylist = rewritePlaylist(videoData.m3u8Playlists[0], baseSegUrl)
            proxy.cachePlaylist("default", rewrittenPlaylist)

            val proxyUrl = "http://127.0.0.1:${proxy.port}/playlist/default.m3u8"
            videos.add(Video(proxyUrl, "HentaiZ", proxyUrl))
        }

        return videos
    }

    private fun rewritePlaylist(playlist: String, baseSegUrl: String): String = playlist.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
            baseSegUrl + trimmed
        } else {
            line
        }
    }

    private fun decryptVideoData(videoId: String): VideoData? {
        val request = Request.Builder()
            .url("$MIMIX_API$videoId")
            .headers(headers)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) return null

        val rawText = response.body.string().trim()
        val colonIdx = rawText.indexOf(':')
        if (colonIdx < 0) return null

        val ivHex = rawText.substring(0, colonIdx)
        val ctHex = rawText.substring(colonIdx + 1)

        val iv = hexToBytes(ivHex)
        val ct = hexToBytes(ctHex)
        val key = MessageDigest.getInstance("SHA-256")
            .digest(videoId.toByteArray(Charsets.UTF_8))

        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val decrypted = cipher.doFinal(ct)
        val jsonStr = String(decrypted, Charsets.UTF_8)
        val json = JSONObject(jsonStr)

        val m3u8Obj = json.optJSONObject("defaultM3u8") ?: return null
        val master = m3u8Obj.optString("master", "").ifEmpty { return null }
        val playlistsArr = m3u8Obj.optJSONArray("playlists") ?: return null

        val playlists = (0 until playlistsArr.length()).map { playlistsArr.getString(it) }

        val segDomains = json.optJSONArray("segmentDomains")
        val segDomain = segDomains?.optString(0, "")?.ifEmpty { null }
            ?: json.optString("domain", "")

        val variantFolders = mutableListOf<String>()
        for (line in master.lines()) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.contains("playlist.m3u8")) {
                variantFolders.add(trimmed.replace("/playlist.m3u8", ""))
            }
        }

        return VideoData(
            m3u8Master = master,
            m3u8Playlists = playlists,
            variantFolders = variantFolders,
            segDomain = segDomain,
            id = json.optString("id", videoId),
        )
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
        }
        return data
    }

    private data class VideoData(
        val m3u8Master: String,
        val m3u8Playlists: List<String>,
        val variantFolders: List<String>,
        val segDomain: String,
        val id: String,
    )

    private class PlaylistProxyServer {
        private var serverSocket: ServerSocket? = null
        private val playlistCache = mutableMapOf<String, String>()

        val port: Int get() = serverSocket?.localPort ?: 0
        val isClosed: Boolean get() = serverSocket?.isClosed != false

        fun cachePlaylist(key: String, content: String) {
            synchronized(playlistCache) { playlistCache[key] = content }
        }

        fun start(): Int {
            if (serverSocket != null && !serverSocket!!.isClosed) return port
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
            }, "HTZ-ProxyAccept").start()
            return ss.localPort
        }

        private fun handleConnection(socket: Socket) {
            try {
                socket.soTimeout = 30_000
                val input = socket.getInputStream().bufferedReader()
                val requestLine = input.readLine() ?: return
                while (input.readLine()?.isEmpty() == false) { /* consume headers */ }

                val path = requestLine.split(" ").getOrNull(1) ?: return
                val output = socket.getOutputStream()

                if (path.startsWith("/playlist/")) {
                    servePlaylist(path, output)
                } else {
                    writeHttp(output, 404, "text/plain", "Not Found".toByteArray())
                }
            } catch (_: Exception) {
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {}
            }
        }

        private fun servePlaylist(path: String, output: OutputStream) {
            val key = path.removePrefix("/playlist/").removeSuffix(".m3u8")
            val content = synchronized(playlistCache) { playlistCache[key] }
            if (content != null) {
                writeHttp(output, 200, "application/vnd.apple.mpegurl", content.toByteArray())
            } else {
                writeHttp(output, 404, "text/plain", "Playlist not found".toByteArray())
            }
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
    }

    companion object {
        private const val MIMIX_API = "https://x.mimix.cc/watch/"
        private val VIDEO_ID_REGEX = Regex("""[?&]v=([a-f0-9-]+)""", RegexOption.IGNORE_CASE)
        private val RESOLUTION_REGEX = Regex("""RESOLUTION=\d+x(\d+)""", RegexOption.IGNORE_CASE)
    }
}
