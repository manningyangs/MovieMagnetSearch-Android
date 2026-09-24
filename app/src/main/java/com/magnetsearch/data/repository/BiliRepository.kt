package com.magnetsearch.data.repository

import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.magnetsearch.data.model.BiliOwner
import com.magnetsearch.data.model.BiliStat
import com.magnetsearch.data.model.BiliVideo
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** B站爬虫 —— 走 WebView 的浏览器环境绕过风控。
 *
 *  背景：B站 2025+ 风控已覆盖 OkHttp API + 桌面 SSR，仅 WebView（完整浏览器栈）能正常请求。
 *  本类提供一个 suspend 函数，内部用 WebView + evaluateJavascript 在浏览器环境里
 *  fetch API，拿到 JSON 后回到原生层解析。
 *
 *  生命周期：
 *    1. 调用方传入 Activity Context（WebView 需要 Activity 级 context）
 *    2. 创建临时 WebView，加载 B站 首页（让它拿到 cookie）
 *    3. evaluateJavascript: fetch('/x/web-interface/ranking/v2?...').then(r=>r.text()).then(t=>BiliBridge.send(t))
 *    4. BiliBridge 收到 text → suspend 函数 resume
 *    5. 销毁 WebView
 */
object BiliRepository {

    /** 分区名 → API rid */
    fun nameToRid(name: String): Int = when (name) {
        "首页推荐" -> -1  // 走 recommend 接口
        "热门" -> 0       // 全站排行
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

    /** 通过 WebView fetch API 拿视频列表。
     *  @param ctx Activity Context（WebView 必须 Activity 级） */
    suspend fun fetchVideos(
        ctx: android.content.Context,
        category: String
    ): Result<List<BiliVideo>> = suspendCancellableCoroutine { cont ->

        val rid = nameToRid(category)
        val apiPath = if (rid == -1) {
            "/x/web-interface/dynamic/recommend?ps=30"
        } else {
            "/x/web-interface/ranking/v2?rid=$rid&type=all"
        }

        val js = """
            (function() {
                fetch('https://api.bilibili.com$apiPath', {
                    credentials: 'include',
                    headers: {
                        'Accept': 'application/json, text/plain, */*',
                        'Referer': 'https://www.bilibili.com/'
                    }
                })
                .then(function(r) { return r.text(); })
                .then(function(t) {
                    try { AndroidBridge.onBiliResponse(t); } catch(e) {}
                })
                .catch(function(err) {
                    try { AndroidBridge.onBiliError(String(err)); } catch(e) {}
                });
            })();
        """.trimIndent()

        val webView = WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // 完全透明，用户看不见
            setBackgroundColor(0)
            alpha = 0f
        }

        val bridge = object {
            @JavascriptInterface
            fun onBiliResponse(text: String) {
                if (!cont.isActive) return
                try {
                    val videos = parseApiResponse(text, category)
                    cont.resume(Result.success(videos))
                } catch (e: Exception) {
                    cont.resume(Result.failure(e))
                }
                webView.post { webView.destroy() }
            }

            @JavascriptInterface
            fun onBiliError(err: String) {
                if (!cont.isActive) return
                cont.resume(Result.failure(Exception("WebView fetch failed: $err")))
                webView.post { webView.destroy() }
            }
        }

        webView.addJavascriptInterface(bridge, "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // 页面加载完，等一小下再 fetch（让 cookie 写入完成）
                view?.postDelayed({
                    view.evaluateJavascript(js, null)
                }, 800)
            }
        }

        cont.invokeOnCancellation {
            try { webView.destroy() } catch (_: Exception) {}
        }

        // 先加载 B站 首页，让浏览器拿到 cookie（buvid3/bili_ticket）
        webView.loadUrl("https://www.bilibili.com")
    }

    private fun parseApiResponse(text: String, category: String): List<BiliVideo> {
        val root = JSONObject(text)
        val code = root.optInt("code", -1)
        if (code != 0) throw Exception("B站风控 $code: ${root.optString("message")}")
        val data = root.optJSONObject("data") ?: return emptyList()

        val list = data.optJSONArray("list")       // ranking/v2
            ?: data.optJSONArray("item")            // recommend
            ?: return emptyList()

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
