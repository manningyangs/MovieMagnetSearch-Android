package com.magnetsearch.data.repository

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import com.magnetsearch.data.model.BiliOwner
import com.magnetsearch.data.model.BiliStat
import com.magnetsearch.data.model.BiliVideo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** B站爬虫 —— WebView 渲染分区页后从 DOM 抓取视频卡片数据。
 *
 *  为什么不走 API：
 *    OkHttp → -352（无浏览器指纹）
 *    WebView fetch API → -352（GFW 标记了当前 IP 段）
 *  为什么 DOM 抓取能通：
 *    WebView 加载 https://www.bilibili.com/v/{分区}/
 *    → CSR 渲染时 B站 前端 JS 自己调 API（完整浏览器 cookie + 同源 + Chromium 指纹）
 *    → API 返回成功，视频卡片渲染到 DOM
 *    → 我们 evaluateJavascript 从 DOM 里 querySelectorAll 拿数据
 */
object BiliRepository {

    private const val TAG = "BiliRepo"

    /** 分区名 → 分区 URL 路径 */
    fun nameToPath(name: String): String = when (name) {
        "首页推荐" -> "/"
        "热门" -> "/v/popular/rank/all"
        "动画" -> "/v/douga/"
        "番剧" -> "/v/anime/"
        "国创" -> "/v/guochuang/"
        "音乐" -> "/v/music/"
        "舞蹈" -> "/v/dance/"
        "游戏" -> "/v/game/"
        "知识" -> "/v/knowledge/"
        "科技" -> "/v/tech/"
        "运动" -> "/v/sports/"
        "汽车" -> "/v/car/"
        "生活" -> "/v/life/"
        "美食" -> "/v/food/"
        "动物圈" -> "/v/animal/"
        "时尚" -> "/v/fashion/"
        "资讯" -> "/v/information/"
        "娱乐" -> "/v/ent/"
        else -> "/"
    }

    /** 桌面 Chrome UA —— 让 WebView 留在 www.bilibili.com（不跳 m 站）。 */
    val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 在 WebView 上渲染分区页 + DOM 抓取视频数据。 */
    suspend fun scrapeVideosFromDom(
        webView: WebView,
        category: String
    ): Result<List<BiliVideo>> = suspendCancellableCoroutine { cont ->

        val path = nameToPath(category)
        val fullUrl = "https://www.bilibili.com$path"

        // DOM 抓取 JS：兼容 B站 不同页面结构
        val scrapeJS = """
            (function() {
                // 尝试多种可能的视频卡片选择器（B站结构经常变）
                var selectors = [
                    '.video-card', '.video-list-item', '.feed-card',
                    '.rank-item', '.popular-video-item', '.recommended-swipe',
                    '[class*="video-list"] a[href*="/video/"]',
                    'a[href*="/video/BV"]'
                ];
                var seen = new Set();
                var results = [];

                selectors.forEach(function(sel) {
                    document.querySelectorAll(sel).forEach(function(el) {
                        // 从 a 标签 href 提取 bvid
                        var link = el.tagName === 'A' ? el : el.querySelector('a[href*="/video/"]');
                        if (!link) return;
                        var href = link.getAttribute('href') || '';
                        var bvidMatch = href.match(/BV[\w]+/);
                        if (!bvidMatch) return;
                        var bvid = bvidMatch[0];
                        if (seen.has(bvid)) return;
                        seen.add(bvid);

                        // 标题
                        var titleEl = el.querySelector('.title, .video-title, h3, [title]') || link;
                        var title = (titleEl.getAttribute('title') || titleEl.textContent || '').trim();
                        if (!title) return;

                        // 封面
                        var img = el.querySelector('img');
                        var pic = '';
                        if (img) {
                            pic = img.getAttribute('data-src') || img.getAttribute('data-lazy-src') || img.getAttribute('src') || '';
                        }
                        // 如果 pic 是相对路径，补全
                        if (pic && pic.startsWith('//')) pic = 'https:' + pic;

                        results.push({bvid: bvid, title: title, pic: pic});
                    });
                });

                console.log('[BiliRepo] DOM scrape found ' + results.length + ' videos');
                try { AndroidBridge.onResult(JSON.stringify(results)); }
                catch(e) { console.log('[BiliRepo] bridge err: ' + e.message); }
            })();
        """.trimIndent()

        val bridge = object {
            @JavascriptInterface
            fun onResult(text: String) {
                Log.d(TAG, "DOM scrape result len=${text.length}")
                if (!cont.isActive) return
                try {
                    val jsonArr = JSONArray(text)
                    val videos = ArrayList<BiliVideo>(jsonArr.length())
                    for (i in 0 until jsonArr.length()) {
                        val obj = jsonArr.optJSONObject(i) ?: continue
                        val bvid = obj.optString("bvid")
                        val title = obj.optString("title")
                        val pic = obj.optString("pic")
                        if (bvid.isBlank() || title.isBlank()) continue
                        videos.add(BiliVideo(
                            bvid = bvid, aid = 0, title = title, pic = pic,
                            tid = 0, tname = "", pubdate = 0, duration = 0,
                            desc = "",
                            owner = BiliOwner(0, "", ""),
                            stat = BiliStat(0, 0, 0, 0, 0, 0, 0),
                            videos = 1
                        ))
                    }
                    Log.d(TAG, "parsed ${videos.size} videos from DOM")
                    cont.resume(Result.success(videos))
                } catch (e: Exception) {
                    Log.e(TAG, "DOM parse failed", e)
                    cont.resume(Result.failure(e))
                }
            }
            @JavascriptInterface
            fun onError(err: String) {
                Log.e(TAG, "DOM scrape error: $err")
                if (!cont.isActive) return
                cont.resume(Result.failure(Exception("DOM scrape failed: $err")))
            }
        }

        webView.addJavascriptInterface(bridge, "AndroidBridge")

        val currentUrl = webView.url ?: ""
        val alreadyOnPath = currentUrl.contains(path.trim('/')) && currentUrl.startsWith("https://www.bilibili.com")
        if (alreadyOnPath) {
            Log.d(TAG, "already on $currentUrl, scraping directly")
        } else {
            // 否则重新加载
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    Log.d(TAG, "page finished: $url, will scrape after CSR render")
                    // B站 CSR 需要时间渲染卡片——等 4 秒让前端 JS 跑完 + API 返回 + DOM 生成
                    scrapeAfterDelay(webView, scrapeJS, 4000)
                }
            }
            Log.d(TAG, "loading $fullUrl for DOM scrape")
            webView.loadUrl(fullUrl)
        }

        // 兜底：如果 WebView 已经在目标路径（已走 alreadyOnPath），需要发一次 scrape
        // webViewClient.onPageFinished 里也会调用 scrapeAfterDelay，两边都会跑
        if (alreadyOnPath) {
            scrapeAfterDelay(webView, scrapeJS, 1500)
        }

        cont.invokeOnCancellation { /* WebView 不关 */ }
    }

    private fun scrapeAfterDelay(webView: WebView, js: String, delayMs: Long) {
        webView.postDelayed({
            Log.d(TAG, "evaluating DOM scrape JS")
            webView.evaluateJavascript(js) { r -> Log.d(TAG, "DOM scrape eval result=$r") }
        }, delayMs)
    }
}
