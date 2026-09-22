package com.magnetsearch.data.model

import kotlinx.serialization.Serializable

@Serializable
data class MagnetResult(
    val title: String,
    val magnet: String,        // magnet:?xt=urn:btih:...
    val size: String = "",     // 人类可读大小 "1.2 GB"
    val seeders: Int = 0,
    val leechers: Int = 0,
    val source: String,        // 来源："Pirate Bay", "YTS", ...
    val category: String = "", // "movie" / "tv"
    val quality: String = extractQuality(title)  // 从标题自动提取分辨率
) {
    /** 归一化大小用于排序（转成 MB）。 */
    val sizeMB: Double by lazy { parseSizeMB(size) }

    companion object {
        /** 从标题提取分辨率，如 "[1080p]", "2160p", "HD"。 */
        fun extractQuality(title: String): String {
            val t = title.uppercase()
            return when {
                "2160P" in t || "4K" in t -> "4K"
                "1080P" in t || "FHD" in t -> "1080p"
                "720P" in t || "HD" in t -> "720p"
                else -> "其他"
            }
        }

        private val sizeRegex = Regex("""([\d.]+)\s*([KMGT])?[Bb]""")

        /** 解析人类可读大小为 MB。 */
        fun parseSizeMB(s: String): Double {
            val m = sizeRegex.find(s) ?: return 0.0
            val num = m.groupValues[1].toDoubleOrNull() ?: return 0.0
            val unit = m.groupValues.getOrNull(2)?.uppercase() ?: "M"
            return when (unit) {
                "K" -> num / 1024.0
                "M" -> num
                "G" -> num * 1024.0
                "T" -> num * 1024.0 * 1024.0
                else -> num
            }
        }
    }
}

@Serializable
enum class SearchSource(val displayName: String) {
    PIRATE_BAY("Pirate Bay"),
    YTS("YTS"),
    NYAA("Nyaa.si"),
    ONE337X("1337x"),
    BTBTT("BT之家"),
    ALL("全部");

    companion object {
        fun all(): List<SearchSource> = values().filter { it != ALL }
    }
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

/** 分辨率筛选选项。 */
enum class QualityFilter(val displayName: String) {
    ALL("全部"),
    Q4K("4K"),
    Q1080P("1080p"),
    Q720P("720p"),
    OTHER("其他");

    fun matches(result: MagnetResult): Boolean = when (this) {
        ALL -> true
        Q4K -> result.quality == "4K"
        Q1080P -> result.quality == "1080p"
        Q720P -> result.quality == "720p"
        OTHER -> result.quality == "其他"
    }
}

/** 排序选项。 */
enum class SortBy(val displayName: String) {
    SEEDERS("做种数"),
    SIZE("大小"),
    LEECHERS("下载数");

    fun compare(a: MagnetResult, b: MagnetResult): Int = when (this) {
        SEEDERS -> b.seeders.compareTo(a.seeders)
        SIZE -> b.sizeMB.compareTo(a.sizeMB)
        LEECHERS -> b.leechers.compareTo(a.leechers)
    }
}

/** 磁力搜索结果包装：包含翻译信息供 UI 展示。 */
data class MagnetSearchResult(
    val results: List<MagnetResult>,
    val translatedQuery: String?   // 若输入是中文且成功翻译了英文，这里存翻译结果；否则为 null
)
