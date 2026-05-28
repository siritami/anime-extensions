package eu.kanade.tachiyomi.animeextension.vi.vlxx.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.bodyString
import okhttp3.Headers
import okhttp3.OkHttpClient

class VLXXExtractor(private val client: OkHttpClient, private val headers: Headers) {

    fun videosFromEmbedUrl(embedUrl: String, serverName: String): List<Video> {
        val response = client.newCall(GET(embedUrl, headers)).execute()
        val body = response.bodyString()

        val fileUrl = FILE_REGEX.find(body)?.groupValues?.get(1) ?: return emptyList()

        return listOf(
            Video(
                url = fileUrl,
                quality = serverName,
                videoUrl = fileUrl,
            ),
        )
    }

    companion object {
        private val FILE_REGEX = Regex(""""file"\s*:\s*"([^"]+)"""")
    }
}
