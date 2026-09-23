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

            // === Step 1: 先抓 subject HTML（必须最先！PoW 解完 cookie 才生效）===
            var html = fetchHtml(url) ?: return@runCatching null
            if (html.contains("载入中") && html.contains("name=\"cha\"")) {
                android.util.Log.d("DoubanRepo", "PoW challenge for $doubanId")
                html = solveDoubanPow(html, url) ?: return@runCatching null
            }
            val detail = parseDetailHtml(html, doubanId, url) ?: return@runCatching null

            // === Step 2: PoW cookie 已经生效 → 并行拉 celebrities + trailers + stills + reviews ===
            val celebrityTask = async {
                runCatching { fetchCelebrities(doubanId) }.getOrElse { emptyList() }
            }
            val trailerTask = async {
                runCatching { fetchTrailers(doubanId) }.getOrElse { emptyList() }
            }
            val stillsTask = async {
                runCatching { fetchStills(doubanId) }.getOrElse { emptyList() }
            }
            val reviewTask = async {
                runCatching { fetchReviews(doubanId) }.getOrElse { emptyList() }
            }

            detail.castMembers = celebrityTask.await()
            val trailersFromSubject = detail.trailers
            val trailersFromVideoPage = trailerTask.await()
            detail.trailers = (trailersFromSubject + trailersFromVideoPage)
                .distinctBy { it.videoUrl }
            val stillsFromSubject = detail.stills
            val stillsFromPhotosPage = stillsTask.await()
            // 合并去重（剧照 URL 通常有 size 参数，按核心路径去重）
            detail.stills = (stillsFromSubject + stillsFromPhotosPage)
                .distinctBy { it.substringBefore('?').substringAfterLast('/') }
            detail.reviews = reviewTask.await()
            detail
        }.getOrElse { e ->
            android.util.Log.e("DoubanRepo", "getDetail FAILED for $doubanId", e)
            null
        }
    }

    /** 判断 URL 是否是真视频文件（不是网页链接或重定向包装） */
    private fun isVideoUrl(url: String): Boolean {
        val lower = url.lowercase()
        // 先排除明显非视频的：html/htm 网页、豆瓣重定向包装
        if (lower.contains(".html") || lower.contains(".htm") || lower.contains(".php") || lower.contains(".asp")) return false
        if (lower.contains("douban.com/link2")) return false
        // 白名单：媒体扩展名
        val extensions = listOf(".mp4", ".m3u8", ".webm", ".mkv", ".mov", ".m4v", ".3gp", ".flv", ".avi", ".ts")
        if (extensions.any { lower.contains(it) }) return true
        // youku / youtube 嵌入链接（带具体视频路径，不是网页）
        if (lower.contains("youku.com/v_show/id_") || lower.contains("youtube.com/watch?v=") || lower.contains("youtu.be/")) return true
        return false
    }

    /** 抓取预告片：独立页面 /subject/{id}/video。
     *  豆瓣 subject 详情页几乎不渲染预告片 DOM，必须去独立页抓。
     *  内置 PoW 兜底。 */
    private fun fetchTrailers(doubanId: String): List<Trailer> {
        val pageUrl = "https://movie.douban.com/subject/$doubanId/video"
        var html = fetchHtml(pageUrl) ?: return emptyList()
        if (html.contains("载入中") && html.contains("name=\"cha\"")) {
            android.util.Log.d("DoubanRepo", "fetchTrailers got PoW, solving...")
            html = solveDoubanPow(html, pageUrl) ?: return emptyList()
        }
        val doc = Jsoup.parse(html)
        val out = mutableListOf<Trailer>()

        // 多 selector 兜底找预告片
        val selectors = listOf(
            "a[data-video]",
            "a[data-video-url]",
            ".gallery-item a",
            ".video-item a",
            "a[href*='video']",
        )
        for (sel in selectors) {
            val nodes = doc.select(sel)
            android.util.Log.d("DoubanRepo", "TRAILER(video page) '$sel' matched ${nodes.size}")
            if (nodes.isNotEmpty()) {
                nodes.forEach { a ->
                    val videoUrl = a.attr("data-video")
                        .ifBlank { a.attr("data-video-url") }
                        .ifBlank { a.attr("href") }
                        .trim().trimEnd(',')
                    val img = a.selectFirst("img")
                    val cover = img?.attr("data-src")?.ifBlank { img.attr("src") }?.trim().orEmpty()
                    val title = a.attr("data-video-title")
                        .ifBlank { a.attr("title") }
                        .ifBlank { img?.attr("alt") ?: "" }
                        .trim()
                    // 严格过滤：必须是真视频文件 URL，不能是网页链接
                    if (videoUrl.isNotBlank() && videoUrl.startsWith("http") && isVideoUrl(videoUrl)) {
                        out += Trailer(title = title, videoUrl = videoUrl, coverUrl = cover)
                    }
                }
                if (out.isNotEmpty()) break
            }
        }

        // 兜底：扫所有 <iframe src> 和 <video src>
        if (out.isEmpty()) {
            doc.select("iframe[src], video[src], source[src]").forEach { el ->
                val src = el.attr("src").trim()
                if (src.isNotBlank() && (src.contains(".mp4") || src.contains("youku") || src.contains("youtube") || src.contains("doubanio"))) {
                    out += Trailer(title = "预告片", videoUrl = src, coverUrl = "")
                }
            }
            android.util.Log.d("DoubanRepo", "TRAILER(video page) iframe/video fallback count=${out.size}")
        }

        android.util.Log.d("DoubanRepo", "fetchTrailers final count=${out.size}")
        return out
    }

    /** 抓取完整剧照：独立页面 /subject/{id}/photos。
     *  subject 详情页只渲染 4~6 张缩略图，完整剧照在 photos 页面。 */
    private fun fetchStills(doubanId: String): List<String> {
        val pageUrl = "https://movie.douban.com/subject/$doubanId/photos"
        var html = fetchHtml(pageUrl) ?: return emptyList()
        if (html.contains("载入中") && html.contains("name=\"cha\"")) {
            android.util.Log.d("DoubanRepo", "fetchStills got PoW, solving...")
            html = solveDoubanPow(html, pageUrl) ?: return emptyList()
        }
        val doc = Jsoup.parse(html)
        val out = mutableListOf<String>()

        // 多 selector 兜底
        val selectors = listOf(
            ".photo-list img",
            "#content img[src*='doubanio.com/view/photo']",
            "a[href*='/photo/'] img",
            ".article img",
            "img[src*='doubanio.com/view/photo']",
        )
        for (sel in selectors) {
            val nodes = doc.select(sel)
            android.util.Log.d("DoubanRepo", "STILLS(photos page) '$sel' matched ${nodes.size}")
            if (nodes.isNotEmpty()) {
                nodes.forEach { img ->
                    val url = img.attr("data-src")
                        .ifBlank { img.attr("src") }
                        .trim()
                    if (url.isNotBlank() && url.contains("doubanio.com")) {
                        out += url
                    }
                }
                if (out.size >= 10) break  // 够多了就停
            }
        }
        android.util.Log.d("DoubanRepo", "fetchStills final count=${out.size}")
        return out
    }

    /** 抓取影评（长评）。旧 API /j/subject/{id}/reviews 已 404，
     *  改从 /subject/{id}/reviews HTML 页面解析。 */
    private fun fetchReviews(doubanId: String): List<DoubanReview> {
        val url = "https://movie.douban.com/subject/$doubanId/reviews"
        var html = fetchHtml(url) ?: return emptyList()
        if (html.contains("载入中") && html.contains("name=\"cha\"")) {
            html = solveDoubanPow(html, url) ?: return emptyList()
        }
        val doc = Jsoup.parse(html)
        val out = mutableListOf<DoubanReview>()

        // 豆瓣长评列表：.review-item / .reviews-list .review-item / #content .review-item
        val items = doc.select(".review-item, .review-list .review-item, .reviews .review-item")
        android.util.Log.d("DoubanRepo", "fetchReviews HTML items=${items.size}")
        for (item in items) {
            val aTitle = item.selectFirst(".main-bd h2 a, .title a, h3 a") ?: continue
            val title = aTitle.text().trim()
            val reviewUrl = aTitle.attr("href").let { if (it.startsWith("/")) "https://movie.douban.com$it" else it }
            val content = item.selectFirst(".review-short, .review-content, .short-content")?.text()?.trim().orEmpty()
            val authorNode = item.selectFirst(".reviewer, .author, a[href*='/people/']")
            val authorName = authorNode?.text()?.trim().orEmpty()
            val authorUrl = authorNode?.attr("href")?.let { if (it.startsWith("/")) "https://movie.douban.com$it" else it }.orEmpty()
            val avatar = item.selectFirst(".reviewer img, .author img, .avatar img")?.attr("src").orEmpty()
            val ratingClass = item.selectFirst(".rating, .star, [class*='star']")?.classNames()?.firstOrNull { it.contains("star") && it.any { c -> c.isDigit() } }.orEmpty()
            val rating = ratingClass.filter { it.isDigit() }.toFloatOrNull()?.div(10f) ?: 0f
            val date = item.selectFirst(".main-bd .time, .review-date, .date, span[class*='date']")?.text()?.trim().orEmpty()

            if (title.isNotBlank() || content.isNotBlank()) {
                out += DoubanReview(
                    author = authorName,
                    avatarUrl = avatar,
                    rating = rating,
                    title = title,
                    content = content,
                    date = date,
                    doubanUrl = reviewUrl.ifBlank { authorUrl }
                )
            }
            if (out.size >= 5) break
        }
        android.util.Log.d("DoubanRepo", "fetchReviews final count=${out.size}")
        return out
    }

    /** 抓取演职员头像：独立页面 /subject/{id}/celebrities。
     *  **核心策略**：不猜 CSS selector，直接扫所有 `personage` 链接，往上爬一层找同名 img。
     *  内置 PoW 兜底：即使 getDetail 已解 PoW，这里再碰一次也能自救。 */
    private fun fetchCelebrities(doubanId: String): List<CastMember> {
        val pageUrl = "https://movie.douban.com/subject/$doubanId/celebrities"
        var html = fetchHtml(pageUrl) ?: return emptyList()
        if (html.contains("载入中") && html.contains("name=\"cha\"")) {
            android.util.Log.d("DoubanRepo", "fetchCelebrities got PoW, solving...")
            html = solveDoubanPow(html, pageUrl) ?: return emptyList()
        }
        if (!html.contains("personage")) {
            android.util.Log.d("DoubanRepo", "fetchCelebrities: no personage links (body head: ${html.take(200)})")
            return emptyList()
        }
        val doc = Jsoup.parse(html)
        val out = mutableListOf<CastMember>()
        val seenPersonages = mutableSetOf<String>()  // 去重

        // === 策略 1：扫所有 personage 链接 ===
        val personageLinks = doc.select("a[href*='/personage/']")
        android.util.Log.d("DoubanRepo", "fetchCelebrities personage links=${personageLinks.size}")

        // === DEBUG：打印前 3 个 personage 链接的 HTML 结构 ===
        personageLinks.take(3).forEachIndexed { i, a ->
            val href = a.attr("href")
            val text = a.text().trim()
            val parentHtml = a.parent()?.outerHtml()?.take(500) ?: "(no parent)"
            android.util.Log.d("DoubanRepo", "PERSONAGE[$i]: href=$href, text=[$text], parentHtml=$parentHtml")
        }

        for (a in personageLinks) {
            val href = a.attr("href").trimEnd(',').let { if (it.startsWith("/")) "https://movie.douban.com$it" else it }
            if (!seenPersonages.add(href)) continue  // 去重

            val linkText = a.text().trim()
            val titleAttr = a.attr("title").trim()
            val name = linkText.ifBlank { titleAttr }
            if (name.isBlank() || name.length > 30) continue  // 过滤导航/更多/返回等

            // 往上爬最多 5 层，找带 img 或 background-image 的祖先节点
            var avatar = ""
            var parent = a.parent()
            var role = ""
            repeat(5) {
                if (parent == null) return@repeat
                // 方式 1：<img data-src=...> 或 <img src=...>
                val img = parent!!.selectFirst("img")
                avatar = img?.attr("data-src")?.ifBlank { img.attr("src") }?.trim().orEmpty()
                // 方式 2：CSS background-image: url(...)  ← 豆瓣 celebrities 页面用这个！
                if (avatar.isBlank()) {
                    val bgAvatar = parent!!.selectFirst("div.avatar, [class*='avatar']")?.let { div ->
                        val style = div.attr("style")
                        Regex("""url\(["']?(https?://[^"')]+)""").find(style)?.groupValues?.get(1)
                    }?.trim().orEmpty()
                    avatar = bgAvatar
                }
                // 角色标识
                if (role.isBlank()) {
                    role = parent!!.selectFirst(".role, .character, .celebrity-role, [class*='role'], [class*='char']")
                        ?.text()?.trim().orEmpty()
                }
                if (avatar.isNotBlank()) return@repeat
                parent = parent!!.parent()
            }

            // 还没找到？直接从 a 标签内找
            if (avatar.isBlank()) {
                avatar = a.selectFirst("img")?.attr("data-src")?.ifBlank { a.selectFirst("img")?.attr("src") }.orEmpty().trim()
            }

            // 头像必须是豆瓣 CDN 上的真实 jpg/webp，跳过空的或导航图
            val isRealAvatar = avatar.isNotBlank() && (avatar.contains("doubanio.com") || avatar.contains("dgtle.com"))
            if (name.isNotBlank() && isRealAvatar) {
                out += CastMember(name = name, role = role, avatarUrl = avatar, doubanUrl = href)
            } else if (name.isNotBlank()) {
                // 没头像但有名，debug 一下
                android.util.Log.d("DoubanRepo", "SKIP: name=[$name], avatar=[$avatar], reason=avatarBlankOrNotCDN")
            }
        }

        android.util.Log.d("DoubanRepo", "fetchCelebrities final count=${out.size}, first=${out.firstOrNull()?.name}/${out.firstOrNull()?.role}/img=${out.firstOrNull()?.avatarUrl?.take(60)}")
        return out.take(20)
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

                // === 演职员头像已由并行的 fetchCelebrities 任务填充（详见 getDetail） ===

                // === 预告片（豆瓣 subject 页经常没有，用宽匹配兜底） ===
                val trailers = mutableListOf<Trailer>()

                // 先试具体 selector
                val trailerSelectors = listOf(
                    "#related-pic-vid a.gallery-item",
                    "#related-pic-vid a",
                    "a[class*='video-modal']",
                    ".related-video a",
                    "#related-pic a[data-video]",
                    "a[data-video]",
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
                            if (videoUrl.isNotBlank() && isVideoUrl(videoUrl)) {
                                trailers += Trailer(title, videoUrl, cover)
                            }
                        }
                        if (trailers.isNotEmpty()) break
                    }
                }

                // 兜底：扫所有 <a> 标签找 video href / data-video
                if (trailers.isEmpty()) {
                    val videoLinks = doc.select("a").filter { a ->
                        val href = a.attr("href")
                        val dv = a.attr("data-video")
                        dv.isNotBlank() || href.endsWith(".mp4") || href.contains("youku") || href.contains("youtube") || href.contains("video")
                    }
                    android.util.Log.d("DoubanRepo", "TRAILER fallback: ${videoLinks.size} video-like links")
                    videoLinks.forEach { a ->
                        val img = a.selectFirst("img")
                        val cover = img?.attr("data-src")?.ifBlank { img.attr("src") } ?: ""
                        val videoUrl = a.attr("data-video").ifBlank { a.attr("href") }
                        val title = a.attr("data-video-title").ifBlank { a.attr("title") }.ifBlank { img?.attr("alt") }.orEmpty()
                        if (videoUrl.isNotBlank() && videoUrl.startsWith("http") && isVideoUrl(videoUrl)) {
                            trailers += Trailer(title, videoUrl, cover)
                        }
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
