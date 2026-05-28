package eu.kanade.tachiyomi.animeextension.vi.nguonc.extractors

import android.util.Base64
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.json.JSONObject

class NguonCExtractor(private val client: OkHttpClient) {

    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun videosFromHtml(html: String, embedUrl: String): List<Video> {
        val obfEncoded = OBF_REGEX.find(html)?.groupValues?.get(1)
            ?: return emptyList()

        val decoded = String(Base64.decode(obfEncoded, Base64.DEFAULT))
        val json = JSONObject(decoded)
        val sUb = json.getString("sUb")

        val host = embedUrl.toHttpUrl().host
        val m3u8Url = "https://$host/$sUb.m3u8"

        val videoHeaders = Headers.headersOf(
            "Referer",
            embedUrl,
            "Origin",
            "https://$host",
        )

        return playlistUtils.extractFromHls(
            m3u8Url,
            referer = embedUrl,
            videoHeaders = videoHeaders,
            masterHeaders = videoHeaders,
        )
    }

    companion object {
        private val OBF_REGEX = Regex("""data-obf="([^"]+)""")
    }
}
