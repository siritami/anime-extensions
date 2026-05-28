package eu.kanade.tachiyomi.animeextension.vi.hentaiz

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import okhttp3.HttpUrl

object HentaiZFilters {

    open class SelectFilter(
        name: String,
        private val options: List<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected get() = options[state].second
    }

    class CheckboxFilter(name: String, val value: String) : AnimeFilter.CheckBox(name, false)

    open class CheckboxGroup(name: String, val filters: List<CheckboxFilter>) : AnimeFilter.Group<CheckboxFilter>(name, filters) {
        val checked get() = filters.filter { it.state }.map { it.value }
    }

    class SortFilter :
        SelectFilter(
            "Sắp xếp",
            listOf(
                "Mới nhất" to "publishedAt_desc",
                "Xem nhiều" to "views_desc",
                "Tên A-Z" to "title_asc",
            ),
        )

    class AnimationTypeFilter :
        SelectFilter(
            "Loại phim",
            listOf(
                "Tất cả" to "ALL",
                "Hentai 2D" to "TWO_D",
                "Hentai 3D" to "THREE_D",
                "Hentai Motion" to "MOTION",
            ),
        )

    class ContentRatingFilter :
        SelectFilter(
            "Kiểm duyệt",
            listOf(
                "Tất cả" to "ALL",
                "Có che" to "CENSORED",
                "Không che" to "UNCENSORED",
            ),
        )

    class ContentTypeFilter :
        SelectFilter(
            "Loại nội dung",
            listOf(
                "Tất cả" to "ALL",
                "Phim đầy đủ" to "false",
                "Trailer" to "true",
            ),
        )

    class YearFilter :
        SelectFilter(
            "Năm",
            listOf("Tất cả" to "ALL") + (2026 downTo 1994).map { it.toString() to it.toString() },
        )

    class GenreFilter(genres: List<Pair<String, String>>) :
        CheckboxGroup(
            "Thể loại",
            genres.map { CheckboxFilter(it.first, it.second) },
        )

    class ExcludeGenreFilter(genres: List<Pair<String, String>>) :
        CheckboxGroup(
            "Loại trừ thể loại",
            genres.map { CheckboxFilter(it.first, it.second) },
        )

    class StudioFilter(studios: List<Pair<String, String>>) :
        SelectFilter(
            "Hãng phim",
            listOf("Tất cả" to "ALL") + studios,
        )

    fun buildFilterList(
        genres: List<Pair<String, String>>,
        studios: List<Pair<String, String>>,
    ): AnimeFilterList = AnimeFilterList(
        SortFilter(),
        AnimationTypeFilter(),
        ContentRatingFilter(),
        ContentTypeFilter(),
        YearFilter(),
        GenreFilter(genres),
        ExcludeGenreFilter(genres),
        StudioFilter(studios),
    )

    fun applyFilters(filters: AnimeFilterList, url: HttpUrl.Builder) {
        filters.forEach { filter ->
            when (filter) {
                is SortFilter -> url.addQueryParameter("sort", filter.selected)
                is AnimationTypeFilter -> url.addQueryParameter("animationType", filter.selected)
                is ContentRatingFilter -> url.addQueryParameter("contentRating", filter.selected)
                is ContentTypeFilter -> url.addQueryParameter("isTrailer", filter.selected)
                is YearFilter -> url.addQueryParameter("year", filter.selected)
                is GenreFilter -> {
                    val checked = filter.checked
                    if (checked.isNotEmpty()) {
                        url.addQueryParameter("genres", "," + checked.joinToString(","))
                    }
                }
                is ExcludeGenreFilter -> {
                    val checked = filter.checked
                    if (checked.isNotEmpty()) {
                        url.addQueryParameter("excludeGenres", "," + checked.joinToString(","))
                    }
                }
                is StudioFilter -> {
                    val value = filter.selected
                    if (value != "ALL") {
                        url.addQueryParameter("studios", ",$value")
                    }
                }
                else -> {}
            }
        }
    }
}
