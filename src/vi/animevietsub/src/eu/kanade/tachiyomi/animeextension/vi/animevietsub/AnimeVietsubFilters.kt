package eu.kanade.tachiyomi.animeextension.vi.animevietsub

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import keiyoushi.utils.firstInstanceOrNull

object AnimeVietsubFilters {

    private val EMPTY_OPTION = FilterOption("Tất cả", null, false)

    private val DANG_ANIME_OPTIONS = arrayOf(
        EMPTY_OPTION,
        FilterOption("TV/Series", "anime-bo", true),
        FilterOption("Movie/OVA", "anime-le", true),
        FilterOption("HH Trung Quốc", "hoat-hinh-trung-quoc", true),
        FilterOption("Anime Sắp Chiếu", "anime-sap-chieu", true),
        FilterOption("Anime Đang Chiếu", "danh-sach/list-dang-chieu", true),
        FilterOption("Anime Trọn Bộ", "danh-sach/list-tron-bo", true),
    )

    private val TOP_ANIME_OPTIONS = arrayOf(
        EMPTY_OPTION,
        FilterOption("Theo Ngày", "bang-xep-hang/day.html", false),
        FilterOption("Yêu Thích", "bang-xep-hang/voted.html", false),
        FilterOption("Theo Tháng", "bang-xep-hang/month.html", false),
        FilterOption("Theo Mùa", "bang-xep-hang/season.html", false),
        FilterOption("Theo Năm", "bang-xep-hang/year.html", false),
    )

    private val THE_LOAI_OPTIONS = arrayOf(
        EMPTY_OPTION,
        FilterOption("Action", "the-loai/hanh-dong", true),
        FilterOption("Adventure", "the-loai/phieu-luu", true),
        FilterOption("Boys Love", "the-loai/dong-tinh-nam", true),
        FilterOption("Cartoon", "the-loai/cartoon", true),
        FilterOption("Cổ Trang", "the-loai/co-trang", true),
        FilterOption("Comedy", "the-loai/hai-huoc", true),
        FilterOption("Dementia", "the-loai/dien-loan", true),
        FilterOption("Demons", "the-loai/demons", true),
        FilterOption("Drama", "the-loai/drama", true),
        FilterOption("Ecchi", "the-loai/ecchi", true),
        FilterOption("Fantasy", "the-loai/phep-thuat", true),
        FilterOption("Game", "the-loai/tro-choi", true),
        FilterOption("Harem", "the-loai/harem", true),
        FilterOption("Historical", "the-loai/lich-su", true),
        FilterOption("Horror", "the-loai/kinh-di", true),
        FilterOption("Josei", "the-loai/josei", true),
        FilterOption("Kids", "the-loai/tre-em", true),
        FilterOption("Live Action", "the-loai/live-action", true),
        FilterOption("Magic", "the-loai/ma-thuat", true),
        FilterOption("Martial Arts", "the-loai/martial-arts", true),
        FilterOption("Mecha", "the-loai/mecha", true),
        FilterOption("Military", "the-loai/quan-doi", true),
        FilterOption("Music", "the-loai/am-nhac", true),
        FilterOption("Mystery", "the-loai/mystery", true),
        FilterOption("Parody", "the-loai/parody", true),
        FilterOption("Police", "the-loai/police", true),
        FilterOption("Psychological", "the-loai/psychological", true),
        FilterOption("Romance", "the-loai/tinh-cam", true),
        FilterOption("Samurai", "the-loai/samurai", true),
        FilterOption("School", "the-loai/truong-hoc", true),
        FilterOption("Sci-Fi", "the-loai/sci-fi", true),
        FilterOption("Seinen", "the-loai/seinen", true),
        FilterOption("Shoujo", "the-loai/shoujo", true),
        FilterOption("Shoujo Ai", "the-loai/shoujo-ai", true),
        FilterOption("Shounen", "the-loai/shounen", true),
        FilterOption("Shounen Ai", "the-loai/shounen-ai", true),
        FilterOption("Slice of Life", "the-loai/doi-thuong", true),
        FilterOption("Space", "the-loai/space", true),
        FilterOption("Sports", "the-loai/the-thao", true),
        FilterOption("Super Power", "the-loai/super-power", true),
        FilterOption("Supernatural", "the-loai/sieu-nhien", true),
        FilterOption("Suspense", "the-loai/hoi-hop", true),
        FilterOption("Thriller", "the-loai/thriller", true),
        FilterOption("Tokusatsu", "the-loai/tokusatsu", true),
        FilterOption("Vampire", "the-loai/vampire", true),
        FilterOption("Yaoi", "the-loai/yaoi", true),
        FilterOption("Yuri", "the-loai/yuri", true),
    )

