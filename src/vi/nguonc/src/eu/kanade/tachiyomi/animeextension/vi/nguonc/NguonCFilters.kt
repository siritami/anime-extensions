package eu.kanade.tachiyomi.animeextension.vi.nguonc

import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList

object NguonCFilters {

    open class SelectFilter(
        name: String,
        private val options: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(name, options.map { it.first }.toTypedArray()) {
        val selected get() = options[state].second
    }

    class SortFilter :
        SelectFilter(
            "Sắp xếp",
            arrayOf(
                "Cập nhật mới nhất" to "update",
                "Phim mới nhất" to "new",
                "Xem nhiều nhất" to "view",
            ),
        )

    class FormatFilter :
        SelectFilter(
            "Định dạng",
            arrayOf(
                "Tất cả" to "",
                "Phim bộ" to "3",
                "Phim lẻ" to "4",
                "TV shows" to "5",
                "Phim đang chiếu" to "163",
            ),
        )

    class GenreFilter :
        SelectFilter(
            "Thể loại",
            arrayOf(
                "Tất cả" to "",
                "Hành Động" to "7",
                "Phiêu Lưu" to "8",
                "Hoạt Hình" to "9",
                "Phim Hài" to "10",
                "Hình Sự" to "11",
                "Tài Liệu" to "12",
                "Chính Kịch" to "13",
                "Gia Đình" to "14",
                "Giả Tưởng" to "15",
                "Lịch Sử" to "16",
                "Kinh Dị" to "17",
                "Phim Nhạc" to "18",
                "Bí Ẩn" to "19",
                "Lãng Mạn" to "20",
                "Khoa Học Viễn Tưởng" to "21",
                "Gây Cấn" to "22",
                "Chiến Tranh" to "23",
                "Miền Tây" to "24",
                "Cổ Trang" to "142",
                "Tâm Lý" to "143",
                "Phim 18+" to "144",
                "Tình Cảm" to "155",
            ),
        )

    class YearFilter :
        SelectFilter(
            "Năm",
            arrayOf(
                "Tất cả" to "",
                "2026" to "166",
                "2025" to "164",
                "2024" to "79",
                "2023" to "46",
                "2022" to "45",
                "2021" to "44",
                "2020" to "43",
                "2019" to "42",
                "2018" to "41",
                "2017" to "40",
                "2016" to "39",
                "2015" to "38",
                "2014" to "37",
                "2013" to "36",
                "2012" to "35",
                "2011" to "34",
                "2010" to "33",
                "2009" to "32",
                "2008" to "31",
                "2007" to "30",
                "2006" to "29",
                "2005" to "28",
                "2004" to "27",
                "2003" to "26",
            ),
        )

    class CountryFilter :
        SelectFilter(
            "Quốc gia",
            arrayOf(
                "Tất cả" to "",
                "Âu Mỹ" to "48",
                "Anh" to "49",
                "Trung Quốc" to "50",
                "Indonesia" to "51",
                "Việt Nam" to "52",
                "Pháp" to "69",
                "Hồng Kông" to "71",
                "Hàn Quốc" to "72",
                "Nhật Bản" to "73",
                "Thái Lan" to "74",
                "Đài Loan" to "75",
                "Nga" to "76",
                "Hà Lan" to "77",
                "Philippines" to "95",
                "Ấn Độ" to "96",
                "Quốc gia khác" to "78",
            ),
        )

    val FILTER_LIST get() = AnimeFilterList(
        SortFilter(),
        FormatFilter(),
        GenreFilter(),
        YearFilter(),
        CountryFilter(),
    )
}
