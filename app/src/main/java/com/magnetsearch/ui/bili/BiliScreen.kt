package com.magnetsearch.ui.bili

import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.magnetsearch.data.model.BiliVideo
import com.magnetsearch.data.repository.BiliRepository

private val CATEGORIES = listOf(
    "热门", "动画", "番剧", "国创", "音乐", "舞蹈", "游戏",
    "知识", "科技", "运动", "汽车", "生活", "美食", "动物圈", "时尚", "资讯", "娱乐"
)
private val BILI_COLOR = Color(0xFFFB7299)

@OptIn(ExperimentalMaterial3Api::class)
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun BiliScreen(
    modifier: Modifier = Modifier,
    vm: BiliViewModel = viewModel()
) {
    var selectedCategory by remember { mutableStateOf("热门") }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val videos by vm.list.collectAsState()
    val state by vm.state.collectAsState()
    val error by vm.error.collectAsState()
    val context = LocalContext.current

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏
        TopAppBar(
            title = { Text("B站畅游", fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BILI_COLOR.copy(alpha = 0.08f)
            ),
            actions = {
                IconButton(onClick = { vm.retry() }) {
                    Icon(Icons.Default.Refresh, "刷新", tint = BILI_COLOR)
                }
                IconButton(onClick = { showSearch = !showSearch }) {
                    Icon(Icons.Default.Search, "搜索", tint = BILI_COLOR)
                }
            }
        )

        // 搜索框 → 跳 B站 搜索页
        if (showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索 B 站视频") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, tint = BILI_COLOR) },
                trailingIcon = {
                    TextButton(onClick = {
                        if (searchQuery.isNotBlank()) {
                            context.startActivity(Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://search.bilibili.com/all?keyword=${Uri.encode(searchQuery)}")
                            ))
                        }
                    }) { Text("去 B 站搜", color = BILI_COLOR) }
                }
            )
        }

        // 分类横滑
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(CATEGORIES) { cat ->
                FilterChip(
                    selected = cat == selectedCategory,
                    onClick = { selectedCategory = cat },
                    label = { Text(cat, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BILI_COLOR.copy(alpha = 0.15f),
                        selectedLabelColor = BILI_COLOR
                    )
                )
            }
        }

        // 主内容区：透明 WebView（后台 fetch）+ 原生封面网格（前景）
        Box(Modifier.fillMaxSize()) {

            // === invisible WebView（后台 fetch API，用户完全看不见） ===
            AndroidView(
                modifier = Modifier
                    .matchParentSize()
                    .clip(RoundedCornerShape(0.dp))
                    .background(Color.Transparent),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_DEFAULT
                        // 桌面 UA —— 留在 www.bilibili.com，不跳 m 站
                        settings.userAgentString = BiliRepository.DESKTOP_UA
                        // 完全透明
                        setBackgroundColor(0)
                        alpha = 0f
                        // attach 到 window 后交给 ViewModel 管理
                        vm.attachWebView(this)
                    }
                },
                update = { /* WebView 只创建一次 */ }
            )

            // === 前景：原生 UI ===
            when (state) {
                BiliListState.LOADING -> CircularProgressIndicator(
                    color = BILI_COLOR,
                    modifier = Modifier.align(Alignment.Center)
                )
                BiliListState.ERROR -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("加载失败", color = Color.Gray, fontSize = 14.sp)
                    error?.let { Text(it, fontSize = 12.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp)) }
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(
                        onClick = { vm.retry() },
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = BILI_COLOR.copy(alpha = 0.15f))
                    ) {
                        Icon(Icons.Default.Refresh, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重试", color = BILI_COLOR)
                    }
                }
                BiliListState.SUCCESS, BiliListState.IDLE -> {
                    if (videos.isEmpty() && state == BiliListState.SUCCESS) {
                        Text("暂无视频", color = Color.Gray, modifier = Modifier.align(Alignment.Center))
                    } else if (videos.isNotEmpty()) {
                        VideoGrid(
                            videos = videos,
                            onVideoClick = { video ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(video.url)))
                            }
                        )
                    }
                }
            }
        }
    }

    // 分类切换 → 触发 WebView + fetch
    LaunchedEffect(selectedCategory) {
        vm.loadCategory(selectedCategory)
    }
}

@Composable
private fun VideoGrid(
    videos: List<BiliVideo>,
    onVideoClick: (BiliVideo) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(videos, key = { it.bvid }) { video ->
            VideoCard(video, onClick = { onVideoClick(video) })
        }
    }
}

@Composable
private fun VideoCard(video: BiliVideo, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
    ) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            AsyncImage(
                model = video.picHttps,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            video.duration.takeIf { it > 0 }?.let { sec ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    color = Color(0xCC000000), shape = RoundedCornerShape(4.dp)
                ) {
                    Text(formatDuration(sec), color = Color.White, fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                }
            }
            if (video.videos > 1) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    color = BILI_COLOR.copy(alpha = 0.92f), shape = RoundedCornerShape(4.dp)
                ) {
                    Text("P${video.videos}", color = Color.White, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = video.title, maxLines = 2, overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp, fontWeight = FontWeight.Medium,
            lineHeight = 17.sp, color = Color(0xFF1F1F1F)
        )
        Spacer(Modifier.height(3.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(video.owner.name, fontSize = 11.sp, color = Color.Gray,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(11.dp), tint = Color.Gray)
                Spacer(Modifier.width(2.dp))
                Text(formatPlayCount(video.stat.view), fontSize = 11.sp, color = Color.Gray)
            }
        }
    }
}
