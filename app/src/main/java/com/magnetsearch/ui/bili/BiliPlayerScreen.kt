package com.magnetsearch.ui.bili

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.OpenInNew
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import com.magnetsearch.data.api.BiliPart
import com.magnetsearch.data.api.BiliPlayUrl
import com.magnetsearch.data.api.BiliPlayUrlApi
import com.magnetsearch.data.api.BiliVideoDetail
import com.magnetsearch.data.model.AppState
import com.magnetsearch.data.model.BiliVideo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val BILI_COLOR = Color(0xFFFB7299)
private const val CACHE_MAX_BYTES = 512L * 1024 * 1024 // 512MB 本地缓存

/** SimpleCache 单例 —— 同一进程里只能有一个实例指向同一目录 */
private object BiliCache {
    private var instance: SimpleCache? = null
    fun get(context: android.content.Context): SimpleCache {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            val newCache = SimpleCache(
                File(context.applicationContext.cacheDir, "bili_video_cache"),
                LeastRecentlyUsedCacheEvictor(CACHE_MAX_BYTES)
            )
            instance = newCache
            return newCache
        }
    }
}

/** 沉浸式全屏控制 —— 隐藏状态栏/导航栏 + 锁定横屏 */
private fun enterFullscreen(activity: Activity) {
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    val window = activity.window
    // 沉浸式
    WindowCompat.setDecorFitsSystemWindows(window, false)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.let {
        it.hide(WindowInsetsCompat.Type.systemBars())
        it.hide(WindowInsetsCompat.Type.navigationBars())
        it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    // keep screen on
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

/** 退出全屏 —— 恢复竖屏 + 显示系统栏 */
private fun exitFullscreen(activity: Activity) {
    activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    val window = activity.window
    WindowCompat.setDecorFitsSystemWindows(window, true)
    val controller = WindowInsetsControllerCompat(window, window.decorView)
    controller.show(WindowInsetsCompat.Type.systemBars())
    controller.show(WindowInsetsCompat.Type.navigationBars())
    controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@SuppressLint("OpaqueUnitKey")
@Composable
fun BiliPlayerScreen(
    video: BiliVideo,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var detail by remember { mutableStateOf<BiliVideoDetail?>(null) }
    var playUrl by remember { mutableStateOf<BiliPlayUrl?>(null) }
    var selectedQn by remember { mutableStateOf(80) }
    var selectedPart by remember { mutableStateOf<BiliPart?>(null) }
    var isFullscreen by remember { mutableStateOf(false) }

    // ====== 共享配置：HTTP + 缓存 + 媒体源工厂 ======
    val mediaSourceFactory = remember(context) {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(mapOf(
                "Referer" to "https://www.bilibili.com/",
                "User-Agent" to BiliPlayUrlApi.UA
            ))
        val cacheFactory = CacheDataSource.Factory()
            .setCache(BiliCache.get(context))
            .setUpstreamDataSourceFactory(httpFactory)
        DefaultMediaSourceFactory(cacheFactory, DefaultExtractorsFactory())
    }

    val player = remember(context, mediaSourceFactory) {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also { it.playWhenReady = true }
    }

    // 详情加载
    LaunchedEffect(video.bvid) {
        isLoading = true
        errorMsg = null
        selectedQn = 80

        val detailResult = BiliPlayUrlApi.getVideoDetail(video.bvid)
        detailResult.fold(
            onSuccess = { d ->
                detail = d
                selectedPart = d.parts.firstOrNull()
                loadPlayUrl(scope, player, mediaSourceFactory, video.bvid, d.cid, selectedQn) { pu ->
                    playUrl = pu
                    if (pu.currentQn != selectedQn) selectedQn = pu.currentQn
                    isLoading = false
                }
            },
            onFailure = { e ->
                errorMsg = e.message ?: "加载失败"
                isLoading = false
            }
        )
    }

    fun switchQn(qn: Int) {
        val d = detail ?: return
        selectedQn = qn
        isLoading = true
        scope.launch {
            loadPlayUrl(scope, player, mediaSourceFactory, video.bvid, selectedPart?.cid ?: d.cid, qn) { pu ->
                playUrl = pu; isLoading = false
            }
        }
    }

    fun switchPart(part: BiliPart) {
        selectedPart = part; isLoading = true
        scope.launch {
            loadPlayUrl(scope, player, mediaSourceFactory, video.bvid, part.cid, selectedQn) { pu ->
                playUrl = pu; isLoading = false
            }
        }
    }

    fun toggleFullscreen() {
        val act = activity ?: return
        isFullscreen = !isFullscreen
        AppState.isVideoFullscreen = isFullscreen
        if (isFullscreen) enterFullscreen(act) else exitFullscreen(act)
    }

    DisposableEffect(Unit) {
        onDispose {
            player.release()
            if (isFullscreen) {
                activity?.let { exitFullscreen(it) }
                AppState.isVideoFullscreen = false
            }
        }
    }

    BackHandler {
        if (isFullscreen) {
            toggleFullscreen() // 先退出全屏
        } else {
            onClose()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // 播放器 —— 全屏时占满屏幕，非全屏时 TopAppBar 底部以下的区域
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = true
                    setControllerAutoShow(true)
                    // 禁用 PlayerView 内置的全屏按钮 —— 我们自己用 Compose 实现
                    setControllerOnFullScreenModeChangedListener(null)
                }
            }
        )

        if (isLoading) {
            CircularProgressIndicator(color = BILI_COLOR, modifier = Modifier.align(Alignment.Center))
        }

        errorMsg?.let { msg ->
            Column(modifier = Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(msg, color = Color.White, fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = BILI_COLOR)) {
                    Text("返回")
                }
            }
        }

        // 非全屏时显示顶部栏
        if (!isFullscreen) {
            TopAppBar(
                title = {
                    Text(detail?.title ?: video.title, maxLines = 1, color = Color.White,
                        fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.url)))
                    }) { Icon(Icons.Default.OpenInNew, "浏览器打开", tint = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }

        // 全屏/退出全屏按钮（常驻右下角，半透明）
        IconButton(
            onClick = { toggleFullscreen() },
            modifier = Modifier
                .align(if (isFullscreen) Alignment.BottomEnd else Alignment.TopEnd)
                .padding(end = 16.dp, bottom = if (isFullscreen) 16.dp else 0.dp, top = if (!isFullscreen) 60.dp else 0.dp)
                .size(44.dp)
                .background(Color(0x55000000), RoundedCornerShape(50))
        ) {
            Icon(
                imageVector = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isFullscreen) "退出全屏" else "全屏播放",
                tint = Color.White
            )
        }

        // 控制条（清晰度 + 分P）—— 全屏时隐藏，避免遮挡
        if (!isFullscreen) {
            Column(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)) {
                playUrl?.let { pu ->
                    if (pu.availableQns.isNotEmpty()) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(pu.availableQns) { qn ->
                                val label = BiliPlayUrlApi.qnToLabel(qn)
                                val isSelected = qn == selectedQn
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSelected) BILI_COLOR else Color(0xCC222222),
                                    modifier = Modifier.clickable { if (!isSelected) switchQn(qn) }
                                ) {
                                    Text(label, color = Color.White, fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                                }
                            }
                        }
                    }
                }

                detail?.parts?.takeIf { it.size > 1 }?.let { parts ->
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(parts) { part ->
                            val isSelected = part.cid == selectedPart?.cid
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (isSelected) Color(0xFF3366CC) else Color(0xCC333333),
                                modifier = Modifier.clickable { if (!isSelected) switchPart(part) }
                            ) {
                                Text("P${part.page} ${part.part}", color = Color.White, fontSize = 11.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 协程内获取 playurl + 用共享的 mediaSourceFactory 准备 ExoPlayer */
private fun loadPlayUrl(
    scope: CoroutineScope,
    player: ExoPlayer,
    mediaSourceFactory: DefaultMediaSourceFactory,
    bvid: String,
    cid: Long,
    qn: Int,
    onReady: (BiliPlayUrl) -> Unit
) {
    scope.launch {
        val puResult = withContext(Dispatchers.IO) { BiliPlayUrlApi.getPlayUrl(bvid, cid, qn) }
        puResult.onSuccess { pu ->
            if (pu.isDash && pu.audioUrl != null) {
                val videoItem = MediaItem.Builder().setUri(pu.videoUrl).setMimeType(MimeTypes.VIDEO_MP4).build()
                val audioItem = MediaItem.Builder().setUri(pu.audioUrl).setMimeType(MimeTypes.AUDIO_MP4).build()
                val videoSrc = mediaSourceFactory.createMediaSource(videoItem)
                val audioSrc = mediaSourceFactory.createMediaSource(audioItem)
                player.setMediaSource(MergingMediaSource(true, videoSrc, audioSrc), true)
            } else {
                val item = MediaItem.Builder().setUri(pu.videoUrl).build()
                player.setMediaSource(mediaSourceFactory.createMediaSource(item), true)
            }
            player.prepare()
            onReady(pu)
        }.onFailure { e ->
            Log.e("BiliPlayer", "loadPlayUrl failed qn=$qn", e)
            if (qn != 64) {
                loadPlayUrl(scope, player, mediaSourceFactory, bvid, cid, 64, onReady)
            }
        }
    }
}
