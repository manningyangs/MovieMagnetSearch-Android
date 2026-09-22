package com.magnetsearch.data.repository

import com.magnetsearch.data.api.HttpClient
import com.magnetsearch.data.model.MediaType
import com.magnetsearch.data.model.MagnetResult
import com.magnetsearch.data.model.MagnetSearchResult
import com.magnetsearch.data.model.SearchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/** 磁力搜索：多源并发 + 结果合并。
 *
 *  每个源的条数上限：
 *  - PirateBay: apibay.org 不限（通常 ~100）
 *  - YTS: limit=50（多 quality 版本会更多）
 *  - Nyaa.si: 页面默认 ~75
 *  - 1337x: 最多 25（两步抓取，控制请求量）
 *  - BT之家: 最多 25（两步抓取，多域 fallback）
 */
class MagnetRepository {

    private val YTS_DOMAINS = listOf("yts.ag", "yts.lt", "yts.mx")

    // BT之家：两个中文 Discuz 论坛（同一模板），哪个通用哪个
    private val BTBTT_DOMAINS = listOf(
        "https://www.1lou.me",
        "https://dyttt.me"
    )

    private fun encode(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private val hasChineseRegex = Regex("""[\u4e00-\u9fff]""")

    // 搜索源分类：中文源直接用中文 query，英文源需先翻译
    private val CHINESE_SOURCES = setOf(SearchSource.BTBTT)
    private val ENGLISH_SOURCES = setOf(
        SearchSource.PIRATE_BAY, SearchSource.YTS,
        SearchSource.NYAA, SearchSource.ONE337X
    )

    /** 通过维基百科 API 把中文片名翻译成英文。
     *  5s 超时，避免大陆网络环境下被墙卡住。
     *  返回翻译后的英文标题（已去除括号内容），失败返回 null。 */
    private suspend fun translateChineseTitleViaWiki(query: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                withTimeout(5000L) {
                    // 先搜 "片名 电影" 提高匹配度，无结果再降级搜裸片名
                    val candidates = listOf("$query 电影", query)
                    for (candidate in candidates) {
                        val url = "https://zh.wikipedia.org/w/api.php?action=query" +
                            "&generator=search" +
                            "&gsrsearch=${encode(candidate)}" +
                            "&gsrnamespace=0" +
                            "&gsrlimit=1" +
                            "&prop=langlinks" +
                            "&lllang=en" +
                            "&lllimit=1" +
                            "&format=json"
                        val req = Request.Builder().url(url)
                            .header("User-Agent", "MagnetSearchAndroid/1.0 (mobile app)")
                            .header("Accept-Language", "zh-CN,zh;q=0.9")
                            .get().build()
                        val result = runCatching {
                            HttpClient.bt.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) return@use null
                                val body = resp.body?.string() ?: return@use null
                                val pages = JSONObject(body)
                                    .optJSONObject("query")?.optJSONObject("pages")
                                    ?: return@use null
                                val key = pages.keys().next()
                                if (key == "-1") return@use null
                                val page = pages.optJSONObject(key) ?: return@use null
                                val langlinks = page.optJSONArray("langlinks") ?: return@use null
                                if (langlinks.length() == 0) return@use null
                                val enTitle = langlinks.getJSONObject(0)
                                    .optString("*", "").trim().ifBlank { null }
                                    ?: return@use null
                                Regex("""\s*\([^)]*\)""").replace(enTitle, "").trim().ifBlank { null }
                            }
                        }.getOrNull()
                        if (result != null) {
                            android.util.Log.d("MagnetRepo", "Wiki: '$query' → '$result'")
                            return@withTimeout result
                        }
                    }
                    null
                }
            }.getOrNull()
        }

    /** 通过豆瓣 subject_suggest API 把中文片名翻译成英文。
     *  豆瓣本身返回 sub_title 字段就是英文原名。
     *  比维基更快，大陆可达性更好。 */
    private suspend fun translateChineseTitleViaDouban(query: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = "https://movie.douban.com/j/subject_suggest?q=${encode(query)}"
                val req = Request.Builder().url(url)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                    .header("Referer", "https://movie.douban.com/")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .get().build()
                HttpClient.douban.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val item = arr.getJSONObject(i)
                        val subTitle = item.optString("sub_title", "").trim()
                        if (subTitle.isNotBlank() && !hasChineseRegex.containsMatchIn(subTitle)) {
                            android.util.Log.d("MagnetRepo", "Douban: '$query' → '$subTitle'")
                            return@use subTitle
                        }
                    }
                    null
                }
            }.getOrNull()
        }

    suspend fun search(
        query: String,
        mediaType: MediaType = MediaType.MOVIE,
        sources: List<SearchSource> = listOf(SearchSource.PIRATE_BAY, SearchSource.NYAA)
    ): MagnetSearchResult = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext MagnetSearchResult(emptyList(), null)

        val isChinese = hasChineseRegex.containsMatchIn(query)

        // === 并发执行 ===
        // 1) 中文源（BT之家）立即用原始中文 query 搜索，不阻塞
        // 2) 同时维基 + 豆瓣并行翻译，谁先成功用谁
        val chineseSources = sources.filter { it in CHINESE_SOURCES }
        val englishSources = sources.filter { it in ENGLISH_SOURCES }

        // Task A: 中文源立即搜索
        val chineseResults = async {
            chineseSources.map { source ->
                runCatching {
                    when (source) {
                        SearchSource.BTBTT -> searchBtbtt(query)
                        else -> emptyList()
                    }
                }.getOrElse { emptyList() }
            }
        }

        // Task B: 翻译（中文才需要）
        val translatedDeferred = if (isChinese) {
            async {
                // 两个都先发起（并行），但优先用豆瓣（更快、大陆可达）
                val wikiTask = async { translateChineseTitleViaWiki(query) }
                val doubanTask = async { translateChineseTitleViaDouban(query) }
                // 先 await 豆瓣，豆瓣有结果直接返回，不用等维基 5s 超时
                val doubanResult = doubanTask.await()
                if (doubanResult != null) {
                    // 取消维基任务，省一个没用的网络请求
                    wikiTask.cancel()
                    doubanResult
                } else {
                    // 豆瓣没结果，等维基
                    wikiTask.await()
                }
            }
        } else {
            null
        }

        // === 等待中文源 + 翻译完成后，再发起英文源 ===
        val chineseFlat = chineseResults.await().flatten()
        val translatedEn = translatedDeferred?.await()

        val englishResults = if (englishSources.isNotEmpty()) {
            async {
                val effectiveQuery = when {
                    isChinese && translatedEn != null -> translatedEn
                    isChinese -> null  // 翻译失败 → 英文源跳过
                    else -> query
                }
                if (effectiveQuery == null) return@async emptyList<List<MagnetResult>>()

                englishSources.map { source ->
                    runCatching {
                        when (source) {
                            SearchSource.PIRATE_BAY -> searchPirateBay(effectiveQuery, mediaType)
                            SearchSource.YTS -> searchYts(effectiveQuery)
                            SearchSource.NYAA -> searchNyaa(effectiveQuery)
                            SearchSource.ONE337X -> search1337x(effectiveQuery)
                            else -> emptyList()
                        }
                    }.getOrElse { emptyList() }
                }
            }
        } else {
            async { emptyList<List<MagnetResult>>() }
        }

        val englishFlat = englishResults.await().flatten()

        // === 合并去重 ===
        val merged = chineseFlat + englishFlat
        val dedup = merged.distinctBy { r ->
            val h = Regex("""btih:([a-fA-F0-9]{40})""").find(r.magnet)?.groupValues?.getOrNull(1)?.lowercase()
            h ?: r.magnet
        }
        MagnetSearchResult(dedup, translatedEn)
    }

    // ========== Pirate Bay（apibay.org JSON API，自然返回 ~100 条）==========
    private fun searchPirateBay(query: String, mediaType: MediaType): List<MagnetResult> {
        val cat = mediaType.pirateBayCategory
        val url = "https://apibay.org/q.php?q=${encode(query)}&cat=$cat"
        val req = Request.Builder().url(url).get().build()
        return HttpClient.bt.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val body = resp.body?.string() ?: return@use emptyList()
            val arr = runCatching { JSONArray(body) }.getOrNull() ?: return@use emptyList()
            val out = mutableListOf<MagnetResult>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val hash = item.optString("info_hash")
                if (hash.isBlank()) continue
                val name = item.optString("name").ifBlank { "Pirate Bay torrent" }
                val sizeBytes = item.optLong("size", 0L)
                val magnet = "magnet:?xt=urn:btih:$hash&dn=${encode(name)}"
                out.add(
                    MagnetResult(
                        title = name, magnet = magnet,
                        size = formatBytes(sizeBytes),
                        seeders = item.optInt("seeders", 0),
                        leechers = item.optInt("leechers", 0),
                        source = "Pirate Bay", category = mediaType.name.lowercase()
                    )
                )
            }
            out
        }
    }

    // ========== YTS（limit=50，多域 fallback）==========
    private fun searchYts(query: String): List<MagnetResult> {
        for (domain in YTS_DOMAINS) {
            val url = "https://$domain/api/v2/list_movies.json?query_term=${encode(query)}&limit=50"
            val req = Request.Builder().url(url).get().build()
            val results = runCatching {
                HttpClient.bt.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string() ?: return@use emptyList()
                    val root = JSONObject(body)
                    if (root.optString("status") != "ok") return@use emptyList()
                    val data = root.optJSONObject("data") ?: return@use emptyList()
                    val movies = data.optJSONArray("movies") ?: return@use emptyList()
                    val out = mutableListOf<MagnetResult>()
                    for (i in 0 until movies.length()) {
                        val m = movies.getJSONObject(i)
                        val title = m.optString("title_long").ifBlank { m.optString("title") }
                        val torrents = m.optJSONArray("torrents") ?: continue
                        for (j in 0 until torrents.length()) {
                            val t = torrents.getJSONObject(j)
                            val hash = t.optString("hash")
                            if (hash.isBlank()) continue
                            val quality = t.optString("quality")
                            val magnet = "magnet:?xt=urn:btih:$hash&dn=${encode(title)}"
                            out.add(
                                MagnetResult(
                                    title = "$title [$quality]",
                                    magnet = magnet,
                                    size = t.optString("size"),
                                    seeders = t.optInt("seeds"),
                                    leechers = t.optInt("peers"),
                                    source = "YTS", category = "movie"
                                )
                            )
                        }
                    }
                    out
                }
            }.getOrNull() ?: emptyList()
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    // ========== Nyaa.si（全分类，页面默认 ~75 条）==========
    private fun searchNyaa(query: String): List<MagnetResult> {
        val url = "https://nyaa.si/?f=0&c=0_0&q=${encode(query)}&p=1"
        val req = Request.Builder().url(url).get().build()
        return HttpClient.bt.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val html = resp.body?.string() ?: return@use emptyList()
            val doc = Jsoup.parse(html)
            val table = doc.selectFirst("table.torrent-list") ?: return@use emptyList()
            val out = mutableListOf<MagnetResult>()
            table.select("tbody tr").forEach { tr ->
                val cells = tr.select("td")
                if (cells.size < 6) return@forEach
                val title = cells[1].selectFirst("a")?.text()?.trim() ?: return@forEach
                val magnet = cells[2].selectFirst("a[href^=magnet:]")?.attr("href")?.replace("&amp;", "&")
                    ?: return@forEach
                val size = cells[3].text().trim()
                val seeders = cells[5].text().toIntOrNull() ?: 0
                val leechers = cells.getOrNull(6)?.text()?.toIntOrNull() ?: 0
                out.add(MagnetResult(title, magnet, size, seeders, leechers, "Nyaa.si"))
            }
            out
        }
    }

    // ========== 1337x（两步：搜索页取详情 → 详情页抓磁链，前 25 条详情）==========
    private fun search1337x(query: String): List<MagnetResult> {
        val searchUrl = "https://www.1337x.to/category-search/${encode(query.replace(" ", "+"))}/Movies/1/"
        val req = Request.Builder().url(searchUrl).get().build()
        val client = HttpClient.bt
        val detailLinks: List<String> = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val html = resp.body?.string() ?: return@use emptyList()
            val doc = Jsoup.parse(html)
            doc.select("td.name a:nth-of-type(2)")
                .mapNotNull { a -> a.attr("href").takeIf { it.isNotBlank() } }
        }
        val out = mutableListOf<MagnetResult>()
        for (link in detailLinks.take(25)) {
            val fullUrl = if (link.startsWith("http")) link else "https://www.1337x.to$link"
            runCatching {
                val detailReq = Request.Builder().url(fullUrl).get().build()
                client.newCall(detailReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val html = resp.body?.string() ?: return@use
                    val m = Regex("""magnet:\?xt=urn:btih:([A-Fa-f0-9]{40})""").find(html) ?: return@use
                    val magnet = m.value
                    val title = Jsoup.parse(html).selectFirst("h1")?.text()?.trim() ?: "1337x torrent"
                    out.add(MagnetResult(title = title, magnet = magnet, source = "1337x"))
                }
            }
        }
        return out
    }

    // ========== BT之家（中文 Discuz 论坛，多域 fallback，两个正则覆盖更多链接格式）==========
    private fun searchBtbtt(query: String): List<MagnetResult> {
        for (base in BTBTT_DOMAINS) {
            val results = runCatching { searchBtbttOnDomain(query, base) }.getOrNull() ?: emptyList()
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    private fun searchBtbttOnDomain(query: String, base: String): List<MagnetResult> {
        val searchUrl = "$base/search.php?mod=forum&searchsubmit=yes&srchtxt=${encode(query)}"
        val req = Request.Builder().url(searchUrl)
            .header("Referer", base)
            .get().build()
        val client = HttpClient.bt
        val searchHtml: String = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: return emptyList()
        }
        // 两个正则覆盖 Discuz 常见链接格式：
        // 1) thread-12345-1-1.html
        // 2) forum.php?mod=viewthread&tid=12345
        val tids = LinkedHashSet<String>()
        Regex("""thread-(\d+)-\d+-\d+\.html""").findAll(searchHtml).forEach { tids.add(it.groupValues[1]) }
        Regex("""forum\.php\?mod=viewthread&tid=(\d+)""").findAll(searchHtml).forEach { tids.add(it.groupValues[1]) }

        if (tids.isEmpty()) return emptyList()

        val out = mutableListOf<MagnetResult>()
        for (tid in tids.take(25)) {
            val threadUrl = "$base/thread-$tid-1-1.html"
            runCatching {
                val threadReq = Request.Builder().url(threadUrl).get().build()
                client.newCall(threadReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val html = resp.body?.string() ?: return@use
                    // 磁链
                    val m = Regex("""magnet:\?xt=urn:btih:([A-Fa-f0-9]{40})""").find(html) ?: return@use
                    val magnet = m.value
                    // 标题
                    val doc = Jsoup.parse(html)
                    val title = doc.selectFirst("#thread_subject, .ts a, h1")?.text()?.trim()
                        ?: "BT之家 $tid"
                    // 大小（正文中找 1.5GB / 850MB）
                    val sizeMatch = Regex("""(\d+(?:\.\d+)?)\s*([GM]B)""", RegexOption.IGNORE_CASE).find(html)
                    val size = sizeMatch?.value?.uppercase() ?: "未知"
                    out.add(
                        MagnetResult(
                            title = title, magnet = magnet,
                            size = size, source = "BT之家"
                        )
                    )
                }
            }
        }
        return out
    }

    // ---------- 工具 ----------
    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 * 1024 -> "%.2f TB".format(bytes / 1024.0 / 1024 / 1024 / 1024)
        bytes >= 1024L * 1024 * 1024 -> "%.2f GB".format(bytes / 1024.0 / 1024 / 1024)
        bytes >= 1024L * 1024 -> "%.2f MB".format(bytes / 1024.0 / 1024)
        bytes >= 1024L -> "%.1f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
