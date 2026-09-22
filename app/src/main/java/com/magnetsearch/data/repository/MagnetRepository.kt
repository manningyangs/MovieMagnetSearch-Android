package com.magnetsearch.data.repository

import com.magnetsearch.data.api.HttpClient
import com.magnetsearch.data.model.MediaType
import com.magnetsearch.data.model.MagnetResult
import com.magnetsearch.data.model.SearchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder

/** 磁力搜索：多源并发 + 结果合并。
 *
 *  关键修复（对比桌面版）：
 *  1) PirateBay 用 apibay.org JSON API（不再爬 HTML），自然返回 ~100 条
 *  2) YTS limit=50（之前 10 → 少 5 倍！），多域 fallback
 *  3) 1337x 两步：搜索页取详情链接 → 详情页抓磁链，取前 15 条详情
 *  4) BT之家（新增）：1lou.me Discuz 论坛，中文内容多
 *  5) bt client UA 桌面 Chrome（不是 Android Mobile）
 *  6) Nyaa.si 保持全分类
 */
class MagnetRepository {

    private val YTS_DOMAINS = listOf("yts.ag", "yts.lt", "yts.mx")
    private val BTBTT_BASE = "https://www.1lou.me"

    suspend fun search(
        query: String,
        mediaType: MediaType = MediaType.MOVIE,
        sources: List<SearchSource> = listOf(SearchSource.PIRATE_BAY, SearchSource.NYAA)
    ): List<MagnetResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        val tasks = sources.map { source ->
            async {
                runCatching {
                    when (source) {
                        SearchSource.PIRATE_BAY -> searchPirateBay(query, mediaType)
                        SearchSource.YTS -> searchYts(query)
                        SearchSource.NYAA -> searchNyaa(query)
                        SearchSource.ONE337X -> search1337x(query)
                        SearchSource.BTBTT -> searchBtbtt(query)
                        SearchSource.ALL -> emptyList()
                    }
                }.getOrElse { emptyList() }
            }
        }

        val merged = tasks.awaitAll().flatten()
        merged.distinctBy { r ->
            val h = Regex("""btih:([a-fA-F0-9]{40})""").find(r.magnet)?.groupValues?.getOrNull(1)?.lowercase()
            h ?: r.magnet
        }
    }

    // ========== Pirate Bay（apibay.org JSON API，返回 ~100 条，稳定不被 Cloudflare 挡）==========
    private fun searchPirateBay(query: String, mediaType: MediaType): List<MagnetResult> {
        val cat = mediaType.pirateBayCategory
        val url = "https://apibay.org/q.php?q=${URLEncoder.encode(query, "UTF-8")}&cat=$cat"
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
                val magnet = "magnet:?xt=urn:btih:$hash&dn=${URLEncoder.encode(name, "UTF-8")}"
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
            val url = "https://$domain/api/v2/list_movies.json?query_term=${URLEncoder.encode(query, "UTF-8")}&limit=50"
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
                            val magnet = "magnet:?xt=urn:btih:$hash&dn=${URLEncoder.encode(title, "UTF-8")}"
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

    // ========== Nyaa.si（全分类，返回页面默认条数）==========
    private fun searchNyaa(query: String): List<MagnetResult> {
        val url = "https://nyaa.si/?f=0&c=0_0&q=${URLEncoder.encode(query, "UTF-8")}&p=1"
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

    // ========== 1337x（两步：搜索页取详情 → 详情页抓磁链，取前 15 条详情）==========
    private fun search1337x(query: String): List<MagnetResult> {
        val searchUrl = "https://www.1337x.to/category-search/${URLEncoder.encode(query.replace(" ", "+"), "UTF-8")}/Movies/1/"
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
        for (link in detailLinks.take(15)) {
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

    // ========== BT之家（1lou.me Discuz 论坛，搜索页 → 帖子页抓磁链，取前 15 条）==========
    private fun searchBtbtt(query: String): List<MagnetResult> {
        val searchUrl = "$BTBTT_BASE/search.php?mod=forum&searchsubmit=yes&srchtxt=${URLEncoder.encode(query, "UTF-8")}"
        val req = Request.Builder().url(searchUrl)
            .header("Referer", BTBTT_BASE)
            .get().build()
        val client = HttpClient.bt
        val searchHtml: String = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            resp.body?.string() ?: return emptyList()
        }
        // 提取帖子 tid：thread-NNN-N-N.html
        val tids = LinkedHashSet<String>()
        Regex("""thread-(\d+)-\d+-\d+\.html""").findAll(searchHtml).forEach { tids.add(it.groupValues[1]) }
        val out = mutableListOf<MagnetResult>()
        for (tid in tids.take(15)) {
            val threadUrl = "$BTBTT_BASE/thread-$tid-1-1.html"
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
