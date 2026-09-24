package com.magnetsearch.data.repository

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.magnetsearch.data.model.BiliOwner
import com.magnetsearch.data.model.BiliStat
import com.magnetsearch.data.model.BiliVideo
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/** B站分区列表仓库 —— 双路降级策略：
 *
 *  1. **直连 API** (优先): api.bilibili.com/x/web-interface/ranking/v2
 *     → 免 wbi 免签名，但 B站 2025 年起对 GFW IP 返回 -352。
 *
 *  2. **HTML SSR 抓取** (降级): 爬 www.bilibili.com/v/{分区}/ 页面里的
 *     window.__INITIAL_STATE__ JSON —— 和正常浏览器访问完全一样，风控放行。
 *     用 OkHttp 带桌面 Chrome UA + Cookie 先预热再爬。
 *
 *  两个方案都走通后再考虑真正的 WebView + JS Bridge。
 */
object BiliRepository {

    private const val API_BASE = "https://api.bilibili.com"
    private const val WEB_BASE = "https://www.bilibili.com"

    // 桌面 Chrome UA —— 和真实浏览器一样
    private const val UA_WEB = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    // B站 APP UA —— ranking/v2 在某些网络环境下风控更松
    private const val UA_APP = "BiliDroid/12.0.0 (bb3101000e232e27; android) os/13 mobi_app/android"

