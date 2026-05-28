package eu.kanade.tachiyomi.animeextension.vi.hentaiz

import android.content.SharedPreferences
import android.text.Html
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.animeextension.vi.hentaiz.extractors.HentaiZExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import keiyoushi.utils.getPreferences
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

class HentaiZ :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "HentaiZ"

    private val defaultBaseUrl = "https://hentaiz.ac"

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

    private val hentaiZExtractor by lazy { HentaiZExtractor(client, headers) }

    init {
        Thread { fetchFilters() }.start()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .apply {
            build()["user-agent"]?.let { userAgent ->
                set("user-agent", removeWebViewToken(userAgent))
            }
        }

    private fun removeWebViewToken(userAgent: String): String = userAgent.replace(WEBVIEW_TOKEN_REGEX, ")")

    // ============================== Popular ===============================

    override fun popularAnimeRequest(page: Int): Request {
        val url = "$baseUrl/browse/__data.json".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "views_desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("animationType", "ALL")
            .addQueryParameter("contentRating", "ALL")
            .addQueryParameter("isTrailer", "ALL")
            .addQueryParameter("year", "ALL")
            .build()
            .toString()
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseBrowsePage(response)

    // ============================== Latest ================================

    override fun latestUpdatesRequest(page: Int): Request {
        val url = "$baseUrl/browse/__data.json".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "publishedAt_desc")
            .addQueryParameter("page", page.toString())
            .addQueryParameter("limit", "24")
            .addQueryParameter("animationType", "ALL")
            .addQueryParameter("contentRating", "ALL")
            .addQueryParameter("isTrailer", "ALL")
            .addQueryParameter("year", "ALL")
            .build()
            .toString()
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseBrowsePage(response)

    // ============================== Search ================================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val url = "$baseUrl/browse/__data.json".toHttpUrl().newBuilder().apply {
            if (query.isNotBlank()) addQueryParameter("q", query)
            addQueryParameter("page", page.toString())
            addQueryParameter("limit", "24")
            if (filters.isEmpty()) {
                addQueryParameter("sort", "publishedAt_desc")
                addQueryParameter("animationType", "ALL")
                addQueryParameter("contentRating", "ALL")
                addQueryParameter("isTrailer", "ALL")
                addQueryParameter("year", "ALL")
            } else {
                HentaiZFilters.applyFilters(filters, this)
            }
        }.build().toString()
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseBrowsePage(response)

    override fun getFilterList(): AnimeFilterList = HentaiZFilters.buildFilterList(genreCache, studioCache)

    // ============================== Details ===============================

    override fun animeDetailsRequest(anime: SAnime): Request {
        val slug = anime.url.removePrefix("/watch/")
        return GET("$baseUrl/watch/$slug/__data.json", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val data = parseSvelteData(response) ?: return SAnime.create()
        val episode = data.optJSONObject("episode") ?: return SAnime.create()

        return SAnime.create().apply {
            title = episode.optString("title", "")
            thumbnail_url = episode.optJSONObject("posterImage")
                ?.optString("filePath", "")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "$STORAGE_URL$it" }
                ?: episode.optJSONObject("backdropImage")
                    ?.optString("filePath", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "$STORAGE_URL$it" }

            author = episode.optJSONArray("studios")?.let { studios ->
                (0 until studios.length()).mapNotNull { i ->
                    studios.optJSONObject(i)?.optJSONObject("studio")?.optString("name")
                }.joinToString(", ")
            }

            genre = episode.optJSONArray("genres")?.let { genres ->
                (0 until genres.length()).mapNotNull { i ->
                    genres.optJSONObject(i)?.optJSONObject("genre")?.optString("name")
                }.joinToString(", ")
            }

            description = episode.optString("description", "")
                .let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString() }
                .trim()

            status = SAnime.COMPLETED
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListRequest(anime: SAnime): Request {
        val slug = anime.url.removePrefix("/watch/")
        return GET("$baseUrl/watch/$slug/__data.json", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val data = parseSvelteData(response) ?: return emptyList()
        val episode = data.optJSONObject("episode") ?: return emptyList()
        val seriesTitle = episode.optString("title", "")
        val currentSlug = response.request.url.pathSegments.getOrNull(1) ?: ""
        val baseSlug = currentSlug.replace(TRAILING_NUM_REGEX, "")

        if (seriesTitle.isBlank() || baseSlug.isEmpty()) {
            return listOf(createFallbackEpisode(currentSlug))
        }

        val seriesEpisodes = try {
            val searchUrl = "$baseUrl/browse/__data.json".toHttpUrl().newBuilder()
                .addQueryParameter("q", seriesTitle)
                .addQueryParameter("sort", "publishedAt_desc")
                .addQueryParameter("page", "1")
                .addQueryParameter("limit", "24")
                .addQueryParameter("animationType", "ALL")
                .addQueryParameter("contentRating", "ALL")
                .addQueryParameter("isTrailer", "ALL")
                .addQueryParameter("year", "ALL")
                .build()
                .toString()

            val searchResponse = client.newCall(GET(searchUrl, headers)).execute()
            val rawText = searchResponse.body.string()

            val slugRegex = Regex(""""(${Regex.escape(baseSlug)}-(\d+))"""")
            slugRegex.findAll(rawText)
                .map { it.groupValues[1] to it.groupValues[2].toInt() }
                .distinctBy { it.first }
                .map { (slug, epNum) ->
                    SEpisode.create().apply {
                        url = "/watch/$slug"
                        name = "Tập $epNum"
                        episode_number = epNum.toFloat()
                    }
                }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }

        if (seriesEpisodes.isEmpty()) {
            return listOf(createFallbackEpisode(currentSlug))
        }

        return seriesEpisodes.sortedByDescending { it.episode_number }
    }

    private fun createFallbackEpisode(slug: String): SEpisode {
        val epNum = TRAILING_NUM_REGEX.find(slug)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        return SEpisode.create().apply {
            url = "/watch/$slug"
            name = "Tập $epNum"
            episode_number = epNum.toFloat()
        }
    }

    // ============================== Video =================================

    override fun videoListRequest(episode: SEpisode): Request {
        val slug = episode.url.removePrefix("/watch/")
        return GET("$baseUrl/watch/$slug/__data.json", headers)
    }

    override fun videoListParse(response: Response): List<Video> {
        val data = parseSvelteData(response) ?: return emptyList()
        val episode = data.optJSONObject("episode") ?: return emptyList()
        val embedUrl = episode.optString("embedUrl", "").ifEmpty { return emptyList() }
        return hentaiZExtractor.videosFromEmbedUrl(embedUrl)
    }

    // ============================== Helpers ================================

    private fun parseBrowsePage(response: Response): AnimesPage {
        val requestUrl = response.request.url
        val data = parseSvelteData(response) ?: return AnimesPage(emptyList(), false)

        val episodes = data.optJSONArray("episodes") ?: return AnimesPage(emptyList(), false)
        val totalPages = data.optInt("totalPages", 1)
        val currentPage = requestUrl.queryParameter("page")?.toIntOrNull() ?: 1

        val animeList = (0 until episodes.length()).mapNotNull { i ->
            val ep = episodes.optJSONObject(i) ?: return@mapNotNull null
            val slug = ep.optString("slug", "").ifEmpty { return@mapNotNull null }
            val title = ep.optString("title", "").ifEmpty { return@mapNotNull null }

            SAnime.create().apply {
                url = "/watch/$slug"
                this.title = title
                thumbnail_url = ep.optJSONObject("backdropImage")
                    ?.optString("filePath", "")
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { "$STORAGE_URL$it" }
                    ?: ep.optJSONObject("posterImage")
                        ?.optString("filePath", "")
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { "$STORAGE_URL$it" }
            }
        }

        return AnimesPage(animeList, currentPage < totalPages)
    }

    private fun parseSvelteData(response: Response): JSONObject? {
        val json = JSONObject(response.body.string())
        val nodes = json.optJSONArray("nodes") ?: return null
        // Try nodes from last to first to find the page data node
        for (i in nodes.length() - 1 downTo 0) {
            val node = nodes.optJSONObject(i) ?: continue
            if (node.optString("type") == "error") return null
            val dataArr = node.optJSONArray("data") ?: continue
            val result = decodeSvelteData(dataArr)
            if (result != null) return result
        }
        return null
    }

    private fun decodeSvelteData(data: JSONArray): JSONObject? {
        val cache = HashMap<Int, Any?>()

        fun resolve(idx: Int): Any? {
            if (cache.containsKey(idx)) return cache[idx]
            if (idx < 0 || idx >= data.length()) return null

            val entry = data.opt(idx)
            when {
                entry == null || entry == JSONObject.NULL -> {
                    cache[idx] = null
                    return null
                }
                entry is String || entry is Number || entry is Boolean -> {
                    cache[idx] = entry
                    return entry
                }
                entry is JSONArray -> {
                    if (entry.length() == 2 && entry.opt(0) == "Date") {
                        val d = entry.opt(1)
                        cache[idx] = d
                        return d
                    }
                    val arr = JSONArray()
                    cache[idx] = arr
                    for (i in 0 until entry.length()) {
                        val refIdx = entry.optInt(i, -1)
                        arr.put(resolve(refIdx))
                    }
                    return arr
                }
                entry is JSONObject -> {
                    val obj = JSONObject()
                    cache[idx] = obj
                    val keys = entry.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val refIdx = entry.optInt(key, -1)
                        obj.put(key, resolve(refIdx))
                    }
                    return obj
                }
                else -> {
                    cache[idx] = entry
                    return entry
                }
            }
        }

        return resolve(0) as? JSONObject
    }

    // ============================== Filters ===============================

    private var genreCache: List<Pair<String, String>> = emptyList()
    private var studioCache: List<Pair<String, String>> = emptyList()

    private fun fetchFilters() {
        try {
            val genreResponse = client.newCall(GET("$baseUrl/genres/__data.json", headers)).execute()
            val genreData = parseSvelteData(genreResponse)
            genreData?.optJSONArray("genres")?.let { arr ->
                genreCache = (0 until arr.length()).mapNotNull { i ->
                    val g = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = g.optString("name", "").ifEmpty { return@mapNotNull null }
                    val slug = g.optString("slug", "").ifEmpty { return@mapNotNull null }
                    name to slug
                }
            }
        } catch (_: Exception) {}

        try {
            val studioResponse = client.newCall(GET("$baseUrl/studios/__data.json", headers)).execute()
            val studioData = parseSvelteData(studioResponse)
            studioData?.optJSONArray("allStudios")?.let { arr ->
                studioCache = (0 until arr.length()).mapNotNull { i ->
                    val s = arr.optJSONObject(i) ?: return@mapNotNull null
                    val name = s.optString("name", "").ifEmpty { return@mapNotNull null }
                    val slug = s.optString("slug", "").ifEmpty { return@mapNotNull null }
                    name to slug
                }
            }
        } catch (_: Exception) {}
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
        private const val STORAGE_URL = "https://storage.haiten.org"

        private const val DEFAULT_BASE_URL_PREF = "defaultBaseUrl"
        private const val BASE_URL_PREF = "overrideBaseUrl"
        private const val BASE_URL_PREF_TITLE = "Ghi đè URL cơ sở"
        private const val BASE_URL_PREF_SUMMARY =
            "Dành cho sử dụng tạm thời, cập nhật tiện ích sẽ xóa cài đặt."

        private val WEBVIEW_TOKEN_REGEX = Regex(""";\s*wv\)""")
        private val TRAILING_NUM_REGEX = Regex("""-?(\d+)$""")
    }
}
