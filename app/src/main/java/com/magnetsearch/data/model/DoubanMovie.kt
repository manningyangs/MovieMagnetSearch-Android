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
    var actors: List<String> = emptyList(),
    var summary: String = "",
    var fullSummary: String = "",
    var coverUrl: String = "",
    var doubanUrl: String = "",
    var genres: List<String> = emptyList(),
    var countries: List<String> = emptyList(),
    var duration: String = "",
    var comments: List<DoubanComment> = emptyList()  // 热门短评
)

@Serializable
data class DoubanComment(
    val author: String = "",
    val rating: Float = 0f,   // 0-5 星，0 表示未打分
    val content: String = "",
    val date: String = ""
)