    private val client = OkHttpClient.Builder().apply {
        connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        readTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
        followRedirects(true)
        dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> {
                val all = java.net.InetAddress.getAllByName(hostname).toList()
                val v4 = all.filterIsInstance<java.net.Inet4Address>()
                return if (v4.isNotEmpty()) v4 else all
            }
        })
    }.build()

    /** 分区名 → (tid, 路径片段) */
    data class BiliCat(val tid: Int, val path: String)

    fun nameToCat(name: String): BiliCat = when (name) {
        "热门" -> BiliCat(0, "popular/rank/all")
        "动画" -> BiliCat(1, "douga")
        "番剧" -> BiliCat(13, "anime")
        "国创" -> BiliCat(167, "guochuang")
        "音乐" -> BiliCat(3, "music")
        "舞蹈" -> BiliCat(129, "dance")
        "游戏" -> BiliCat(4, "game")
        "知识" -> BiliCat(36, "knowledge")
        "科技" -> BiliCat(188, "tech")
        "运动" -> BiliCat(234, "sports")
        "汽车" -> BiliCat(254, "car")
        "生活" -> BiliCat(160, "life")
        "美食" -> BiliCat(295, "food")
        "动物圈" -> BiliCat(217, "animal")
        "时尚" -> BiliCat(155, "fashion")
        "资讯" -> BiliCat(207, "information")
        "娱乐" -> BiliCat(5, "ent")
        else -> BiliCat(0, "popular/rank/all")
    }

    /** 拉视频列表 —— 先试 API，失败降级走 Web + SSR JSON 解析。 */
    suspend fun fetchVideos(category: String): Result<List<BiliVideo>> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // 方案 1: 直连 API
            val apiResult = tryApi(category)
            if (apiResult.isSuccess && apiResult.getOrNull()?.isNotEmpty() == true) {
                return@withContext apiResult
            }
            // 方案 2: Web SSR 降级
            tryWeb(category)
        }

    private fun tryApi(category: String): Result<List<BiliVideo>> = runCatching {
        val cat = nameToCat(category)
        val req = Request.Builder()
            .url("$API_BASE/x/web-interface/ranking/v2?rid=${cat.tid}&type=all")
            .header("User-Agent", UA_APP)
            .header("Referer", "$WEB_BASE/")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
        val body = resp.body?.string() ?: throw Exception("empty body")
        val root = JSONObject(body)
        if (root.optInt("code") != 0) {
            throw Exception("API code=${root.optInt("code")} msg=${root.optString("message")}")
        }
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: return@runCatching emptyList()
        parseJsonArray(list)
    }

    private fun tryWeb(category: String): Result<List<BiliVideo>> = runCatching {
        val cat = nameToCat(category)
        val url = if (cat.tid == 0) {
            "$WEB_BASE/v/${cat.path}"
        } else {
            "$WEB_BASE/v/${cat.path}/"
        }

        // 先请求 B站 首页，让服务器下发 buvid3 cookie（免风控）
        val warmup = Request.Builder().url(WEB_BASE)
            .header("User-Agent", UA_WEB).build()
        client.newCall(warmup).execute().close()

        val req = Request.Builder().url(url)
            .header("User-Agent", UA_WEB)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Referer", WEB_BASE)
            .build()

        val resp = client.newCall(req).execute()
        if (!resp.isSuccessful) throw Exception("HTTP ${resp.code}")
        val html = resp.body?.string() ?: throw Exception("empty body")

        // 检查是不是风控页
        if ("验证码" in html || "-352" in html || "risk-captcha" in html) {
            throw Exception("B站风控拦截")
        }

        // 从 __NEXT_DATA__ 或 __INITIAL_STATE__ 里提取视频 JSON
        // B站现代页面用 Next.js: <script id="__NEXT_DATA__" type="application/json">...</script>
        val nextDataMatch = Regex("""<script id="__NEXT_DATA__"[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)
        if (nextDataMatch != null) {
            val json = nextDataMatch.groupValues[1]
            return@runCatching parseNextData(json)
        }

        // 旧版 __INITIAL_STATE__
        val stateMatch = Regex("""window\.__INITIAL_STATE__\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)
            .find(html)
        if (stateMatch != null) {
            val json = stateMatch.groupValues[1]
            return@runCatching parseLegacyState(json)
        }

        // 兜底：找页面里所有 card 相关的 JSON
        throw Exception("页面里没找到视频数据 JSON（可能风控升级了）")
    }

    /** 解析 B站 Next.js SSR JSON。
     *  结构: props.pageProps.initialState.seasonArchives[*].item 或者
     *        props.pageProps.videoCardData[*].item —— 不同分区路径略有差异。 */
    private fun parseNextData(json: String): List<BiliVideo> {
        val root = JSONObject(json)
        val pageProps = root.optJSONObject("props")
            ?.optJSONObject("pageProps") ?: return emptyList()

        // 尝试多种路径
        val candidates = listOf(
            "initialState.seasonArchives",
            "initialState.videos",
            "videoCardData",
            "videoList",
            "rankList",
            "archives",
            "items",
        )

        for (path in candidates) {
            val arr = walkPath(pageProps, path) as? JSONArray ?: continue
            val result = parseJsonArray(arr)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    private fun parseLegacyState(json: String): List<BiliVideo> {
        val root = JSONObject(json)
        return walkPath(root, "rank.list")?.let {
            parseJsonArray(it as JSONArray)
        } ?: emptyList()
    }

    private fun walkPath(root: JSONObject, path: String): Any? {
        var cur: Any? = root
        for (seg in path.split('.')) {
            cur = when (cur) {
                is JSONObject -> cur.opt(seg)
                is JSONArray -> cur.optJSONObject(0)?.opt(seg)
                else -> return null
            }
            if (cur == null) return null
        }
        return cur
    }

    private fun parseJsonArray(arr: JSONArray): List<BiliVideo> {
        val result = ArrayList<BiliVideo>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            try {
                parseVideo(obj)?.let { result.add(it) }
            } catch (_: Exception) {}
        }
        return result
    }

    private fun parseVideo(obj: JSONObject): BiliVideo? {
        // 兼容两套字段名（API 返回 vs SSR 返回）
        val bvid = obj.optString("bvid").ifBlank {
            obj.optString("item")?.let { JSONObject(it).optString("bvid") }
        }.orEmpty()
        if (bvid.isBlank()) return null

        val title = obj.optString("title").ifBlank {
            obj.optString("item")?.let { JSONObject(it).optString("title") }
        }.orEmpty()
        val pic = obj.optString("pic").ifBlank {
            obj.optString("item")?.let { JSONObject(it).optString("pic") }
        }.orEmpty()
        val aid = obj.optLong("aid", 0L)
        val tid = obj.optInt("tid", 0)
        val tname = obj.optString("tname", "")
        val pubdate = obj.optLong("pubdate", 0L)
        val duration = obj.optInt("duration", 0)
        val desc = obj.optString("desc", "")
        val videos = obj.optInt("videos", 1)

        val owner = obj.optJSONObject("owner") ?: JSONObject()
        val biliOwner = BiliOwner(
            mid = owner.optLong("mid", 0),
            name = owner.optString("name", "匿名"),
            face = owner.optString("face", ""),
        )

        val stat = obj.optJSONObject("stat")
        val biliStat = if (stat != null) {
            BiliStat(
                view = stat.optLong("view", 0),
                danmaku = stat.optLong("danmaku", 0),
                reply = stat.optLong("reply", 0),
                favorite = stat.optLong("favorite", 0),
                coin = stat.optLong("coin", 0),
                share = stat.optLong("share", 0),
                like = stat.optLong("like", 0),
            )
        } else {
            BiliStat(view = obj.optLong("play", 0), danmaku = 0, reply = 0,
                favorite = 0, coin = 0, share = 0, like = 0)
        }

        return BiliVideo(
            bvid = bvid, aid = aid, title = title, pic = pic,
            tid = tid, tname = tname, pubdate = pubdate, duration = duration,
            desc = desc, owner = biliOwner, stat = biliStat, videos = videos,
        )
    }
}
