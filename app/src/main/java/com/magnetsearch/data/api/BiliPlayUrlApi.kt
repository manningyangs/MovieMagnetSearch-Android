package com.magnetsearch.data.api

import android.webkit.CookieManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * B站视频播放地址获取
 *
 * 流程：
 *   1. getVideoDetail(bvid) → 拿 cid（分P ID）
 *   2. getPlayUrl(bvid, cid, qn) → 拿 DASH 视频/音频流地址
 *
 * Cookie 来源：android.webkit.CookieManager → 同步到 OkHttp CookieJar
 * 无 cookie 时最高只能拿到 720P，有 SESSDATA 可到 1080P+
 */
object BiliPlayUrlApi {

    private const val TAG = "BiliPlayUrl"

    /** 桌面 Chrome UA —— 和 WebView 一致 */
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** CookieJar —— 自动把 WebView CookieManager 里的 cookie 同步到 OkHttp 请求 */
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val cm = CookieManager.getInstance()
            for (c in cookies) {
                cm.setCookie(url.toString(), c.toString())
            }
            cm.flush()
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val cm = CookieManager.getInstance()
            val raw = cm.getCookie(url.toString()) ?: return emptyList()
            return raw.split(";").mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isBlank()) return@mapNotNull null
                runCatching { Cookie.parse(url, trimmed) }.getOrNull()
            }
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .cookieJar(cookieJar)
        .build()

    /** 从 WebView CookieManager 提取 bilibili.com 的 cookie 字符串（用于 debug） */
    private fun dumpCookie(): String {
        val cm = CookieManager.getInstance()
        return try {
            val www = cm.getCookie("https://www.bilibili.com") ?: ""
            val api = cm.getCookie("https://api.bilibili.com") ?: ""
            "www.bilibili.com=[$www]\napi.bilibili.com=[$api]"
        } catch (e: Exception) {
            "cookie dump err: ${e.message}"
        }
    }

    /** 从 cookie 字符串里抽 SESSDATA */
    fun getSessdata(): String? {
        val cm = CookieManager.getInstance()
        val raw = cm.getCookie("https://www.bilibili.com") ?: ""
        return Regex("SESSDATA=([^;]+)").find(raw)?.groupValues?.get(1)
    }

    /** 判断是否有登录（有 SESSDATA 就算登录） */
    fun isLoggedIn(): Boolean = getSessdata()?.isNotBlank() == true

    /**
     * 获取视频详情 —— 主要为了拿 cid
     * https://api.bilibili.com/x/web-interface/view?bvid=BVxxx
     */
    suspend fun getVideoDetail(bvid: String): Result<BiliVideoDetail> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "getVideoDetail bvid=$bvid")
                val req = Request.Builder()
                    .url("https://api.bilibili.com/x/web-interface/view?bvid=$bvid")
                    .header("User-Agent", UA)
                    .header("Referer", "https://www.bilibili.com")
                    .build()

                client.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: return@use Result.failure(Exception("empty response"))
                    val json = JSONObject(body)
                    val code = json.optInt("code", -1)
                    if (code != 0) {
                        Log.e(TAG, "getVideoDetail failed code=$code msg=${json.optString("message")} body=$body")
                        return@use Result.failure(Exception("B站错误 code=$code"))
                    }
                    val data = json.getJSONObject("data")
                    val title = data.optString("title", "")

                    val pages = data.optJSONArray("pages")
                    val parts = mutableListOf<BiliPart>()
                    var firstPositiveCid = -1L
                    if (pages != null) {
                        for (i in 0 until pages.length()) {
                            val p = pages.getJSONObject(i)
                            val c = p.optLong("cid") // Long！B站新版 cid 超 Int 范围
                            parts += BiliPart(
                                page = p.optInt("page", i + 1),
                                cid = c,
                                part = p.optString("part", "P${i + 1}"),
                                duration = p.optInt("duration", 0)
                            )
                            if (c > 0 && firstPositiveCid == -1L) firstPositiveCid = c
                        }
                    }
                    // log 所有 cid 帮助排查
                    Log.d(TAG, "pages cids: ${parts.map { it.cid }}")

                    // 选 cid：主 cid > 0 用主的，否则用 pages 里第一个正的，都没有就 0
                    val mainCid = data.optLong("cid") // Long！
                    val cid = when {
                        mainCid > 0 -> mainCid
                        firstPositiveCid > 0 -> firstPositiveCid
                        parts.isNotEmpty() -> parts[0].cid
                        else -> mainCid
                    }
                    Log.d(TAG, "getVideoDetail ok: title=$title mainCid=$mainCid → useCid=$cid parts=${parts.size}")

                    if (cid <= 0) {
                        val keys = data.keys().asSequence().toList()
                        Log.e(TAG, "cid 无效！data keys=$keys full_data=${data.toString().take(500)}")
                    }

                    Result.success(BiliVideoDetail(bvid, cid, title, parts))
                }
            } catch (e: Exception) {
                Log.e(TAG, "getVideoDetail exception", e)
                Result.failure(e)
            }
        }

    /**
     * 获取播放地址 —— DASH 流
     *
     * 直接用老版 /x/player/playurl（无需 WBI 签名，稳定可靠）
     *
     * fnval=16 请求 DASH 格式（video + audio 分离）
     */
    suspend fun getPlayUrl(bvid: String, cid: Long, qn: Int = 80): Result<BiliPlayUrl> =
        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "getPlayUrl bvid=$bvid cid=$cid qn=$qn")
                Log.d(TAG, "cookie state: ${if (isLoggedIn()) "LOGGED IN" else "NO SESSDATA"}")
                tryPlayUrlLegacy(bvid, cid, qn)
            } catch (e: Exception) {
                Log.e(TAG, "getPlayUrl exception", e)
                Result.failure(e)
            }
        }

    /**
     * 老版：/x/player/playurl
     * 无需 WBI 签名，返回 DASH (data.dash) 或旧格式 (data.durl)
     */
    private fun tryPlayUrlLegacy(bvid: String, cid: Long, qn: Int): Result<BiliPlayUrl> {
        val req = Request.Builder()
            .url("https://api.bilibili.com/x/player/playurl?bvid=$bvid&cid=$cid&qn=$qn&fnval=16&fnver=0&fourk=1")
            .header("User-Agent", UA)
            .header("Referer", "https://www.bilibili.com/video/$bvid")
            .build()

        client.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: return Result.failure(Exception("empty response"))
            Log.d(TAG, "playurl legacy raw (first 300): ${body.take(300)}")
            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            if (code != 0) {
                val msg = json.optString("message", "")
                Log.e(TAG, "playurl legacy code=$code msg=$msg cookieDump=${dumpCookie()}")
                return Result.failure(Exception("播放地址获取失败 code=$code $msg"))
            }
            return parsePlayUrlData(json.getJSONObject("data"), qn)
        }
    }

    /** 解析 data 对象，统一处理 DASH / 非 DASH */
    private fun parsePlayUrlData(data: JSONObject, qn: Int): Result<BiliPlayUrl> {
        val acceptQn = data.optJSONArray("accept_quality")
        val availableQns = mutableListOf<Int>()
        if (acceptQn != null) {
            for (i in 0 until acceptQn.length()) availableQns.add(acceptQn.getInt(i))
        }

        // 新版 wbi/v2 的 DASH 在 data.dash 里，老版 playurl 也是 data.dash
        val dash = data.optJSONObject("dash")
        if (dash != null) {
            val videoArr = dash.optJSONArray("video")
            val audioArr = dash.optJSONArray("audio")

            var videoUrl = ""
            var videoCodec: String? = null
            if (videoArr != null) {
                // 先精确匹配 qn
                for (i in 0 until videoArr.length()) {
                    val v = videoArr.getJSONObject(i)
                    if (v.optInt("id") == qn) {
                        videoUrl = v.optString("baseUrl")
                        videoCodec = v.optString("codecs")
                        break
                    }
                }
                // 没匹配到 → 取最高 qn 的
                if (videoUrl.isEmpty() && videoArr.length() > 0) {
                    val last = videoArr.getJSONObject(videoArr.length() - 1)
                    videoUrl = last.optString("baseUrl")
                    videoCodec = last.optString("codecs")
                }
            }

            var audioUrl = ""
            var audioCodec: String? = null
            if (audioArr != null && audioArr.length() > 0) {
                val best = audioArr.getJSONObject(audioArr.length() - 1)
                audioUrl = best.optString("baseUrl")
                audioCodec = best.optString("codecs")
            }

            if (videoUrl.isEmpty()) {
                return Result.failure(Exception("视频流地址为空"))
            }

            Log.d(TAG, "DASH ok: videoUrl=${videoUrl.take(80)} audio=${audioUrl.isNotEmpty()} codec=$videoCodec")
            return Result.success(
                BiliPlayUrl(
                    videoUrl = videoUrl,
                    audioUrl = audioUrl.ifEmpty { null },
                    isDash = true,
                    availableQns = availableQns,
                    currentQn = qn,
                    videoCodec = videoCodec,
                    audioCodec = audioCodec,
                    backupVideoUrl = null
                )
            )
        }

        // 非 DASH —— 旧格式 durl
        val durl = data.optJSONArray("durl")
        if (durl != null && durl.length() > 0) {
            val first = durl.getJSONObject(0)
            val url = first.optString("url")
            val backUrl = first.optString("backup_url")
            return Result.success(
                BiliPlayUrl(
                    videoUrl = url,
                    audioUrl = null,
                    isDash = false,
                    availableQns = availableQns,
                    currentQn = qn,
                    videoCodec = null,
                    audioCodec = null,
                    backupVideoUrl = backUrl
                )
            )
        }

        return Result.failure(Exception("无可用播放流 (既无 dash 也无 durl)"))
    }

    /** qn → 清晰度中文名 */
    fun qnToLabel(qn: Int): String = when (qn) {
        276 -> "4K"
        120 -> "1080P60"
        116 -> "1080P高码率"
        112 -> "1080P高码率"
        80 -> "1080P"
        64 -> "720P"
        48 -> "720P高码率"
        32 -> "480P"
        16 -> "360P"
        6 -> "240P"
        else -> "${qn}P"
    }
}

/** 视频详情 */
data class BiliVideoDetail(
    val bvid: String,
    val cid: Long,
    val title: String,
    val parts: List<BiliPart>
)

/** 分P */
data class BiliPart(
    val page: Int,
    val cid: Long,
    val part: String,
    val duration: Int
)

/** 播放地址 */
data class BiliPlayUrl(
    val videoUrl: String,
    val audioUrl: String?,
    val isDash: Boolean,
    val availableQns: List<Int>,
    val currentQn: Int,
    val videoCodec: String?,
    val audioCodec: String?,
    val backupVideoUrl: String?
)
