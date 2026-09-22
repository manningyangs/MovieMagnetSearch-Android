package com.magnetsearch.data.api

import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

/** 全局 OkHttp client。
 * 关键：强制 IPv4（Android 默认优先 IPv6，但海外 BT 站和 CDN 的 IPv6 普遍不通/被墙）。
 */
object HttpClient {

    /** 只返回 IPv4 地址的自定义 DNS。 */
    private val IPv4_ONLY_DNS = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val all = InetAddress.getAllByName(hostname).toList()
            val v4 = all.filterIsInstance<Inet4Address>()
            return if (v4.isNotEmpty()) v4 else all
        }
    }

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .dns(IPv4_ONLY_DNS)

    val douban: OkHttpClient by lazy {
        baseBuilder().build()
    }

    val bt: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        baseBuilder()
            .addInterceptor(logging)
            .build()
    }

    /** 创建 Coil 专用的 OkHttp client（图片加载也强制 IPv4）。 */
    fun forImage(): OkHttpClient = baseBuilder().build()
}
