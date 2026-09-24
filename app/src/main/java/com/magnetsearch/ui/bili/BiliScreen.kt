package com.magnetsearch.ui.bili

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.magnetsearch.data.model.BiliVideo

// B站分区顺序（和 Repository.nameToTid 对应）
private val CATEGORIES = listOf(
    "热门", "动画", "番剧", "国创", "音乐", "舞蹈", "游戏",
    "知识", "科技", "运动", "汽车", "生活", "美食", "动物圈", "时尚", "资讯", "娱乐"
)
private val BILI_COLOR = Color(0xFFFB7299)

@OptIn(ExperimentalMaterial3Api::class)
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
    val context = androidx.compose.ui.platform.LocalContext.current

    // 首次 + 分类切换时加载
    LaunchedEffect(selectedCategory) {
        vm.loadCategory(selectedCategory)
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏
        TopAppBar(
            title = { Text("B站畅游", fontWeight = FontWeight.SemiBold) },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = BILI_COLOR.copy(alpha = 0.08f)
            ),
            actions = {
                IconButton(onClick = { showSearch = !showSearch }) {
                    Icon(Icons.Default.Search, "搜索", tint = BILI_COLOR)
                }
            }
        )

        // 搜索框
        if (showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索 B 站视频") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
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

        // 状态指示
        when (state) {
            BiliListState.LOADING -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) { CircularProgressIndicator(color = BILI_COLOR) }

            BiliListState.ERROR -> Box(
                Modifier.fillMaxSize(), contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("加载失败", color = Color.Gray)
                    error?.let { Text(it, fontSize = 12.sp, color = Color.Gray) }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.loadCategory(selectedCategory) }) { Text("重试", color = BILI_COLOR) }
                }
            }

            BiliListState.SUCCESS, BiliListState.IDLE -> {
                if (videos.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无数据", color = Color.Gray)
                    }
                } else {
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
        // 封面 + 时长角标
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 10f)) {
            AsyncImage(
                model = video.picHttps,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Crop
            )
            // 时长
            video.duration.takeIf { it > 0 }?.let { sec ->
                Surface(
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    color = Color(0xCC000000),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = formatDuration(sec),
                        color = Color.White,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
            // P 数（多 P 视频）
            if (video.videos > 1) {
                Surface(
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
                    color = BILI_COLOR.copy(alpha = 0.9f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "P${video.videos}",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        // 标题（2 行截断）
        Text(
            text = video.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            lineHeight = 17.sp,
            color = Color(0xFF222222)
        )

        Spacer(Modifier.height(3.dp))

        // UP 主 + 播放量
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = video.owner.name,
                fontSize = 11.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(Modifier.width(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.PlayArrow, null,
                    modifier = Modifier.size(11.dp),
                    tint = Color.Gray
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = formatPlayCount(video.stat.view),
                    fontSize = 11.sp,
                    color = Color.Gray
                )
            }
        }
    }
}
