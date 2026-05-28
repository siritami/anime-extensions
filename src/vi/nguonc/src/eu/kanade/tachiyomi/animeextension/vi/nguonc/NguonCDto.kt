package eu.kanade.tachiyomi.animeextension.vi.nguonc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
class FilmResponse(
    val movie: FilmDetail? = null,
)

@Serializable
class FilmDetail(
    val name: String,
    val slug: String,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("thumb_url") val thumbUrl: String? = null,
    @SerialName("poster_url") val posterUrl: String? = null,
    val description: String? = null,
    val director: String? = null,
    val casts: String? = null,
    val category: Map<String, FilmCategory>? = null,
    val episodes: List<EpisodeServer>? = null,
)

@Serializable
class FilmCategory(
    val group: CategoryGroup? = null,
    val list: List<CategoryItem>? = null,
)

@Serializable
class CategoryGroup(
    val name: String? = null,
)

@Serializable
class CategoryItem(
    val name: String? = null,
)

@Serializable
class EpisodeServer(
    @SerialName("server_name") val serverName: String,
    val items: List<EpisodeItem>? = null,
)

@Serializable
class EpisodeItem(
    val name: String,
    val slug: String,
    val embed: String,
    val m3u8: String,
)
