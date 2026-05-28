package eu.kanade.tachiyomi.animeextension.vi.nguonc

import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.vi.nguonc.NguonCFilters.CountryFilter
import eu.kanade.tachiyomi.animeextension.vi.nguonc.NguonCFilters.FormatFilter
import eu.kanade.tachiyomi.animeextension.vi.nguonc.NguonCFilters.GenreFilter
import eu.kanade.tachiyomi.animeextension.vi.nguonc.NguonCFilters.SortFilter
import eu.kanade.tachiyomi.animeextension.vi.nguonc.NguonCFilters.YearFilter
import eu.kanade.tachiyomi.animeextension.vi.nguonc.extractors.NguonCExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.firstInstanceOrNull
import keiyoushi.utils.getPreferences
import keiyoushi.utils.parseAs
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response

class NguonC :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "NguonC"

    private val defaultBaseUrl = "https://phim.nguonc.com"

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

    private val extractor by lazy { NguonCExtractor(client, headers) }

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/danh-sach-phim".toHttpUrl().newBuilder()
            .addQueryParameter("sort_field", "view")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/danh-sach-phim".toHttpUrl().newBuilder()
            .addQueryParameter("sort_field", "update")
            .addQueryParameter("page", page.toString())
            .build()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimesPage(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val sort = filters.firstInstanceOrNull<SortFilter>()?.selected ?: "update"
        val format = filters.firstInstanceOrNull<FormatFilter>()?.selected ?: ""
        val genre = filters.firstInstanceOrNull<GenreFilter>()?.selected ?: ""
        val year = filters.firstInstanceOrNull<YearFilter>()?.selected ?: ""
        val country = filters.firstInstanceOrNull<CountryFilter>()?.selected ?: ""

        val path = if (query.isNotBlank()) "/tim-kiem" else "/danh-sach-phim"

        val url = "$baseUrl$path".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("keyword", query)
            addQueryParameter("sort_field", sort)
            if (format.isNotEmpty()) addQueryParameter("cats[1]", format)
            if (genre.isNotEmpty()) addQueryParameter("cats[6]", genre)
            if (year.isNotEmpty()) addQueryParameter("cats[25]", year)
            if (country.isNotEmpty()) addQueryParameter("cats[47]", country)
            addQueryParameter("page", page.toString())
        }.build()

        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimesPage(response)

    override fun getFilterList(): AnimeFilterList = NguonCFilters.FILTER_LIST

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("$baseUrl/api/film/$slug", headers)
    }

    override fun getAnimeUrl(anime: SAnime): String = "$baseUrl${anime.url}"

    override fun animeDetailsParse(response: Response): SAnime {
        val film = response.parseAs<FilmResponse>().movie
            ?: throw Exception("Film not found")

        return SAnime.create().apply {
            title = film.name
            thumbnail_url = film.posterUrl ?: film.thumbUrl
            description = film.description
            author = film.director?.takeIf { it != "Đang cập nhật" }
            genre = film.category?.values
                ?.firstOrNull { it.group?.name == "Thể loại" }
                ?.list?.mapNotNull { it.name }
                ?.joinToString()
            status = SAnime.UNKNOWN
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.substringAfterLast("/")
        return GET("$baseUrl/api/film/$slug", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val film = response.parseAs<FilmResponse>().movie
            ?: return emptyList()

        val episodes = mutableListOf<SEpisode>()

        film.episodes?.forEach { server ->
            server.items?.forEach { item ->
                episodes.add(
                    SEpisode.create().apply {
                        url = item.embed
                        name = "Tập ${item.name} - ${server.serverName}"
                        episode_number = item.name.toFloatOrNull() ?: 0f
                        scanlator = server.serverName
                    },
                )
            }
        }

        return episodes.reversed()
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request = GET(
        episode.url,
        headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build(),
    )

    override fun videoListParse(response: Response): List<Video> {
        val embedUrl = response.request.url.toString()
        return extractor.videosFromUrl(embedUrl)
    }

    override fun getEpisodeUrl(episode: SEpisode): String = episode.url

    // ============================== Parsing ===============================

    private fun parseAnimesPage(response: Response): AnimesPage {
        val document = response.asJsoup()

        val animes = document.select("table tbody tr").map { row ->
            SAnime.create().apply {
                val linkEl = row.selectFirst("td a.ajax-load")!!
                title = linkEl.selectFirst("h3")!!.text()
                setUrlWithoutDomain(linkEl.absUrl("href"))
                thumbnail_url = row.selectFirst("td img")?.absUrl("data-src")
            }
        }

        val hasNext = document.selectFirst("a[rel=next]") != null

        return AnimesPage(animes, hasNext)
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
    }
}
