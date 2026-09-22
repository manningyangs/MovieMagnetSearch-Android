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
import org.jsoup.Jsoup
import java.net.URLEncoder

/** 磁力搜索：多源并发 + 结果合并。 */
class MagnetRepository {

    private val client = HttpClient.bt

    suspend fun search(
        query: String,
        mediaType: MediaType = MediaType.MOVIE,
        sources: List<SearchSource> = listOf(SearchSource.PIRATE_BAY, SearchSource.NYAA)
    ): List<MagnetResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()

        // 并发跑所有选中的源
        val tasks = sources.map { source ->
            async {
                runCatching {
                    when (source) {
                        SearchSource.PIRATE_BAY -> searchPirateBay(query, mediaType)
                        SearchSource.YTS -> searchYts(query)
                        SearchSource.NYAA -> searchNyaa(query, mediaType)
                        SearchSource.ONE337X -> search1337x(query)
                        SearchSource.ALL -> emptyList()
                    }
                }.getOrElse { emptyList() }
            }
        }

        val merged = tasks.awaitAll().flatten()
        // 简单去重：按 magnet hash 去重
        merged.distinctBy { it.magnet.substringAfter("btih:").substringBefore("&").lowercase() }
    }

    // ---------- Pirate Bay ----------
    private suspend fun searchPirateBay(query: String, mediaType: MediaType): List<MagnetResult> {
        val cat = mediaType.pirateBayCategory
        val url = "https://thepiratebay.org/search.php?q=${URLEncoder.encode(query, "UTF-8")}&cat=$cat&sortby=9"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0 Android").get().build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val html = resp.body?.string() ?: return@use emptyList()
            val doc = Jsoup.parse(html)
            val out = mutableListOf<MagnetResult>()
            doc.select("table#searchResult tbody tr").forEach { tr ->
                val linkA = tr.selectFirst("a.detLink") ?: return@forEach
                val title = linkA.text().trim()
                val magnet = tr.selectFirst("a[href^=magnet:]")?.attr("href") ?: return@forEach
                val sizeTd = tr.select("td").getOrNull(4)?.text()?.trim() ?: ""
                val seeders = tr.select("td").getOrNull(5)?.text()?.toIntOrNull() ?: 0
                val leechers = tr.select("td").getOrNull(6)?.text()?.toIntOrNull() ?: 0
                out.add(
                    MagnetResult(
                        title = title, magnet = magnet,
                        size = sizeTd, seeders = seeders, leechers = leechers,
                        source = "Pirate Bay", category = mediaType.name.lowercase()
                    )
                )
            }
            out
        }
    }

    // ---------- YTS (只给电影) ----------
    private val YTS_DOMAINS = listOf("yts.ag", "yts.lt", "yts.mx")

    private suspend fun searchYts(query: String): List<MagnetResult> {
        // 多域 fallback（和桌面版一致）
        for (domain in YTS_DOMAINS) {
            val url = "https://$domain/api/v2/list_movies.json?query_term=${URLEncoder.encode(query, "UTF-8")}&limit=10"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
            val result = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val body = resp.body?.string() ?: return@use emptyList()
                    parseYtsJson(body)
                }
            }
            if (result.isSuccess && result.get().isNotEmpty()) {
                return result.get()
            }
        }
        return emptyList()
    }

    private fun parseYtsJson(body: String): List<MagnetResult> {
        val json = org.json.JSONObject(body).optJSONObject("data") ?: return emptyList()
        val movies = json.optJSONArray("movies") ?: return emptyList()
        val out = mutableListOf<MagnetResult>()
        for (i in 0 until movies.length()) {
            val movie = movies.getJSONObject(i)
            val title = movie.optString("title")
            val torrents = movie.optJSONArray("torrents") ?: continue
            for (j in 0 until torrents.length()) {
                val t = torrents.getJSONObject(j)
                val hash = t.optString("hash")
                val magnet = "magnet:?xt=urn:btih:$hash&dn=${URLEncoder.encode(title, "UTF-8")}"
                out.add(
                    MagnetResult(
                        title = "$title [${t.optString("quality")}]",
                        magnet = magnet,
                        size = t.optString("size"),
                        seeders = t.optInt("seeds"),
                        leechers = t.optInt("peers"),
                        source = "YTS", category = "movie"
                    )
                )
            }
        }
        return out
    }

    // ---------- Nyaa.si ----------
    private suspend fun searchNyaa(query: String, mediaType: MediaType): List<MagnetResult> {
        val cat = if (mediaType == MediaType.TV) "0_5" else "0_0"  // 0_0=All, 0_5=Anime
        val url = "https://nyaa.si/?f=0&c=$cat&q=${URLEncoder.encode(query, "UTF-8")}"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val html = resp.body?.string() ?: return@use emptyList()
            val doc = Jsoup.parse(html)
            val out = mutableListOf<MagnetResult>()
            doc.select("table.torrent-list tbody tr").forEach { tr ->
                val title = tr.selectFirst("td:nth-child(2) a")?.text()?.trim() ?: return@forEach
                val magnet = tr.selectFirst("a[href^=magnet:]")?.attr("href") ?: return@forEach
                val size = tr.selectFirst("td:nth-child(4)")?.text()?.trim() ?: ""
                val seeders = tr.selectFirst("td:nth-child(6)")?.text()?.toIntOrNull() ?: 0
                val leechers = tr.selectFirst("td:nth-child(7)")?.text()?.toIntOrNull() ?: 0
                out.add(MagnetResult(title, magnet, size, seeders, leechers, "Nyaa.si", mediaType.name.lowercase()))
            }
            out
        }
    }

    // ---------- 1337x ----------
    private suspend fun search1337x(query: String): List<MagnetResult> {
        val url = "https://1337x.to/search/${URLEncoder.encode(query, "UTF-8")}/1/"
        val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").get().build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val html = resp.body?.string() ?: return@use emptyList()
            val doc = Jsoup.parse(html)
            val out = mutableListOf<MagnetResult>()
            doc.select("table.table-list tbody tr").forEach { tr ->
                val title = tr.selectFirst("td.coll-1 a")?.text()?.trim() ?: return@forEach
                val href = tr.selectFirst("td.coll-1 a")?.attr("href") ?: return@forEach
                // 1337x 列表页没有 magnet，需要详情页，但为简化跳过
                val size = tr.selectFirst("td.coll-4")?.text()?.trim() ?: ""
                val seeders = tr.selectFirst("td.coll-2")?.text()?.toIntOrNull() ?: 0
                val leechers = tr.selectFirst("td.coll-3")?.text()?.toIntOrNull() ?: 0
                out.add(
                    MagnetResult(
                        title = title, magnet = "1337x: need detail",
                        size = size, seeders = seeders, leechers = leechers,
                        source = "1337x", category = "movie"
                    )
                )
            }
            out
        }
    }
}
