package com.magnetsearch.ui.bili

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

// B站分区
private val CATEGORIES = listOf(
    "热门", "动画", "番剧", "音乐", "舞蹈", "游戏", "知识",
    "科技", "运动", "汽车", "生活", "美食", "动物圈", "时尚", "资讯", "娱乐"
)

private val BILI_COLOR = Color(0xFFFB7299)

private fun categoryUrl(cat: String): String = when (cat) {
    "热门" -> "https://www.bilibili.com/v/popular/rank/all"
    "动画" -> "https://www.bilibili.com/v/anime/"
    "番剧" -> "https://www.bilibili.com/v/bangumi/"
    "音乐" -> "https://www.bilibili.com/v/music/"
    "舞蹈" -> "https://www.bilibili.com/v/dance/"
    "游戏" -> "https://www.bilibili.com/v/game/"
    "知识" -> "https://www.bilibili.com/v/knowledge/"
    "科技" -> "https://www.bilibili.com/v/tech/"
    "运动" -> "https://www.bilibili.com/v/sports/"
    "汽车" -> "https://www.bilibili.com/v/car/"
    "生活" -> "https://www.bilibili.com/v/life/"
    "美食" -> "https://www.bilibili.com/v/food/"
    "动物圈" -> "https://www.bilibili.com/v/animal/"
    "时尚" -> "https://www.bilibili.com/v/fashion/"
    "资讯" -> "https://www.bilibili.com/v/information/"
    "娱乐" -> "https://www.bilibili.com/v/ent/"
    else -> "https://search.bilibili.com/all?keyword=${Uri.encode(cat)}"
}

private const val BILI_HOME = "https://www.bilibili.com"

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BiliScreen(
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf("热门") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var currentTitle by remember { mutableStateOf("B站畅游") }
    val context = androidx.compose.ui.platform.LocalContext.current
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // Back 键：WebView 有历史先 goBack，否则让 Activity 处理（可能跳别的 Tab 或退出）
    BackHandler(enabled = canGoBack) {
        webViewRef.value?.goBack()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏（WebView 导航）
        TopAppBar(
            title = { Text(currentTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false) },
            navigationIcon = {
                IconButton(
                    onClick = { webViewRef.value?.goBack() },
                    enabled = canGoBack
                ) { Icon(Icons.Default.ArrowBack, "后退") }
            },
            actions = {
                IconButton(onClick = { webViewRef.value?.reload() }) {
                    Icon(Icons.Default.Refresh, "刷新")
                }
                IconButton(onClick = { showSearch = !showSearch }) {
                    Icon(Icons.Default.Search, "搜索")
                }
                IconButton(onClick = {
                    webViewRef.value?.let { wv ->
                        val url = wv.url
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    }
                }) {
                    Icon(Icons.Default.OpenInNew, "浏览器打开")
                }
            }
        )

        // 加载进度条
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = BILI_COLOR,
                trackColor = BILI_COLOR.copy(alpha = 0.2f)
            )
        }

        // 搜索框
        if (showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索视频") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, tint = BILI_COLOR) },
                trailingIcon = {
                    TextButton(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                webViewRef.value?.loadUrl(
                                    "https://search.bilibili.com/all?keyword=${Uri.encode(searchQuery)}"
                                )
                            }
                        }
                    ) { Text("搜索", color = BILI_COLOR) }
                }
            )
        }

        // 分类横滑
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(CATEGORIES) { cat ->
                FilterChip(
                    selected = cat == selectedCategory,
                    onClick = {
                        selectedCategory = cat
                        webViewRef.value?.loadUrl(categoryUrl(cat))
                    },
                    label = { Text(cat, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BILI_COLOR.copy(alpha = 0.15f),
                        selectedLabelColor = BILI_COLOR
                    )
                )
            }
        }

        // WebView
        Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        webViewRef.value = this

                        // === 设置 ===
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.databaseEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        settings.loadWithOverviewMode = true
                        settings.useWideViewPort = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        settings.allowFileAccess = false
                        settings.savePassword = true
                        settings.userAgentString =
                            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

                        // 硬件加速
                        setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                        isHorizontalScrollBarEnabled = false
                        isVerticalScrollBarEnabled = false
                        overScrollMode = android.view.View.OVER_SCROLL_ALWAYS

                        // 下载支持
                        setDownloadListener { url, _, _, _, _ ->
                            try {
                                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            } catch (_: Exception) {}
                        }

                        // === Client ===
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                // 外部 App 协议跳外部
                                return if (url.startsWith("intent://") || url.startsWith("bili://")) {
                                    try {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    } catch (_: Exception) {}
                                    true
                                } else {
                                    false
                                }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                canGoBack = view?.canGoBack() == true
                                view?.title?.let { currentTitle = it.ifBlank { "B站畅游" } }
                            }
                        }

                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                                if (newProgress == 100) progress = 0
                            }
                            override fun onReceivedTitle(view: WebView?, title: String?) {
                                title?.let { currentTitle = it.ifBlank { "B站畅游" } }
                            }
                        }

                        loadUrl(BILI_HOME)
                    }
                },
                update = { /* WebView 状态由自身维护，不需要 update 重设 */ }
            )
        }
    }
}
