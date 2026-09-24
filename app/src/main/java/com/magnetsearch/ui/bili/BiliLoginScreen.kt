package com.magnetsearch.ui.bili

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.magnetsearch.data.api.BiliPlayUrlApi

private val BILI_COLOR = Color(0xFFFB7299)

/**
 * B站登录页 —— 内嵌 WebView 加载 B站登录页面
 *
 * 登录完成后 B站会把 SESSDATA 写入 Cookie，CookieManager 会自动保存。
 * 我们只需要检查 CookieManager 里有没有 SESSDATA 就知道登录成功没。
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BiliLoginScreen(
    onClose: () -> Unit,
    onLoggedIn: () -> Unit = {}
) {
    val context = LocalContext.current
    var loggedIn by remember { mutableStateOf(BiliPlayUrlApi.isLoggedIn()) }
    var progress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler { onClose() }

    // 定期检查登录状态（cookie 更新有延迟）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            if (BiliPlayUrlApi.isLoggedIn() && !loggedIn) {
                loggedIn = true
                onLoggedIn()
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.setSupportZoom(true)
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                    settings.userAgentString = BiliPlayUrlApi.UA

                    // 关键：接受并持久化 B站 cookie（SESSDATA 就是存这里）
                    val cm = CookieManager.getInstance()
                    cm.setAcceptCookie(true)
                    cm.setAcceptThirdPartyCookies(this, true)
                    CookieManager.getInstance().setCookie("https://www.bilibili.com", "")

                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?): Boolean {
                            val u = r?.url?.toString() ?: return false
                            // 登录跳转回首页 → 大概率登录成功
                            if (u == "https://www.bilibili.com/" || u.contains("bilibili.com/?spm_id_from")) {
                                if (BiliPlayUrlApi.isLoggedIn()) {
                                    loggedIn = true
                                    onLoggedIn()
                                }
                            }
                            return false
                        }
                    }
                    webChromeClient = android.webkit.WebChromeClient().also {
                        // progress 回调没用到，简单处理即可
                    }

                    // 加载 B站登录页
                    loadUrl("https://passport.bilibili.com/login")
                }.also { webView = it }
            }
        )

        // 进度条
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).height(2.dp),
                color = BILI_COLOR, trackColor = BILI_COLOR.copy(alpha = 0.2f)
            )
        }

        // 顶部栏
        TopAppBar(
            title = { Text("B站登录", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BILI_COLOR.copy(alpha = 0.08f)),
            actions = {
                if (loggedIn) {
                    TextButton(onClick = onClose) {
                        Text("已登录 ✓ 完成", color = BILI_COLOR, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    }
                }
            }
        )
    }
}
