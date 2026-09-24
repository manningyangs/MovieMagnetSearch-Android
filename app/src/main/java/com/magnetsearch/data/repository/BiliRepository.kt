package com.magnetsearch.data.repository

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log
import com.magnetsearch.data.model.BiliOwner
import com.magnetsearch.data.model.BiliStat
import com.magnetsearch.data.model.BiliVideo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** B站爬虫 —— 在 WebView 上 evaluateJavascript 跑 fetch API 拿数据。
 *
 *  关键：WebView 必须已经被 attach 到 Activity 的 window（由 UI 层保证），
 *  否则 postDelayed / evaluateJavascript 里的消息队列不会处理。
 *
 *  桌面 UA + www.bilibili.com（不是 m.bilibili.com）→ fetch api.bilibili.com 同源，无 CORS。
 */
object BiliRepository {

    private const val TAG = "BiliRepo"

    /** 分区名 → API rid */
    fun nameToRid(name: String): Int = when (name) {
        "首页推荐" -> -1
        "热门" -> 0
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
        else -> 0
    }

    /** 桌面 Chrome UA —— 让 WebView 留在 www.bilibili.com（不跳 m.bilibili.com）。 */
    val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** 在已 attach 的 WebView 上 fetch 视频列表。 */
    suspend fun fetchVideosOnWebView(
        webView: WebView,
        category: String
    ): Result<List<BiliVideo>> = suspendCancellableCoroutine { cont ->

        val rid = nameToRid(category)
        val apiUrl = if (rid == -1) {
            "https://api.bilibili.com/x/web-interface/dynamic/recommend?ps=30"
        } else {
            "https://api.bilibili.com/x/web-interface/ranking/v2?rid=$rid&type=all"
        }

        // JS：fetch API + 控制台日志 + AndroidBridge 回调
        val js = """
            (function() {
                try {
                    console.log('[BiliRepo] fetch start: $apiUrl');
                    fetch('$apiUrl', {
                        credentials: 'include',
                        headers: { 'Accept': 'application/json, text/plain, */*' }
                    })
                    .then(function(r) {
                        console.log('[BiliRepo] fetch status=' + r.status + ' ok=' + r.ok);
                        return r.text();
                    })
                    .then(function(t) {
                        console.log('[BiliRepo] text len=' + t.length);
                        try { AndroidBridge.onResult(t); }
                        catch(e) { console.log('[BiliRepo] bridge err: ' + e.message); }
                    })
                    .catch(function(err) {
                        console.log('[BiliRepo] fetch FAIL: ' + err);
                        try { AndroidBridge.onError(String(err)); } catch(e2) {}
                    });
                } catch(e) {
                    console.log('[BiliRepo] outer err: ' + e.message);
                    try { AndroidBridge.onError(String(e.message)); } catch(e2) {}
                }
            })();
        """.trimIndent()

        val bridge = object {
            @JavascriptInterface
            fun onResult(text: String) {
                Log.d(TAG, "onResult len=${text.length} head=${text.take(120)}")
                if (!cont.isActive) return
                try {
                    val videos = parseApiResponse(text)
                    Log.d(TAG, "parsed ${videos.size} videos")
                    cont.resume(Result.success(videos))
                } catch (e: Exception) {
                    Log.e(TAG, "parse failed", e)
                    cont.resume(Result.failure(e))
                }
            }

            @JavascriptInterface
            fun onError(err: String) {
                Log.e(TAG, "onError: $err")
                if (!cont.isActive) return
                cont.resume(Result.failure(Exception("fetch failed: $err")))
            }
        }

        // 每次 fetch 都重新 set bridge（之前的可能已被 GC）
        webView.addJavascriptInterface(bridge, "AndroidBridge")

        // 先检查 WebView 当前 URL —— 如果已经在 www.bilibili.com 且 cookie 已 warmup，直接 fetch
        // 否则先 loadUrl 预热
        val currentUrl = webView.url
        if (currentUrl?.startsWith("https://www.bilibili.com") == true) {
            Log.d(TAG, "already on bilibili, fetching directly")
            webView.evaluateJavascript(js) { result ->
                Log.d(TAG, "eval immediate result=$result")
            }
        } else {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, url: String?) {
                    Log.d(TAG, "page finished: $url, waiting 1.5s for cookies")
                    v?.postDelayed({
                        Log.d(TAG, "running evaluateJavascript now")
                        v.evaluateJavascript(js) { r -> Log.d(TAG, "eval result=$r") }
                    }, 1500)
                }
            }
            Log.d(TAG, "loading www.bilibili.com for cookie warmup")
            webView.loadUrl("https://www.bilibili.com")
        }

        cont.invokeOnCancellation { /* WebView 不关，UI 层管 */ }
    }

    private fun parseApiResponse(text: String): List<BiliVideo> {
        val root = JSONObject(text)
        val code = root.optInt("code", -1)
        if (code != 0) throw Exception("B站风控 code=$code msg=${root.optString("message")}")
        val data = root.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: data.optJSONArray("item") ?: return emptyList()

        val result = ArrayList<BiliVideo>(list.length())
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            try { parseVideo(obj)?.let { result.add(it) } } catch (_: Exception) {}
        }
        return result
    }

    private fun parseVideo(obj: JSONObject): BiliVideo? {
        val bvid = obj.optString("bvid").ifBlank { return null }
        val title = obj.optString("title").ifBlank { "未知标题" }
        val pic = obj.optString("pic")
        val aid = obj.optLong("aid", 0L)
        val tid = obj.optInt("tid", 0)
        val tname = obj.optString("tname", "")
        val pubdate = obj.optLong("pubdate", 0L)
        val duration = obj.optInt("duration", 0)
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
                view = stat.optLong("view", 0), danmaku = stat.optLong("danmaku", 0),
                reply = stat.optLong("reply", 0), favorite = stat.optLong("favorite", 0),
                coin = stat.optLong("coin", 0), share = stat.optLong("share", 0),
                like = stat.optLong("like", 0),
            )
        } else {
            BiliStat(view = obj.optLong("play", 0), danmaku = 0, reply = 0,
                favorite = 0, coin = 0, share = 0, like = 0)
        }

        return BiliVideo(
            bvid = bvid, aid = aid, title = title, pic = pic,
            tid = tid, tname = tname, pubdate = pubdate, duration = duration,
            desc = obj.optString("desc", ""), owner = biliOwner, stat = biliStat, videos = videos,
        )
    }
}
