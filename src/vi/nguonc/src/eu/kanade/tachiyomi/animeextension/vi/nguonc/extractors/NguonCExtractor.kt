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
        server.cachedPlaylist = m3u8Content

        val proxyUrl = "http://127.0.0.1:${server.port}/playlist.m3u8"
        return listOf(Video(proxyUrl, "Video", proxyUrl))
    }

    private fun ensureProxyRunning(): HlsProxyServer {
        proxy?.let { if (!it.isClosed) return it }
        val server = HlsProxyServer()
        server.start()
        proxy = server
        return server
    }

    private class HlsProxyServer {
        private var serverSocket: ServerSocket? = null

        @Volatile var cachedPlaylist: String? = null
        val port: Int get() = serverSocket?.localPort ?: 0
        val isClosed: Boolean get() = serverSocket?.isClosed != false

        fun start() {
            val ss = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
            serverSocket = ss
            Thread({
                while (!ss.isClosed) {
                    try {
                        val conn = ss.accept()
                        Thread { handleConnection(conn) }.start()
                    } catch (_: Exception) { break }
                }
            }, "NguonC-Proxy").start()
        }

        private fun handleConnection(socket: Socket) {
            try {
                socket.soTimeout = 30_000
                val input = socket.getInputStream().bufferedReader()
                input.readLine() ?: return
                while (input.readLine()?.isEmpty() == false) { /* consume headers */ }
                servePlaylist(socket.getOutputStream())
            } catch (_: Exception) {
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        }

        private fun servePlaylist(output: OutputStream) {
            val body = (cachedPlaylist ?: "#EXTM3U\n").toByteArray()
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/vnd.apple.mpegurl\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            output.write(header.toByteArray())
            output.write(body)
            output.flush()
        }
    }

    companion object {
        private val OBF_REGEX = Regex("""data-obf="([^"]+)""")
    }
}
