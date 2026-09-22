package com.magnetsearch.data.api

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

    private fun baseBuilder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)

    val douban: OkHttpClient by lazy {
        baseBuilder()
            .addInterceptor(logging)
            .build()
    }

    val bt: OkHttpClient by lazy {
        baseBuilder()
            .addInterceptor(logging)
            .build()
    }

    /** Coil 图片加载专用 — 详细 logcat 输出诊断封面加载问题。 */
    fun forImage(): OkHttpClient = baseBuilder()
        .addInterceptor(imageLogging)
        .build()
}
