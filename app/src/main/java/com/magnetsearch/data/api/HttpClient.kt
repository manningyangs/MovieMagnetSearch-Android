package com.magnetsearch.data.api

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** 全局 OkHttp client。
 *
 *  bt client 关键：
 *  - IPv4 强制 Dns：BT 站 IPv6 基本都被 GFW 挡（即使开梯子也只代理 IPv4），
 *    必须只解析 IPv4 地址，否则会在 IPv6 上卡 15s timeout 才 fallback。
 *  - 桌面 Chrome UA：BT 站对 Mobile UA 返回更少结果甚至拦截。
 */
object HttpClient {

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    private val imageLogging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    /** IPv4 强制 Dns —— OkHttp 默认会同时解析 v4 和 v6，
     *  但 BT 站（nyaa.si / 1337x / yts.mx）返回的 IPv6 地址都连不上，
     *  OkHttp 会在 v6 上等满 15s timeout 才重试 v4 → 结果页面挂 15s+。
     *  这里过滤只返回 Inet4Address。 */
    private val IPv4_ONLY_DNS = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = InetAddress.getAllByName(hostname).toList()
            val v4 = all.filterIsInstance<Inet4Address>()
            return if (v4.isNotEmpty()) v4 else all
        }
    }

    /** 共享 CookieJar —— 让 Top250 预热、详情页 GET、PoW POST 共用同一套 session cookie。
     *  ⚠️ 关键：Cookie 不按 url.host 存储 —— 因为跨域 cookie（Domain=.douban.com）
     *  设置在 sec.douban.com 上，但需要在 movie.douban.com 请求时发送。
     *  必须让 OkHttp 的 Cookie.matches() 自己做 domain 匹配。 */
    private val sharedCookieJar = object : CookieJar {
        private val cookies = mutableListOf<Cookie>()
        override fun saveFromResponse(url: HttpUrl, cs: List<Cookie>) {
            cookies.addAll(cs)
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            return cookies.filter { it.matches(url) }
        }
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .cookieJar(sharedCookieJar)

    private val UA_DESKTOP = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private val UA_MOBILE = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"

    private fun withCommonHeaders(builder: OkHttpClient.Builder, ua: String = UA_DESKTOP): OkHttpClient.Builder = builder
        .addInterceptor { chain ->
            val req = chain.request().newBuilder()
                .header("User-Agent", ua)
                .build()
            chain.proceed(req)
        }

    /** douban 主 client —— IPv4 Dns + 桌面 UA（同上 session cookie + PoW）。 */
    val douban: OkHttpClient by lazy {
        withCommonHeaders(baseBuilder(), UA_DESKTOP)
            .dns(IPv4_ONLY_DNS)
            .addInterceptor(logging)
            .build()
    }

    /** bt 搜索 client —— IPv4 Dns + 桌面 Chrome UA。
     *  不传代理（让系统 ProxySelector 自动读取 Wi-Fi/VPN 代理）。 */
    val bt: OkHttpClient by lazy {
        withCommonHeaders(baseBuilder(), UA_DESKTOP)
            .dns(IPv4_ONLY_DNS)
            .addInterceptor(logging)
            .build()
    }

    /** Coil 图片加载专用 — 加 UA + Referer（豆瓣 img CDN 反爬虫，418 拒无 UA 请求）。 */
    fun forImage(): OkHttpClient = baseBuilder()
        .dns(IPv4_ONLY_DNS)
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
