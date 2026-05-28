package eu.kanade.tachiyomi.animeextension.vi.nguonc.extractors

import android.util.Base64
import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject

class NguonCExtractor {

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
            "https://$host/",
        )

        return listOf(
            Video(m3u8Url, "Video", m3u8Url, headers = videoHeaders),
        )
    }

    companion object {
        private val OBF_REGEX = Regex("""data-obf="([^"]+)""")
    }
}
