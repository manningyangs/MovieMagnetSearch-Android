package com.magnetsearch.data.repository

import com.magnetsearch.data.api.HttpClient
import com.magnetsearch.data.model.DoubanDetail
import com.magnetsearch.data.model.DoubanMovie
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.Request
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
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", "https://movie.douban.com/")
            .get()
            .build()

        runCatching {
            HttpClient.douban.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val html = resp.body?.string() ?: return@use null
                val doc = Jsoup.parse(html)
                val d = DoubanDetail(doubanId = doubanId, doubanUrl = url)

                doc.selectFirst("#content h1 span[property='v:itemreviewed']")?.let {
                    d.title = it.text().trim()
                }
                doc.selectFirst(".rating_num[property='v:average']")?.text()?.toFloatOrNull()?.let {
                    d.rating = it
                }
                doc.selectFirst("#mainpic img")?.attr("src")?.let { d.coverUrl = it }

                val info = doc.selectFirst("#info")
                if (info != null) {
                    d.directors = info.select("a[rel='v:directedBy']").map { it.text().trim() }
                    d.actors = info.select("a[rel='v:starring']").map { it.text().trim() }.take(5)
                    d.genres = info.select("span[property='v:genre']").map { it.text().trim() }
                    d.duration = info.selectFirst("span[property='v:runtime']")?.attr("content")
                        ?: info.selectFirst("span[property='v:runtime']")?.text() ?: ""
                    val infoText = info.text()
                    d.countries = Regex("""制片国家/地区[:：]([^\n]+)""").find(infoText)
                        ?.groupValues?.get(1)?.split("/")?.map { it.trim() } ?: emptyList()
                }

                d.fullSummary = doc.selectFirst("span[property='v:summary']")?.text()?.trim()
                    ?: doc.selectFirst(".related-info .indent span")?.text()?.trim()
                    ?: ""

                d
            }
        }.getOrNull()
    }
}
