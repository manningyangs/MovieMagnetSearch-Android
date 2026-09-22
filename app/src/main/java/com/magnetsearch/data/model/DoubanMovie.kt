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
    val doubanId: String = "",
    val title: String = "",
    val originalTitle: String = "",
    val year: String = "",
    val rating: Float = 0f,
    val directors: List<String> = emptyList(),
    val actors: List<String> = emptyList(),
    val summary: String = "",
    val fullSummary: String = "",
    val coverUrl: String = "",
    val doubanUrl: String = "",
    val genres: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val duration: String = ""
)
