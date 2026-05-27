package eu.kanade.tachiyomi.animeextension.vi.animevietsub

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object AnimeVietsubFilters {

    data class FilterOption(
        val name: String,
        val path: String?,
        val paged: Boolean,
    )

    data class FilterGroup(
        val title: String,
        val options: List<FilterOption>,
    )

    fun buildFilterList(groups: List<FilterGroup>): AnimeFilterList {
        if (groups.isEmpty()) {
            return AnimeFilterList(
                AnimeFilter.Header("Nhấn \"Đặt lại\" để làm mới bộ lọc"),
            )
        }

        val filters = groups.map { group ->
            PathFilter(group.title, group.options)
        }
        return AnimeFilterList(filters)
    }

    fun parseFilter(filters: AnimeFilterList): FilterOption? {
        if (filters.isEmpty()) return null
        return filters.filterIsInstance<PathFilter>()
            .firstNotNullOfOrNull { it.selected() }
    }

    private class PathFilter(
        name: String,
        private val options: List<FilterOption>,
    ) : AnimeFilter.Select<String>(
        name,
        arrayOf("Tất cả") + options.map { it.name }.toTypedArray(),
    ) {
        fun selected(): FilterOption? = if (state == 0) null else options[state - 1]
    }
}
