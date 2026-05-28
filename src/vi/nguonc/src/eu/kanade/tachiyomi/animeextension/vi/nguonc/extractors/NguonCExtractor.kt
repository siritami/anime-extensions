package eu.kanade.tachiyomi.animeextension.vi.nguonc.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder

class NguonCExtractor(private val client: OkHttpClient) {

    @Volatile private var proxy: HlsProxyServer? = null

    fun videosFromHtml(html: String, embedUrl: String): List<Video> {
        val obfEncoded = OBF_REGEX.find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val decoded = String(Base64.decode(obfEncoded, Base64.DEFAULT))
        val json = JSONObject(decoded)
        val sUb = json.getString("sUb")

        val host = embedUrl.toHttpUrl().host
        val m3u8Url = "https://$host/$sUb.m3u8"

        val m3u8Content = client.newCall(GET(m3u8Url)).execute().body.string()

        val server = ensureProxyRunning()
        server.cachedPlaylist = rewriteForProxy(m3u8Content, server.port)

        val proxyUrl = "http://127.0.0.1:${server.port}/playlist.m3u8"
        return listOf(Video(proxyUrl, "Video", proxyUrl))
    }

    private fun rewriteForProxy(m3u8: String, port: Int): String =
        m3u8.lines().joinToString("\n") { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("http") && !trimmed.startsWith("#")) {
                "http://127.0.0.1:$port/seg?u=${URLEncoder.encode(trimmed, "UTF-8")}"
            } else {
                line
            }
        }

    private fun ensureProxyRunning(): HlsProxyServer {
        proxy?.let { if (!it.isClosed) return it }
        val server = HlsProxyServer(client)
        server.start()
        proxy = server
        return server
    }

    private class HlsProxyServer(private val httpClient: OkHttpClient) {
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

            val response = httpClient.newCall(GET(url)).execute()
            val bytes = response.body.bytes()

            val result = if (bytes.size > PNG_HEADER_SIZE && isPng(bytes)) {
                bytes.copyOfRange(PNG_HEADER_SIZE, bytes.size)
            } else {
                bytes
            }
            writeHttp(output, 200, "video/mp2t", result)
        }

        private fun writeHttp(output: OutputStream, code: Int, contentType: String, body: ByteArray) {
            val status = if (code == 200) "OK" else "Not Found"
            val header = "HTTP/1.1 $code $status\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
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
        private const val PNG_HEADER_SIZE = 127
        private val OBF_REGEX = Regex("""data-obf="([^"]+)""")
    }
}
