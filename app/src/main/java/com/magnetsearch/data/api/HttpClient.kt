package com.magnetsearch.data.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

/** 全局 OkHttp client。
 * Android 上如果有系统代理（Wi-Fi 设置里或 VPN），OkHttp 默认会通过 ProxySelector 读取。
 * 不强制 IPv4 —— 让系统/DNS 自己决定，避免和 VPN/TUN 模式冲突。
 */
object HttpClient {

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val imageLogging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    /** 共享 CookieJar —— 让 Top250 预热、详情页 GET、PoW POST 共用同一套 session cookie。
     *  豆瓣 PoW 挑战需要 cookie 上下文才能通过。 */
    private val sharedCookieJar = object : CookieJar {
        private val cookies = mutableMapOf<String, MutableList<Cookie>>()
        override fun saveFromResponse(url: HttpUrl, cs: List<Cookie>) {
            cookies.getOrPut(url.host) { mutableListOf() }.addAll(cs)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookies[url.host]?.filter { it.matches(url) } ?: emptyList()
        }
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(sharedCookieJar)

    private val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36"

    private fun withCommonHeaders(builder: OkHttpClient.Builder): OkHttpClient.Builder = builder
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", UA)
                .build()
            chain.proceed(req)
        }

    val douban: OkHttpClient by lazy {
        withCommonHeaders(baseBuilder())
            .addInterceptor(logging)
            .build()
    }

    val bt: OkHttpClient by lazy {
        withCommonHeaders(baseBuilder())
            .addInterceptor(logging)
            .build()
    }

    /** Coil 图片加载专用 — 加 UA + Referer（豆瓣 img CDN 反爬虫，418 拒无 UA 请求）。 */
    fun forImage(): OkHttpClient = baseBuilder()
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                .header("Referer", "https://movie.douban.com/")
                .header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .build()
            chain.proceed(req)
        }
        .addInterceptor(imageLogging)
        .build()
}
