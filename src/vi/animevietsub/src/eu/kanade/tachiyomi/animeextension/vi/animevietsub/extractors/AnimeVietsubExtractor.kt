package eu.kanade.tachiyomi.animeextension.vi.animevietsub.extractors

import eu.kanade.tachiyomi.animesource.model.Video
import okhttp3.Headers
import okhttp3.OkHttpClient

class AnimeVietsubExtractor(
    private val client: OkHttpClient,
    private val headers: Headers,
    private val mode: String = MODE_PROXY,
) {
    private val proxyExtractor by lazy { AnimeVietsubExtractorProxy(client, headers) }
    private val decryptExtractor by lazy { AnimeVietsubExtractorDecrypt(client, headers) }

    fun videosFromEpisodeUrl(episodeUrl: String): List<Video> = when (mode) {
        MODE_DECRYPT -> decryptExtractor.videosFromEpisodeUrl(episodeUrl)
        else -> proxyExtractor.videosFromEpisodeUrl(episodeUrl)
    }

    companion object {
        const val MODE_PROXY = "proxy"
        const val MODE_DECRYPT = "decrypt"
    }
}
