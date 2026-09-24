package com.magnetsearch.data.repository

import com.magnetsearch.data.model.BiliOwner
import com.magnetsearch.data.model.BiliStat
import com.magnetsearch.data.model.BiliVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** B站爬虫 —— 自建 OkHttp client，独立 CookieJar，首次请求前预热 cookie。
 *
 *  B站风控（-352）要求：必须先正常访问一次 B站（拿到 buvid3/buvid4 cookie），
 *  之后带 cookie 的 API 请求才会放行。wbi 签名是另一层，我们目前走的接口
 *  （ranking/v2、dynamic/recommend）**wbi 已免**，但 cookie 不能省。
 *
 *  视频播放直链仍需 wbi + sessdata，点击视频暂时跳外部。
 */
object BiliRepository {

    private const val API_BASE = "https://api.bilibili.com"
    // 关键：B站 APP UA 比 Web UA 风控松得多 —— ranking/v2 在 APP UA 下不需要 wbi + cookie
    private const val UA = "BiliDroid/12.0.0 (bb3101000e232e27; android) os/13 mobi_app/android"

    // 独立 CookieJar —— 存 B站的 buvid3 / buvid4 / bili_ticket
    private val biliCookies = mutableListOf<Cookie>()
    private val biliCookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cs: List<Cookie>) {
            biliCookies.addAll(cs)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return biliCookies.filter { it.matches(url) }
        }
    }

    private val v4Dns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = InetAddress.getAllByName(hostname).toList()
            val v4 = all.filterIsInstance<Inet4Address>()
            return if (v4.isNotEmpty()) v4 else all
        }
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(biliCookieJar)
        .dns(v4Dns)
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            chain.proceed(req)
        }
        .build()

    // 预热状态：保证同时只有一个协程在做预热
    private val warmupLock = Mutex()
    @Volatile private var warmedUp = false

    /** 先访问一次 B站 首页，拿到 buvid3/buvid4 cookie。
     *  必须在调 API 之前执行，否则会触发 -352 风控。 */
    private suspend fun ensureWarmedUp() = withContext(Dispatchers.IO) {
        if (warmedUp) return@withContext
        warmupLock.withLock {
            if (warmedUp) return@withLock
            runCatching {
                // 访问 api.bilibili.com 本身就会下发 buvid3 cookie
                val req = Request.Builder()
                    .url("$API_BASE/x/web-interface/ranking/v2?rid=0&type=all")
                    .header("Referer", "https://www.bilibili.com/")
                    .header("Accept", "application/json, text/plain, */*")
                    .build()
                client.newCall(req).execute().close()
                warmedUp = true
            }
        }
    }

    /** B站分区 tid 映射表（从 bilibili-API-collect 整理）。
     *  null 表示首页推荐而非某个具体分区。 */
    fun nameToTid(name: String): Int? = when (name) {
        "热门" -> 0           // 全站排行
        "首页推荐" -> null   // 走 recommend 接口
        "动画" -> 1
        "番剧" -> 13
        "国创" -> 167
        "音乐" -> 3
        "舞蹈" -> 129
        "游戏" -> 4
        "知识" -> 36
        "科技" -> 188
        "运动" -> 234
        "汽车" -> 254
        "生活" -> 160
        "美食" -> 295
        "动物圈" -> 217
        "时尚" -> 155
        "资讯" -> 207
        "娱乐" -> 5
        else -> null
    }

    /** 拉分类热门视频列表（排行榜 API，免签名）。 */
    suspend fun fetchRanking(tid: Int, ps: Int = 20): Result<List<BiliVideo>> {
        ensureWarmedUp()
        val url = "$API_BASE/x/web-interface/ranking/v2?rid=$tid&type=all"
        return fetchList(url)
    }

    /** 首页推荐（瀑布流，免签名）。 */
    suspend fun fetchRecommend(ps: Int = 20): Result<List<BiliVideo>> {
        ensureWarmedUp()
        val url = "$API_BASE/x/web-interface/dynamic/recommend?ps=$ps"
        return fetchList(url)
    }

    private suspend fun fetchList(url: String): Result<List<BiliVideo>> =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("Referer", "https://www.bilibili.com/")
                .header("Accept", "application/json, text/plain, */*")
                .build()

            runCatching {
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    throw Exception("HTTP ${resp.code}")
                }
                val body = resp.body?.string() ?: throw Exception("empty body")
                val root = JSONObject(body)
                val code = root.optInt("code", -1)
                if (code != 0) {
                    val msg = root.optString("message")
                    throw Exception("B站风控 $code: $msg")
                }
                val data = root.optJSONObject("data") ?: return@runCatching emptyList()

                // ranking/v2 → data.list
                // recommend → data.item (array of item card)
                val arr = data.optJSONArray("list")
                    ?: data.optJSONArray("item")
                    ?: return@runCatching emptyList()

                val result = ArrayList<BiliVideo>(arr.length())
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    try {
                        val video = parseVideo(obj)
                        if (video != null) result.add(video)
                    } catch (_: Exception) { /* 跳过 */ }
                }
                result
            }
        }

    private fun parseVideo(obj: JSONObject): BiliVideo? {
        val bvid = obj.optString("bvid").ifBlank { return null }
        val title = obj.optString("title").ifBlank { "未知标题" }
        val pic = obj.optString("pic").ifBlank { obj.optString("cover", "") }
        val aid = obj.optLong("aid", obj.optLong("id", 0))
        val tid = obj.optInt("tid", obj.optInt("rid", 0))
        val tname = obj.optString("tname", "")
        val pubdate = obj.optLong("pubdate", obj.optLong("ptime", 0))
        val duration = obj.optInt("duration", 0)
        val desc = obj.optString("desc", "")
        val videos = obj.optInt("videos", 1)

        // owner
        val owner = obj.optJSONObject("owner")
            ?: obj.optJSONObject("upper")
            ?: JSONObject()
        val biliOwner = BiliOwner(
            mid = owner.optLong("mid", owner.optLong("uid", 0)),
            name = owner.optString("name", owner.optString("uname", "匿名")),
            face = owner.optString("face", owner.optString("up_face", "")),
        )

        // stat
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
            // recommend 接口 stat 扁平
            BiliStat(
                view = obj.optLong("play", obj.optLong("view", 0)),
                danmaku = obj.optLong("danmaku", 0),
                reply = 0, favorite = 0, coin = 0, share = 0, like = 0
            )
        }

        return BiliVideo(
            bvid = bvid, aid = aid, title = title, pic = pic,
            tid = tid, tname = tname, pubdate = pubdate, duration = duration,
            desc = desc, owner = biliOwner, stat = biliStat, videos = videos,
        )
    }
}
