package com.magnetsearch.data.model

// B站视频列表 API 返回的单条视频（来自 /x/web-interface/ranking/v2 等免签名接口）
data class BiliVideo(
    val bvid: String,
    val aid: Long,
    val title: String,
    val pic: String,           // http://i2.hdslb.com/... 封面，需要改 https
    val tid: Int,              // 分区 id
    val tname: String,         // 分区名
    val pubdate: Long,         // unix 秒
    val duration: Int,         // 秒
    val desc: String,
    val owner: BiliOwner,
    val stat: BiliStat,
    val videos: Int = 1,       // 分 P 数
) {
    // 封面强制升 https（B站 API 返回 http，Android 默认可能拦截）
    val picHttps: String get() = pic.replaceFirst("http://", "https://")
    val url: String get() = "https://www.bilibili.com/video/$bvid"
}

data class BiliOwner(
    val mid: Long,
    val name: String,
    val face: String,
)

data class BiliStat(
    val view: Long,
    val danmaku: Long,
    val reply: Long,
    val favorite: Long,
    val coin: Long,
    val share: Long,
    val like: Long,
)

// API 通用包装
data class BiliApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?,
)

// 排行榜 data
data class BiliRankingData(
    val note: String,
    val list: List<BiliVideo>,
)