    private val SEASON_OPTIONS = arrayOf(
        EMPTY_OPTION,
        FilterOption("Mùa Đông 2026", "season/winter/2026", true),
        FilterOption("Mùa Xuân 2026", "season/spring/2026", true),
        FilterOption("Mùa Hạ 2026", "season/summer/2026", true),
        FilterOption("Mùa Thu 2026", "season/autumn/2026", true),
        FilterOption("Mùa Đông 2025", "season/winter/2025", true),
        FilterOption("Mùa Xuân 2025", "season/spring/2025", true),
        FilterOption("Mùa Hạ 2025", "season/summer/2025", true),
        FilterOption("Mùa Thu 2025", "season/autumn/2025", true),
        FilterOption("Mùa Đông 2024", "season/winter/2024", true),
        FilterOption("Mùa Xuân 2024", "season/spring/2024", true),
        FilterOption("Mùa Hạ 2024", "season/summer/2024", true),
        FilterOption("Mùa Thu 2024", "season/autumn/2024", true),
        FilterOption("Mùa Đông 2023", "season/winter/2023", true),
        FilterOption("Mùa Xuân 2023", "season/spring/2023", true),
        FilterOption("Mùa Hạ 2023", "season/summer/2023", true),
        FilterOption("Mùa Thu 2023", "season/autumn/2023", true),
        FilterOption("Mùa Đông 2022", "season/winter/2022", true),
        FilterOption("Mùa Xuân 2022", "season/spring/2022", true),
        FilterOption("Mùa Hạ 2022", "season/summer/2022", true),
        FilterOption("Mùa Thu 2022", "season/autumn/2022", true),
        FilterOption("Mùa Đông 2021", "season/winter/2021", true),
        FilterOption("Mùa Xuân 2021", "season/spring/2021", true),
        FilterOption("Mùa Hạ 2021", "season/summer/2021", true),
        FilterOption("Mùa Thu 2021", "season/autumn/2021", true),
        FilterOption("Mùa Đông 2020", "season/winter/2020", true),
        FilterOption("Mùa Xuân 2020", "season/spring/2020", true),
        FilterOption("Mùa Hạ 2020", "season/summer/2020", true),
        FilterOption("Mùa Thu 2020", "season/autumn/2020", true),
        FilterOption("Mùa Đông 2019", "season/winter/2019", true),
        FilterOption("Mùa Xuân 2019", "season/spring/2019", true),
        FilterOption("Mùa Hạ 2019", "season/summer/2019", true),
        FilterOption("Mùa Thu 2019", "season/autumn/2019", true),
        FilterOption("Mùa Đông 2018", "season/winter/2018", true),
        FilterOption("Mùa Xuân 2018", "season/spring/2018", true),
        FilterOption("Mùa Hạ 2018", "season/summer/2018", true),
        FilterOption("Mùa Thu 2018", "season/autumn/2018", true),
    )

    class DangAnimeFilter : PathFilter("Dạng Anime", DANG_ANIME_OPTIONS)

    class TopAnimeFilter : PathFilter("Top Anime", TOP_ANIME_OPTIONS)

    class TheLoaiFilter : PathFilter("Thể loại", THE_LOAI_OPTIONS)

    class SeasonFilter : PathFilter("Season", SEASON_OPTIONS)

    val FILTER_LIST get() = AnimeFilterList(
        AnimeFilter.Header("Lọc theo menu trên trang chủ AnimeVietsub."),
        DangAnimeFilter(),
        TopAnimeFilter(),
        TheLoaiFilter(),
        SeasonFilter(),
    )

    fun parseFilter(filters: AnimeFilterList): FilterOption? {
        if (filters.isEmpty()) return null

        return filters.firstInstanceOrNull<DangAnimeFilter>()?.selected()
            ?: filters.firstInstanceOrNull<TopAnimeFilter>()?.selected()
            ?: filters.firstInstanceOrNull<TheLoaiFilter>()?.selected()
            ?: filters.firstInstanceOrNull<SeasonFilter>()?.selected()
    }

    open class PathFilter(
        name: String,
        private val options: Array<FilterOption>,
    ) : AnimeFilter.Select<String>(
        name,
        options.map { it.name }.toTypedArray(),
    ) {
        fun selected(): FilterOption? = options[state].takeIf { it.path != null }
    }

    class FilterOption(
        val name: String,
        val path: String?,
        val paged: Boolean,
    )
}
