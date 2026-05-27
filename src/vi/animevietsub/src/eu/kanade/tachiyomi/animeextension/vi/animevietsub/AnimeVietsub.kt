package eu.kanade.tachiyomi.animeextension.vi.animevietsub

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.vi.animevietsub.extractors.AnimeVietsubExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AnimeVietsub :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "AnimeVietsub"

    private val defaultBaseUrl = "https://animevietsub.site"

    override val baseUrl get() = getPrefBaseUrl()

    override val lang = "vi"

    override val supportsLatest = true

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
    private val animeVietsubExtractor by lazy {
        AnimeVietsubExtractor(client, headers)
    }

    // Strip "wv" from User-Agent so Google login works in this source.
    // Google deny login when User-Agent contains the WebView token.
    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .apply {
            build()["user-agent"]?.let { userAgent ->
                set("user-agent", removeWebViewToken(userAgent))
            }
        }

    private fun removeWebViewToken(userAgent: String): String = userAgent.replace(WEBVIEW_TOKEN_REGEX, ")")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request = GET("$baseUrl/bang-xep-hang/season.html", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimePage(response, false)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request = GET(buildPagedUrl("anime-moi", page), headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimePage(response, true)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        if (query.isNotBlank()) {
            val url = baseUrl.toHttpUrl().newBuilder()
                .addPathSegment("tim-kiem")
                .addPathSegment(query)
                .addPathSegment("trang-$page.html")
                .build()
            return GET(url, headers)
        }

        val selectedFilter = AnimeVietsubFilters.parseFilter(filters)
        val selectedPath = selectedFilter?.path
        val url = when {
            selectedPath == null -> buildPagedUrl("anime-moi", page)
            selectedFilter.paged -> buildPagedUrl(selectedPath, page)
            else -> buildStaticUrl(selectedPath)
        }

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val paged = PAGE_NUMBER_REGEX.containsMatchIn(response.request.url.toString())
        return parseAnimePage(response, paged)
    }

    @Volatile private var cachedFilterGroups: List<AnimeVietsubFilters.FilterGroup>? = null
    private var filtersFetchAttempted = false

    override fun getFilterList(): AnimeFilterList {
        cachedFilterGroups?.let { return AnimeVietsubFilters.buildFilterList(it) }

        if (!filtersFetchAttempted) {
            filtersFetchAttempted = true
            kotlin.concurrent.thread {
                try {
                    cachedFilterGroups = fetchFilterGroups()
                } catch (_: Exception) {}
            }
        }

        return AnimeVietsubFilters.buildFilterList(emptyList())
    }

    private fun fetchFilterGroups(): List<AnimeVietsubFilters.FilterGroup> {
        val doc = client.newCall(GET(baseUrl, headers)).execute().asJsoup()
        val nav = doc.selectFirst("nav.Menu") ?: return emptyList()

        return nav.select("li.menu-item-has-children").map { li ->
            val title = li.selectFirst("> a")?.text()?.trim() ?: ""
            val options = li.select("ul.sub-menu li a").map { a ->
                val path = a.absUrl("href").toHttpUrl().encodedPath.trim('/')
                val paged = !path.endsWith(".html")
                AnimeVietsubFilters.FilterOption(a.text().trim(), path, paged)
            }
            AnimeVietsubFilters.FilterGroup(title, options)
        }
    }

    // ============================== Details ===============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()

        return SAnime.create().apply {
            title = document.selectFirst("h1.Title")!!.text()
            thumbnail_url = document.selectFirst(".Image img")?.absUrl("src")
                ?.ifEmpty { document.selectFirst(".Image img")?.absUrl("data-src") }
            description = document.selectFirst("div.Description")?.text()
            genre = document.select(".InfoList li:has(strong:contains(Thể loại:)) a").joinToString { it.text() }
            author = document.infoValue("Đạo diễn")

            val statusText = document.infoValue("Trạng thái")
            status = when {
                statusText?.contains("Phim đang chiếu", true) == true ||
                    statusText?.contains("Cập Nhật", true) == true -> SAnime.ONGOING
                statusText?.contains("Full", true) == true ||
                    statusText?.contains("Trọn bộ", true) == true -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    private fun Document.infoValue(label: String): String? {
        val item = selectFirst(".InfoList li:has(strong:contains($label))") ?: return null
        return item.text().substringAfter(":", "").ifBlank { null }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> {
        val detailsDocument = response.asJsoup()
        val latestEpisodeUrl = detailsDocument.selectFirst(".InfoList li.latest_eps a")?.absUrl("href")

        val fromEpisodePage = latestEpisodeUrl?.let {
            client.newCall(GET(it, headers)).execute().use { episodeResponse ->
                episodeResponse.asJsoup().select(".server-group .list-episode a.episode-link")
            }
        }.orEmpty().map { it.toEpisode() }

        if (fromEpisodePage.isNotEmpty()) {
            return fromEpisodePage
                .distinctBy { it.url }
                .toDisplayOrder()
        }

        return detailsDocument.select(".InfoList li.latest_eps a")
            .map { it.toEpisode() }
            .distinctBy { it.url }
            .toDisplayOrder()
    }

    private fun Element.toEpisode(): SEpisode {
        val label = text()
        val episodeUrl = absUrl("href")

        return SEpisode.create().apply {
            setUrlWithoutDomain(episodeUrl)
            name = label
            episode_number = EPISODE_NUMBER_REGEX.find(label)?.groupValues?.get(1)?.toFloatOrNull() ?: 0F
        }
    }

    private fun List<SEpisode>.toDisplayOrder(): List<SEpisode> {
        if (size < 2) return this

        val firstNumber = first().episode_number
        val lastNumber = last().episode_number

        return if (firstNumber < lastNumber) reversed() else this
    }

    // ============================== Pages =================================

    override fun videoListParse(response: Response): List<Video> {
        val episodeUrl = response.request.url.toString()
        response.close()
        return animeVietsubExtractor.videosFromEpisodeUrl(episodeUrl)
    }

    // ============================== Parsing ===============================

    private fun parseAnimePage(response: Response, paged: Boolean): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select("main .TPostMv:has(a[href]):has(.Title), main .TPost:has(a[href]):has(.Title)")
            .map { it.toAnime() }
            .ifEmpty {
                document.select("ul.bxh-movie-phimletv > li:has(.e-item a[href])")
                    .map { it.toRankingAnime() }
            }
            .distinctBy { it.url }

        val currentPage = PAGE_NUMBER_REGEX.find(response.request.url.toString())?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val hasNextPage = paged && document.select(".pagination a, .wp-pagenavi a")
            .any { it.text() == (currentPage + 1).toString() }

        return AnimesPage(animes, hasNextPage)
    }

    private fun Element.toAnime(): SAnime {
        val anchor = selectFirst("a[href]")!!

        return SAnime.create().apply {
            setUrlWithoutDomain(anchor.absUrl("href"))
            title = selectFirst("h2.Title, .Title")!!.text()
            thumbnail_url = selectFirst("img")?.absUrl("src")
                ?.ifEmpty { selectFirst("img")?.absUrl("data-src") }
        }
    }

    private fun Element.toRankingAnime(): SAnime {
        val anchor = selectFirst(".e-item a[href]")!!
        val titleAttr = anchor.attr("title")
            .removePrefix("Phim ")
            .substringBefore(" - ")
            .trim()

        return SAnime.create().apply {
            setUrlWithoutDomain(anchor.absUrl("href"))
            title = titleAttr
            thumbnail_url = selectFirst("img")?.absUrl("src")
                ?.ifEmpty { selectFirst("img")?.absUrl("data-src") }
        }
    }

    // ============================== Utilities =============================

    private fun buildPagedUrl(path: String, page: Int): HttpUrl = buildPathUrl(path).newBuilder().addPathSegment("trang-$page.html").build()

    private fun buildStaticUrl(path: String): HttpUrl = buildPathUrl(path)

    private fun buildPathUrl(path: String): HttpUrl {
        val builder = baseUrl.toHttpUrl().newBuilder()
        path.trim('/').split('/').filter { it.isNotEmpty() }.forEach(builder::addPathSegment)
        return builder.build()
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
        private const val BASE_URL_PREF_SUMMARY = "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."

        private val WEBVIEW_TOKEN_REGEX = Regex(""";\s*wv\)""")
        private val PAGE_NUMBER_REGEX = Regex("""/trang-(\d+)\.html""")
        private val EPISODE_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
    }
}
