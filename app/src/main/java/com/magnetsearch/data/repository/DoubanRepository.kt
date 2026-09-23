package com.magnetsearch.data.repository

import android.annotation.SuppressLint
import com.magnetsearch.data.api.HttpClient
import com.magnetsearch.data.model.CastMember
import com.magnetsearch.data.model.DoubanComment
import com.magnetsearch.data.model.DoubanDetail
import com.magnetsearch.data.model.DoubanMovie
import com.magnetsearch.data.model.DoubanReview
import com.magnetsearch.data.model.RatingDist
import com.magnetsearch.data.model.Trailer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup

/** 豆瓣数据层：Top250 并行抓取 + 关键词搜索 + 详情页解析。 */
class DoubanRepository {

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"
        private const val CACHE_TTL_MS = 24 * 3600_000L // 24h
    }

    suspend fun getTop250(limit: Int = 250): List<DoubanMovie> = withContext(Dispatchers.IO) {
        val perPage = 25
        val pages = (limit + perPage - 1) / perPage

        val deferreds = (0 until pages).map { pageIdx ->
            async { fetchTop250Page(pageIdx, perPage) }
        }

        // 并发抓所有页，然后按 pageIdx 排序合并
        val results = deferreds.awaitAll().sortedBy { it.first }
        val all = results.flatMap { it.second }
        // DEBUG: 打印前3个封面URL，确认解析正确
        all.take(3).forEachIndexed { i, m ->
            android.util.Log.d("DoubanRepo", "  #${m.rank} coverUrl[${m.coverUrl.isNotBlank()}] = ${m.coverUrl}")
        }
        all.take(limit)
    }

    private suspend fun fetchTop250Page(pageIdx: Int, perPage: Int): Pair<Int, List<DoubanMovie>> =
        withContext(Dispatchers.IO) {
            val offset = pageIdx * perPage
            val startRank = offset + 1
            val url = "https://movie.douban.com/top250?start=$offset"

            val req = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()

            runCatching {
                HttpClient.douban.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val html = resp.body?.string() ?: return@use emptyList()
                    parseTop250Page(html, startRank)
                }
            }.getOrElse { emptyList() }
                .let { pageIdx to it }
        }

    private fun parseTop250Page(html: String, startRank: Int): List<DoubanMovie> {
        val doc = Jsoup.parse(html)
        val out = mutableListOf<DoubanMovie>()
        val items = doc.select("#content .article ol.grid_view > li")

        items.forEachIndexed { i, li ->
            val rank = startRank + i
            val titleA = li.selectFirst(".hd > a") ?: return@forEachIndexed
            val url = titleA.attr("href")
            val doubanId = Regex("""/subject/(\d+)/""").find(url)?.groupValues?.get(1) ?: ""

            val texts = titleA.select("span").map { it.text().trim() }
            val title = texts.firstOrNull() ?: ""
            val original = texts.getOrNull(1)?.trim()?.removePrefix("/")?.trim() ?: ""

            val bdP = li.selectFirst(".bd p")
            var year = ""
            val directors = mutableListOf<String>()
            if (bdP != null) {
                val text = bdP.text()
                Regex("""导演[:：](.+?)(?:主|演|$)""").find(text)?.groupValues?.get(1)
                    ?.split("/")?.take(3)?.forEach { directors.add(it.trim()) }
                year = Regex("""(\d{4})""").find(text)?.groupValues?.get(1) ?: ""
            }

            val rating = li.selectFirst(".rating_num")?.text()?.toFloatOrNull() ?: 0f
            val summary = li.selectFirst(".quote span")?.text() ?: ""
            val cover = li.selectFirst(".pic img")?.let { it.attr("src").ifBlank { it.attr("data-src") } } ?: ""

            out.add(
                DoubanMovie(
                    doubanId = doubanId,
                    title = title,
                    originalTitle = original,
                    year = year,
                    rating = rating,
                    directors = directors,
                    summary = summary,
                    coverUrl = cover,
                    doubanUrl = url,
                    rank = rank
                )
            )
        }
        return out
    }

    suspend fun search(query: String, maxResults: Int = 30): List<DoubanMovie> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        val url = "https://movie.douban.com/j/subject_suggest?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://movie.douban.com/")
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()

        runCatching {
            HttpClient.douban.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val body = resp.body?.string() ?: return@use emptyList()
                // subject_suggest 返回 JSON 数组
                val jsonArray = org.json.JSONArray(body)
                val out = mutableListOf<DoubanMovie>()
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.getJSONObject(i)
                    if (item.optString("type") !in listOf("movie", "tv")) continue
                    val u = item.optString("url", "")
                    val id = Regex("""/subject/(\d+)/""").find(u)?.groupValues?.get(1) ?: ""
                    out.add(
                        DoubanMovie(
                            doubanId = id,
                            title = item.optString("title"),
                            originalTitle = item.optString("sub_title"),
                            year = item.optString("year"),
                            coverUrl = item.optString("img"),
                            doubanUrl = u
                        )
                    )
                    if (out.size >= maxResults) break
                }
                out
            }
        }.getOrElse { emptyList() }
    }

    suspend fun getDetail(doubanId: String): DoubanDetail? = withContext(Dispatchers.IO) {
        if (doubanId.isBlank()) return@withContext null
        val url = "https://movie.douban.com/subject/$doubanId/"

        runCatching {
            warmUpCookie()

            // === 两个并行任务：主详情 HTML + 影评 ===
            val detailTask = async {
                val html = fetchHtml(url) ?: return@async null
                val realHtml = if (html.contains("载入中") && html.contains("name=\"cha\"")) {
                    android.util.Log.d("DoubanRepo", "PoW challenge for $doubanId")
                    solveDoubanPow(html, url) ?: return@async null
                } else html
                parseDetailHtml(realHtml, doubanId, url)
            }
            val reviewTask = async {
                runCatching { fetchReviews(doubanId) }.getOrElse { emptyList() }
            }

            val detail = detailTask.await() ?: return@runCatching null
            detail.reviews = reviewTask.await()
            detail
        }.getOrElse { e ->
            android.util.Log.e("DoubanRepo", "getDetail FAILED for $doubanId", e)
            null
        }
    }

    /** 抓取影评（长评）。runCatching 确保 PoW 或网络失败不会阻塞详情页。 */
    private fun fetchReviews(doubanId: String): List<DoubanReview> {
        val url = "https://movie.douban.com/j/subject/$doubanId/reviews?start=0"
        val req = Request.Builder().url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://movie.douban.com/subject/$doubanId/")
            .header("X-Requested-With", "XMLHttpRequest")
            .get().build()
        val resp = HttpClient.douban.newCall(req).execute()
        if (!resp.isSuccessful) {
            android.util.Log.d("DoubanRepo", "fetchReviews HTTP ${resp.code}")
            return emptyList()
        }
        val body = resp.body?.string() ?: return emptyList()
        android.util.Log.d("DoubanRepo", "fetchReviews body head: ${body.take(300)}")
        // 返回格式可能是 JSON 数组，也可能是被 PoW 拦截返回的 HTML
        if (!body.trimStart().startsWith("[")) return emptyList()
        val arr = JSONArray(body)
        val out = mutableListOf<DoubanReview>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val reviewer = item.optJSONObject("reviewer") ?: continue
            val rTitle = item.optString("title", "").trim()
            val rContent = item.optString("content", "").trim()
            if (rTitle.isBlank() && rContent.isBlank()) continue
            val ratingStr = item.optString("rating", "")
            val rating = ratingStr.toFloatOrNull()?.div(10f) ?: 0f
            val alt = reviewer.optString("alt", "")
            // 跳过外链长影评（alt 里有 /review/ 的才是豆瓣原创）
            out.add(
                DoubanReview(
                    author = reviewer.optString("name", ""),
                    avatarUrl = reviewer.optString("avatar", ""),
                    rating = rating,
                    title = rTitle,
                    content = rContent,
                    date = item.optString("time", ""),
                    doubanUrl = alt
                )
            )
            if (out.size >= 5) break
        }
        return out
    }

    private fun fetchHtml(url: String): String? {
        val req = Request.Builder().url(url).get().build()
        HttpClient.douban.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                android.util.Log.e("DoubanRepo", "fetchHtml HTTP ${resp.code} for $url")
                return null
            }
            return resp.body?.string()
        }
    }

    /** 预热 cookie：GET movie.douban.com/ 让豆瓣下发 bid cookie。
     *  没有这个 cookie，PoW 解出来 POST 回去 sec.douban.com 不认 —— 会循环回 PoW 页。
     *  和桌面版 core/douban.py:298 完全一致。 */
    private fun warmUpCookie() {
        runCatching {
            val req = Request.Builder().url("https://movie.douban.com/").get().build()
            HttpClient.douban.newCall(req).execute().use { it.close() }
        }
    }

    private fun solveDoubanPow(powHtml: String, subjectUrl: String): String? {
        val tok = Regex("""name="tok"[^>]*value="([^"]+)"""").find(powHtml)?.groupValues?.get(1) ?: return null
        val cha = Regex("""name="cha"[^>]*value="([^"]+)"""").find(powHtml)?.groupValues?.get(1) ?: return null
        val red = Regex("""name="red"[^>]*value="([^"]+)"""").find(powHtml)?.groupValues?.get(1) ?: return null

        // SHA-512 PoW：找最小 nonce 使 hash(cha + nonce) 前 4 个 hex 字符为 "0000"
        val digest = java.security.MessageDigest.getInstance("SHA-512")
        var nonce = 0L
        while (true) {
            nonce++
            val bytes = digest.digest((cha + nonce).toByteArray())
            val hex = bytes.joinToString("") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
            if (hex.startsWith("0000")) break
        }
        android.util.Log.d("DoubanRepo", "PoW nonce=$nonce (cha length=${cha.length})")

        // === 尝试 A：POST 到 sec.douban.com/c 跟进重定向 ===
        val form = okhttp3.FormBody.Builder()
            .add("tok", tok).add("cha", cha)
            .add("sol", nonce.toString()).add("red", red).build()
        val req = Request.Builder().url("https://sec.douban.com/c")
            .header("Referer", subjectUrl)
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .post(form).build()
        HttpClient.douban.newCall(req).execute().use { resp ->
            val finalUrl = resp.request.url.toString()
            val html = resp.body?.string() ?: ""
            android.util.Log.d("DoubanRepo", "PoW POST A finalUrl=$finalUrl, bodyLen=${html.length}, hasItemReviewed=${html.contains("v:itemreviewed")}")
            if (html.contains("v:itemreviewed")) return html
            // 如果还在 sec.douban.com，继续尝试 B
            if (finalUrl.startsWith("https://sec.douban.com")) {
                android.util.Log.d("DoubanRepo", "PoW POST A still on sec.douban.com (body head: ${html.take(200).replace("\n", "\\n")})")
            }
        }

        // === 尝试 B：关闭 followRedirects，手动跟随 302 ===
        android.util.Log.d("DoubanRepo", "PoW trying B: manual 302 follow")
        val clientNoRedirect = HttpClient.douban.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val resp1 = clientNoRedirect.newCall(req).execute()
        val location = resp1.header("Location")
        android.util.Log.d("DoubanRepo", "PoW POST B status=${resp1.code}, Location=$location")
        // 关键 debug：打印 PoW POST 后 sec.douban.com 返回了哪些 Set-Cookie
        resp1.headers("Set-Cookie").forEach { android.util.Log.d("DoubanRepo", "PoW Set-Cookie: $it") }
        val secUrl = Request.Builder().url("https://sec.douban.com/").build().url
        android.util.Log.d("DoubanRepo", "PoW cookies in jar: ${HttpClient.douban.cookieJar.loadForRequest(secUrl)}")
        resp1.close()
        if (!location.isNullOrBlank()) {
            val redirectUrl = if (location.startsWith("/")) "https://movie.douban.com$location" else location
            val resp2 = clientNoRedirect.newCall(Request.Builder().url(redirectUrl).get().build()).execute()
            val body2 = resp2.body?.string() ?: ""
            android.util.Log.d("DoubanRepo", "PoW POST B redirect status=${resp2.code}, hasItemReviewed=${body2.contains("v:itemreviewed")}")
            resp2.headers("Set-Cookie").forEach { android.util.Log.d("DoubanRepo", "PoW B redirect Set-Cookie: $it") }
            // 手动打印 movie.douban.com 请求时 jar 里实际送了哪些 cookie
            val movieUrl = Request.Builder().url("https://movie.douban.com/").build().url
            android.util.Log.d("DoubanRepo", "PoW B cookies for movie.douban.com: ${HttpClient.douban.cookieJar.loadForRequest(movieUrl)}")
            if (body2.contains("v:itemreviewed")) return body2
        }

        // === 尝试 C：直接 POST 到原 subject URL（桌面版降级方案） ===
        android.util.Log.d("DoubanRepo", "PoW trying C: POST directly to subject URL")
        val reqC = Request.Builder().url(subjectUrl)
            .header("Referer", subjectUrl)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .post(form).build()
        HttpClient.douban.newCall(reqC).execute().use { resp ->
            val html = resp.body?.string() ?: ""
            android.util.Log.d("DoubanRepo", "PoW POST C bodyLen=${html.length}, hasItemReviewed=${html.contains("v:itemreviewed")}")
            if (html.contains("v:itemreviewed")) return html
        }

        android.util.Log.e("DoubanRepo", "All PoW strategies FAILED for $subjectUrl")
        return null
    }

    @SuppressLint("SuspiciousIndentation")
    private fun parseDetailHtml(html: String, doubanId: String, url: String): DoubanDetail? {
        val doc = Jsoup.parse(html)
        // 快速验证这是不是真正的详情页（不是 PoW 或错误页）
        if (!html.contains("v:itemreviewed")) {
            android.util.Log.e("DoubanRepo", "parseDetailHtml: HTML missing v:itemreviewed, got ${html.take(200)}")
            return null
        }
        val d = DoubanDetail(doubanId = doubanId, doubanUrl = url)

                // 标题 + 年份 + 英文原名
                doc.selectFirst("#content h1 span[property='v:itemreviewed']")?.let { d.title = it.text().trim() }
                doc.selectFirst("#content h1 .year")?.text()?.trim()?.let { d.year = it.removeSurrounding("(", ")") }
                // 从 h1 的其他 span 中提取英文原名，通常格式为 " / Titanic"
                doc.select("#content h1 span")
                    .firstOrNull { span ->
                        span.attr("property") != "v:itemreviewed"
                            && !span.hasClass("year")
                            && span.attr("class") != "year"
                    }?.let { span ->
                        val t = span.text().trim().removePrefix("/").trim().removePrefix("/").trim()
                        if (t.isNotBlank()) d.originalTitle = t
                    }

                // 评分 + 评价人数
                doc.selectFirst(".rating_num[property='v:average']")?.text()?.toFloatOrNull()?.let { d.rating = it }
                doc.selectFirst("span[property='v:votes']")?.text()?.toIntOrNull()?.let { d.voteCount = it }

                // 封面
                doc.selectFirst("#mainpic img")?.attr("src")?.let { d.coverUrl = it }

                // === #info 核心信息（全部用 info.text() 的正则，最稳） ===
                val info = doc.selectFirst("#info")
                if (info != null) {
                    d.directors = info.select("a[rel='v:directedBy']").map { it.text().trim() }
                    d.actors = info.select("a[rel='v:starring']").map { it.text().trim() }.take(10)
                    d.genres = info.select("span[property='v:genre']").map { it.text().trim() }
                    d.duration = info.selectFirst("span[property='v:runtime']")?.attr("content")
                        ?: info.selectFirst("span[property='v:runtime']")?.text()?.replace("分钟", "") ?: ""

                    val infoText = info.text()
                    // 用 info.text() 正则一次性提取所有剩余字段（最鲁棒，不怕 DOM 结构变）
                    d.countries = Regex("""制片国家/地区[:：]([^\n]+)""").find(infoText)
                        ?.groupValues?.get(1)?.split("/")?.map { it.trim() } ?: emptyList()
                    d.languages = Regex("""语言[:：]([^\n]+)""").find(infoText)
                        ?.groupValues?.get(1)?.split("/")?.map { it.trim() } ?: emptyList()
                    d.writers = Regex("""编剧[:：]([^\n]+)""").find(infoText)
                        ?.groupValues?.get(1)?.split("/")?.map { it.trim() } ?: emptyList()
                    d.aliases = Regex("""又名[:：]([^\n]+)""").find(infoText)
                        ?.groupValues?.get(1)?.split("/")?.map { it.trim() } ?: emptyList()
                    d.imdbId = Regex("""IMDb[:：]\s*(tt\d+)""", kotlin.text.RegexOption.IGNORE_CASE).find(infoText)
                        ?.groupValues?.get(1) ?: ""
                    d.releaseDates = info.select("span[property='v:initialReleaseDate']")
                        .map { it.text().trim() }
                }

                // === 完整剧情简介 ===
                d.fullSummary = doc.selectFirst("span.all.hidden")?.text()?.trim()
                    ?: doc.selectFirst("span[property='v:summary']")?.text()?.trim()
                    ?: ""

                // === 评分分布 ===
                val ratingItems = doc.select(".ratings-on-weight .item, .rating_distribution .item")
                if (ratingItems.size >= 5) {
                    val dist = ratingItems.take(5).mapNotNull { item ->
                        item.selectFirst(".rating_per")?.text()?.trim()
                            ?.removeSuffix("%")?.toFloatOrNull()
                    }
                    if (dist.size == 5) d.ratingDist = RatingDist(dist[0], dist[1], dist[2], dist[3], dist[4])
                }

                // === 热门短评 ===
                val comments = doc.select("#comments .comment-item").take(5).mapNotNull { item ->
                    val author = item.selectFirst(".comment-info a")?.text()?.trim() ?: ""
                    val content = item.selectFirst(".comment .short")?.text()?.trim() ?: ""
                    val date = item.selectFirst(".comment-info .comment-time")?.text()?.trim() ?: ""
                    val cls = item.selectFirst(".comment-info span[class*='allstar']")?.className() ?: ""
                    val rating = Regex("""allstar(\d+)""").find(cls)?.groupValues?.get(1)
                        ?.toFloatOrNull()?.div(10f) ?: 0f
                    if (content.isBlank()) null else DoubanComment(author, rating, content, date)
                }
                d.comments = comments

                // === 演职员照片（多 selector 兜底） ===
                val castMembers = mutableListOf<CastMember>()
                val castSelectors = listOf(
                    "#celebrities .celebrity",
                    "#celebrities li",
                    ".celebrities .celebrity",
                    "div[id*='celeb'] li",
                    "#info .celebrity"
                )
                for (sel in castSelectors) {
                    val nodes = doc.select(sel)
                    android.util.Log.d("DoubanRepo", "CAST selector '$sel' matched ${nodes.size} nodes")
                    if (nodes.isNotEmpty()) {
                        nodes.forEach { celeb ->
                            val aLink = celeb.selectFirst("a")
                            val href = aLink?.attr("href")?.let {
                                if (it.startsWith("/")) "https://movie.douban.com$it" else it
                            } ?: ""
                            val img = celeb.selectFirst("img")
                            val avatar = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
                            val name = celeb.selectFirst(".celebrity-name, .name, a[title]")?.text()?.trim()
                                ?: img?.attr("alt")?.trim() ?: ""
                            val role = celeb.selectFirst(".celebrity-role, .role, .character")?.text()?.trim() ?: ""
                            if (name.isNotBlank()) castMembers += CastMember(name, role, avatar, href)
                        }
                        break
                    }
                }
                d.castMembers = castMembers
                android.util.Log.d("DoubanRepo", "CAST final count=${castMembers.size}, first avatar=${castMembers.firstOrNull()?.avatarUrl?.take(80)}")

                // === 预告片（多 selector 兜底） ===
                val trailers = mutableListOf<Trailer>()
                val trailerSelectors = listOf(
                    "#related-pic-vid a.gallery-item",
                    "#related-pic-vid a",
                    "a[class*='video-modal']",
                    "a[href*='video']",
                    ".related-video a"
                )
                for (sel in trailerSelectors) {
                    val nodes = doc.select(sel)
                    android.util.Log.d("DoubanRepo", "TRAILER selector '$sel' matched ${nodes.size} nodes")
                    if (nodes.isNotEmpty()) {
                        nodes.forEach { a ->
                            val img = a.selectFirst("img")
                            val cover = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
                            val videoUrl = a.attr("data-video").ifBlank { a.attr("href") }
                            val title = a.attr("data-video-title").ifBlank { a.attr("title") }
                            if (videoUrl.isNotBlank() && cover.isNotBlank()) {
                                trailers += Trailer(title, videoUrl, cover)
                            }
                        }
                        break
                    }
                }
                d.trailers = trailers
                android.util.Log.d("DoubanRepo", "TRAILER final count=${trailers.size}, first cover=${trailers.firstOrNull()?.coverUrl?.take(80)}")

                // === 剧照（多 selector 兜底） ===
                val trailerCoverSet = trailers.map { it.coverUrl }.toSet()
                val stills = mutableListOf<String>()
                val stillSelectors = listOf(
                    "#related-pic a.gallery-item img",
                    "#related-pic img",
                    ".related-pic img",
                    "div[class*='photo'] img"
                )
                for (sel in stillSelectors) {
                    val nodes = doc.select(sel)
                    android.util.Log.d("DoubanRepo", "STILL selector '$sel' matched ${nodes.size} nodes")
                    if (nodes.isNotEmpty()) {
                        nodes.forEach { img ->
                            val url = img.attr("data-src").ifBlank { img.attr("src") }
                            if (url.isNotBlank() && url !in stills && url !in trailerCoverSet) stills += url
                        }
                        break
                    }
                }
                d.stills = stills.take(12)
                android.util.Log.d("DoubanRepo", "STILL final count=${d.stills.size}, first=${d.stills.firstOrNull()?.take(80)}")

                android.util.Log.d("DoubanRepo", "parseDetail OK: title=${d.title}, cast=${d.castMembers.size}, trailers=${d.trailers.size}, stills=${d.stills.size}")
                return d
    }
}
