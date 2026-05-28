package eu.kanade.tachiyomi.animeextension.vi.vlxx.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class VLXXExtractor(private val client: OkHttpClient, private val headers: Headers) {

    @Volatile private var proxy: HlsProxyServer? = null

    fun videosFromEmbedUrl(embedUrl: String, serverName: String): List<Video> {
        val response = client.newCall(GET(embedUrl, headers)).execute()
        val body = response.bodyString()

        val fileUrl = FILE_REGEX.find(body)?.groupValues?.get(1) ?: return emptyList()

        val m3u8Response = client.newCall(GET(fileUrl, headers)).execute()
        val m3u8Content = m3u8Response.bodyString()
        val m3u8BaseUrl = fileUrl.substringBeforeLast("/") + "/"

        val segmentUrls = mutableListOf<String>()
        val lines = m3u8Content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                val url = when {
                    trimmed.startsWith("http") -> trimmed
                    trimmed.startsWith("/") -> {
                        val origin = m3u8BaseUrl.split("/").take(3).joinToString("/")
                        "$origin$trimmed"
                    }
                    else -> "$m3u8BaseUrl$trimmed"
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
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    appendLine("http://127.0.0.1:${server.port}/seg/$segIdx.ts")
                    segIdx++
                } else {
                    appendLine(line)
                }
            }
            if (!contains("#EXT-X-ENDLIST")) appendLine("#EXT-X-ENDLIST")
        }
        server.cachedPlaylist = rewritten

        val proxyUrl = "http://127.0.0.1:${server.port}/playlist.m3u8"
        return listOf(Video(proxyUrl, serverName, proxyUrl))
    }

    private fun ensureProxyRunning(): HlsProxyServer {
        proxy?.let { if (!it.isClosed) return it }
        val server = HlsProxyServer(this)
        server.start()
        proxy = server
        return server
    }

    internal fun fetchSegmentBytes(url: String): ByteArray? = try {
        val request = Request.Builder().url(url).headers(headers).build()
        client.newCall(request).execute().use { resp ->
            if (resp.isSuccessful) resp.body.bytes() else null
        }
    } catch (_: Exception) {
        null
    }

    private class HlsProxyServer(private val extractor: VLXXExtractor) {
        private var serverSocket: ServerSocket? = null

        @Volatile var segmentUrls: List<String> = emptyList()

        @Volatile var cachedPlaylist: String? = null
        val segmentCache = ConcurrentHashMap<Int, ByteArray>()

        val port: Int get() = serverSocket?.localPort ?: 0
        val isClosed: Boolean get() = serverSocket?.isClosed != false

        fun reset() {
            segmentCache.clear()
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
            }, "VLXX-Proxy").start()
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
                    val bytes = extractor.fetchSegmentBytes(segmentUrls[idx])
                    if (bytes == null) {
                        writeHttp(output, 502, "text/plain", "Fetch failed".toByteArray())
                        return
                    }
                    data = stripPngHeader(bytes)
                    segmentCache[idx] = data
                }

                writeHttp(output, 200, "video/mp2t", data)

                if (idx + 1 < segmentUrls.size && !segmentCache.containsKey(idx + 1)) {
                    Thread {
                        try {
                            val nextBytes = extractor.fetchSegmentBytes(segmentUrls[idx + 1])
                            if (nextBytes != null) {
                                segmentCache[idx + 1] = stripPngHeader(nextBytes)
                            }
                        } catch (_: Exception) {}
                    }.start()
                }

                if (idx > 3) segmentCache.remove(idx - 3)
            } catch (_: Exception) {
                writeHttp(output, 502, "text/plain", "Error".toByteArray())
            }
        }

        private fun stripPngHeader(bytes: ByteArray): ByteArray {
            if (bytes.size < 8 || !isPng(bytes)) return bytes
            for (i in 0 until minOf(bytes.size - 188, 500)) {
                if (bytes[i] == TS_SYNC_BYTE && i + 188 < bytes.size && bytes[i + 188] == TS_SYNC_BYTE) {
                    return bytes.copyOfRange(i, bytes.size)
                }
            }
            return bytes
        }

        private fun isPng(bytes: ByteArray): Boolean = bytes.size > 4 &&
            bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte()

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
        private val FILE_REGEX = Regex(""""file"\s*:\s*"([^"]+)"""")
        private const val TS_SYNC_BYTE = 0x47.toByte()
    }
}
