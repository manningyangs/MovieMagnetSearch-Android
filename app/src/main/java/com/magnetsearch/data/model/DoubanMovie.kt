package com.magnetsearch.data.model

import kotlinx.serialization.Serializable

@Serializable
data class DoubanMovie(
    val doubanId: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val year: String = "",
    val rating: Float = 0f,
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val summary: String = "",
    val coverUrl: String = "",
    val doubanUrl: String = "",
    val rank: Int = 0
)

@Serializable
data class DoubanDetail(
    var doubanId: String = "",
    var title: String = "",
    var originalTitle: String = "",
    var year: String = "",
    var rating: Float = 0f,
    var directors: List<String> = emptyList(),
    var writers: List<String> = emptyList(),
    var actors: List<String> = emptyList(),
    var aliases: List<String> = emptyList(),       // 又名
    var languages: List<String> = emptyList(),
    var releaseDates: List<String> = emptyList(),  // 多个上映日期
    var imdbId: String = "",                       // 如 tt0111161
    var voteCount: Int = 0,                        // 评价人数
    var ratingDist: RatingDist = RatingDist(),     // 5星~1星 分布
    var summary: String = "",
    var fullSummary: String = "",
    var coverUrl: String = "",
    var doubanUrl: String = "",
    var genres: List<String> = emptyList(),
    var countries: List<String> = emptyList(),
    var duration: String = "",
    var comments: List<DoubanComment> = emptyList()
)

@Serializable
data class RatingDist(
    val star5: Float = 0f,  // 百分比 0~100
    val star4: Float = 0f,
    val star3: Float = 0f,
    val star2: Float = 0f,
    val star1: Float = 0f
)

@Serializable
data class DoubanComment(
    val author: String = "",
    val rating: Float = 0f,   // 0-5 星，0 表示未打分
    val content: String = "",
    val date: String = ""
)
