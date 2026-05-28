package eu.kanade.tachiyomi.animeextension.vi.vlxx

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.vi.vlxx.extractors.VLXXExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import kotlinx.serialization.Serializable
import okhttp3.FormBody
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class VLXX :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "VLXX"

    private val defaultBaseUrl = "https://vlxx.moi"

    override val baseUrl get() = getPrefBaseUrl()

    override val lang = "vi"

    override val supportsLatest = true

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .apply {
            build()["user-agent"]?.let { userAgent ->
                set("user-agent", userAgent.replace(WEBVIEW_TOKEN_REGEX, ")"))
            }
        }

    private val preferences: SharedPreferences = getPreferences {
        getString(DEFAULT_BASE_URL_PREF, null).let { prefDefaultBaseUrl ->
            if (prefDefaultBaseUrl != defaultBaseUrl) {
                edit()
                    .putString(BASE_URL_PREF, defaultBaseUrl)
                    .putString(DEFAULT_BASE_URL_PREF, defaultBaseUrl)
                    .apply()
            }
        }
    }

    private val extractor by lazy { VLXXExtractor(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/phim-sex-hay/${if (page > 1) "$page/" else ""}", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET("$baseUrl/${if (page > 1) "new/$page/" else ""}", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = GET("$baseUrl/search/$query/$page/", headers)

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    override fun getFilterList(): AnimeFilterList = AnimeFilterList()

    // ============================== Details ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        val videoId = response.request.url.pathSegments.lastOrNull { it.isNotEmpty() }
        return SAnime.create().apply {
            title = document.selectFirst("h1")!!.text()
            thumbnail_url = videoId?.let { "$baseUrl/img2/$it.jpg" }
            description = document.selectFirst(".video-description")?.text()
            author = document.selectFirst(".actress-tag a")?.text()
            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val document = response.asJsoup()
        val servers = document.select(".video-server")
        if (servers.isEmpty()) return emptyList()

        return listOf(
            SEpisode.create().apply {
                url = response.request.url.encodedPath
                name = "Video"
                episode_number = 1f
            },
        )
    }

    // ============================== Video =================================

    override fun videoListParse(response: Response): List<Video> {
        val document = response.asJsoup()
        val servers = document.select(".video-server")

        return servers.flatMap { server ->
            val onclick = server.attr("onclick")
            val serverNum = SERVER_REGEX.find(onclick)?.groupValues?.get(1) ?: "1"
            val embedUrl = getEmbedUrl(document, serverNum) ?: return@flatMap emptyList()
            extractor.videosFromEmbedUrl(embedUrl, "Server #$serverNum")
        }
    }

    private fun getEmbedUrl(document: Document, serverNum: String): String? {
        val iframe = document.selectFirst("iframe[src*=embed]")
        if (iframe != null && serverNum == "1") {
            return iframe.absUrl("src")
        }

        val videoId = ID_REGEX.find(
            document.selectFirst(".video-server")?.attr("onclick").orEmpty(),
        )?.groupValues?.get(1) ?: return null

        val formBody = FormBody.Builder()
            .add("vlxx_server", "1")
            .add("id", videoId)
            .add("server", serverNum)
            .build()

        val ajaxResponse = client.newCall(
            POST("$baseUrl/ajax.php", headers, formBody),
        ).execute()

        val serverResponse = ajaxResponse.parseAs<ServerResponse>()
        val playerHtml = serverResponse.player ?: return null

        return IFRAME_SRC_REGEX.find(playerHtml)?.groupValues?.get(1)
    }

    // ============================== Parsing ===============================

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animes = document.select(".video-item").map(::parseAnimeFromElement)
        val hasNext = document.selectFirst(".pagenavi a[title=Next Page]") != null
        return AnimesPage(animes, hasNext)
    }

    private fun parseAnimeFromElement(element: Element): SAnime = SAnime.create().apply {
        val linkEl = element.selectFirst("a[href*=/video/]")!!
        title = linkEl.attr("title").ifEmpty { linkEl.text() }
        setUrlWithoutDomain(linkEl.absUrl("href"))
        thumbnail_url = element.selectFirst("img")?.absUrl("data-original")
    }

    // ============================== Settings ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = BASE_URL_PREF_TITLE
            summary = BASE_URL_PREF_SUMMARY
            setDefaultValue(defaultBaseUrl)
            dialogTitle = BASE_URL_PREF_TITLE
            dialogMessage = "Default: $defaultBaseUrl"
        }.let(screen::addPreference)
    }

    private fun getPrefBaseUrl(): String = preferences.getString(BASE_URL_PREF, defaultBaseUrl)!!

    companion object {
        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."
        private val WEBVIEW_TOKEN_REGEX = Regex("""\s*;\s*wv\)""")
        private val SERVER_REGEX = Regex("""server\((\d+)""")
        private val ID_REGEX = Regex("""server\(\d+,(\d+)\)""")
        private val IFRAME_SRC_REGEX = Regex("""src="([^"]+)""")
    }
}

@Serializable
class ServerResponse(
    val player: String? = null,
)
