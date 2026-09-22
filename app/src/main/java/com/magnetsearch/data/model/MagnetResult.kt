package com.magnetsearch.data.model

@Serializable
data class MagnetResult(
    val title: String,
    val magnet: String,        // magnet:?xt=urn:btih:...
    val size: String = "",     // 人类可读大小 "1.2 GB"
    val seeders: Int = 0,
    val leechers: Int = 0,
    val source: String,        // 来源："Pirate Bay", "YTS", ...
    val category: String = ""  // "movie" / "tv"
)

@Serializable
enum class SearchSource(val displayName: String) {
    PIRATE_BAY("Pirate Bay"),
    YTS("YTS"),
    NYAA("Nyaa.si"),
    ONE337X("1337x"),
    ALL("全部")
}

@Serializable
enum class MediaType(val displayName: String) {
    MOVIE("电影"),
    TV("电视剧/综艺");

    val pirateBayCategory: Int
        get() = when (this) {
            MOVIE -> 200
            TV -> 205
        }
}
