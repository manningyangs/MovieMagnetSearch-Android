package com.magnetsearch.ui.douban

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.magnetsearch.data.model.DoubanComment
import com.magnetsearch.data.model.DoubanDetail
import com.magnetsearch.data.model.DoubanMovie
import com.magnetsearch.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubanScreen(
    onMagnetSearch: (String) -> Unit,
    vm: DoubanViewModel = viewModel()
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    var searchText by remember { mutableStateOf("") }

    // ========== 详情页 ==========
    if (state.selectedMovie != null) {
        BackHandler { vm.backToList() }
        DoubanDetailScreen(
            movie = state.selectedMovie!!,
            detail = state.detail,
            detailLoading = state.detailLoading,
            detailError = state.detailError,
            onBack = { vm.backToList() },
            onSearch = { onMagnetSearch(it) },
            onOpenDouban = {
                state.selectedMovie?.doubanUrl?.let { url ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
            }
        )
        return
    }

    // ========== 列表页 ==========
    Scaffold(
        topBar = { TopAppBar(title = { Text("豆瓣发现") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 搜索栏
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入片名搜索豆瓣") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                Button(onClick = { if (searchText.isNotBlank()) vm.search(searchText) }) {
                    Text("搜索")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = { vm.loadTop250() }) {
                    Icon(Icons.Default.Home, null, modifier = Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("豆瓣 Top 250")
                }
            }

            // 状态 / 列表
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.movies.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                    state.error != null -> Text(
                        text = state.error!!,
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Red
                    )
                    state.movies.isEmpty() -> Text(
                        text = "点击「豆瓣 Top 250」开始",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Gray
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.movies) { movie ->
                            MovieCard(
                                movie = movie,
                                onSearch = { onMagnetSearch(it) },
                                onClick = { vm.selectMovie(movie) }
                            )
                        }
                        item {
                            Text(
                                text = if (state.isTop250) "共 ${state.movies.size} 部 · 点击卡片查看详情"
                                       else "共 ${state.movies.size} 条结果 · 点击卡片查看详情",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (state.movies.isEmpty() && !state.isLoading) {
            vm.loadTop250()
        }
    }
}

// ============================================================
// 详情页 UI
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DoubanDetailScreen(
    movie: DoubanMovie,
    detail: DoubanDetail?,
    detailLoading: Boolean,
    detailError: String?,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenDouban: () -> Unit
) {
    val context = LocalContext.current
    val coverUrl = detail?.coverUrl?.takeIf { it.isNotBlank() } ?: movie.coverUrl

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电影详情") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // === 顶部：封面 + 基本信息 ===
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = coverUrl,
                            contentDescription = "封面",
                            modifier = Modifier
                                .width(100.dp)
                                .height(141.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFE0E0E0)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            placeholder = painterResource(id = android.R.drawable.ic_menu_report_image),
                            error = painterResource(id = android.R.drawable.ic_menu_report_image)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .width(100.dp)
                                .height(141.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFE0E0E0)),
                            contentAlignment = Alignment.Center
                        ) { Text("封面", color = Color.Gray) }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (movie.rank > 0) {
                                Text("#${movie.rank}", color = RedRank, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                movie.title + if (movie.originalTitle.isNotBlank() && movie.originalTitle != movie.title)
                                    " / ${movie.originalTitle}" else "",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        if (movie.year.isNotBlank()) Text(movie.year, color = TextSecondary, fontSize = 13.sp)

                        val rating = detail?.rating?.takeIf { it > 0f } ?: movie.rating
                        if (rating > 0f) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "★ $rating",
                                color = Accent,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                        }
                    }
                }
            }

            // === 详细信息（类型 / 国家 / 时长 / 导演 / 演员） ===
            detail?.let { d ->
                if (d.genres.isNotEmpty() || d.countries.isNotEmpty() || d.duration.isNotBlank()) {
                    item {
                        val infos = mutableListOf<String>()
                        if (d.genres.isNotEmpty()) infos.add("类型: ${d.genres.joinToString(" / ")}")
                        if (d.countries.isNotEmpty()) infos.add("地区: ${d.countries.joinToString(" / ")}")
                        if (d.duration.isNotBlank()) infos.add("时长: ${d.duration} 分钟")
                        Text(
                            infos.joinToString("\n"),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
                if (d.directors.isNotEmpty()) {
                    item {
                        Text("导演: ${d.directors.joinToString(" / ")}", fontSize = 13.sp, color = TextPrimary, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }
                if (d.actors.isNotEmpty()) {
                    item {
                        Text(
                            "主演: ${d.actors.joinToString(" / ")}",
                            fontSize = 13.sp,
                            color = TextSecondary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }
                }
            }

            // === 按钮 ===
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onSearch(movie.title) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("搜磁力")
                    }
                    OutlinedButton(
                        onClick = onOpenDouban,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("打开豆瓣")
                    }
                }
                HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
            }

            // === 加载中 / 错误 ===
            if (detailLoading) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (detailError != null) {
                item {
                    Text(
                        text = "加载失败: ${detailError}",
                        color = Color.Red,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // === 剧情简介 ===
            val fullStory = detail?.fullSummary?.takeIf { it.isNotBlank() } ?: movie.summary
            if (fullStory.isNotBlank()) {
                item {
                    Text(
                        text = "剧情简介",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                    )
                    Text(
                        text = fullStory,
                        fontSize = 13.sp,
                        color = TextPrimary.copy(alpha = 0.85f),
                        lineHeight = 20.sp
                    )
                    HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
                }
            }

            // === 热门短评 ===
            val comments = detail?.comments ?: emptyList()
            if (comments.isNotEmpty()) {
                item {
                    Text(
                        text = "热门短评",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(comments) { c ->
                    CommentItem(c)
                    Spacer(Modifier.height(4.dp))
                }
                item {
                    Spacer(Modifier.height(24.dp))
                }
            } else if (!detailLoading) {
                item {
                    Text(
                        text = "暂无短评",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentItem(c: DoubanComment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F7F7)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                if (c.rating > 0f) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "★ ${c.rating}",
                        color = Accent,
                        fontSize = 11.sp
                    )
                }
                if (c.date.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(c.date, color = TextSecondary, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = c.content,
                fontSize = 13.sp,
                color = TextPrimary,
                lineHeight = 18.sp
            )
        }
    }
}
