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
import androidx.lifecycle.viewmodel.compose.viewModel
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
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
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
                Button(onClick = { if (searchText.isNotBlank()) vm.search(searchText) }) { Text("搜索") }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = { vm.loadTop250() }) {
                    Icon(Icons.Default.Home, null, modifier = Modifier.width(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("豆瓣 Top 250")
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.movies.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    state.error != null -> Text(state.error!!, Modifier.align(Alignment.Center), Color.Red)
                    state.movies.isEmpty() -> Text("点击「豆瓣 Top 250」开始", Modifier.align(Alignment.Center), Color.Gray)
                    else -> LazyColumn(Modifier.fillMaxSize()) {
                        items(state.movies) { movie ->
                            MovieCard(movie = movie, onSearch = { onMagnetSearch(it) }, onClick = { vm.selectMovie(movie) })
                        }
                        item {
                            Text(
                                text = if (state.isTop250) "共 ${state.movies.size} 部 · 点击卡片查看详情"
                                       else "共 ${state.movies.size} 条结果 · 点击卡片查看详情",
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                color = Color.Gray, fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (state.movies.isEmpty() && !state.isLoading) vm.loadTop250()
    }
}

// ============================================================
// 详情页
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
    val d = detail
    val coverUrl = d?.coverUrl?.takeIf { it.isNotBlank() } ?: movie.coverUrl
    val title = d?.title?.takeIf { it.isNotBlank() } ?: movie.title
    val year = d?.year?.takeIf { it.isNotBlank() } ?: movie.year
    val rating = d?.rating?.takeIf { it > 0f } ?: movie.rating

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("电影详情", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "返回") } }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
        ) {
            // === 顶部：封面 + 标题 + 评分 ===
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    if (coverUrl.isNotBlank()) {
                        AsyncImage(
                            model = coverUrl, contentDescription = "封面",
                            modifier = Modifier.width(110.dp).height(155.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE0E0E0)),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            placeholder = painterResource(id = android.R.drawable.ic_menu_report_image),
                            error = painterResource(id = android.R.drawable.ic_menu_report_image)
                        )
                    } else {
                        Box(Modifier.width(110.dp).height(155.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE0E0E0)), contentAlignment = Alignment.Center) {
                            Text("封面", color = Color.Gray)
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (movie.rank > 0) { Text("#${movie.rank}", color = RedRank, fontWeight = FontWeight.Bold, fontSize = 16.sp); Spacer(Modifier.width(6.dp)) }
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        d?.originalTitle?.takeIf { it.isNotBlank() && it != title }?.let {
                            Text(it, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        if (year.isNotBlank()) Text(year, color = TextSecondary, fontSize = 13.sp)

                        if (rating > 0f) {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("★ $rating", color = Accent, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                                d?.voteCount?.takeIf { it > 0 }?.let { n ->
                                    Spacer(Modifier.width(6.dp))
                                    Text("(${formatVoteCount(n)} 人评价)", color = TextSecondary, fontSize = 11.sp)
                                }
                            }
                        }

                        // IMDb 链接
                        d?.imdbId?.takeIf { it.isNotBlank() }?.let { imdb ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "IMDb: $imdb",
                                color = Color(0xFF1565C0),
                                fontSize = 12.sp,
                                modifier = Modifier.clickable {
                                    val url = "https://www.imdb.com/title/$imdb/"
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    context.startActivity(intent)
                                }
                            )
                        }
                    }
                }
            }

            // === 评分分布条形图 ===
            d?.ratingDist?.let { rd ->
                val total = rd.star5 + rd.star4 + rd.star3 + rd.star2 + rd.star1
                if (total > 0f) {
                    item {
                        Text("评分分布", fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                        RatingBarRow("5星", rd.star5, Color(0xFFE65100))
                        RatingBarRow("4星", rd.star4, Color(0xFFF57C00))
                        RatingBarRow("3星", rd.star3, Color(0xFFFBC02D))
                        RatingBarRow("2星", rd.star2, Color(0xFF90CAF9))
                        RatingBarRow("1星", rd.star1, Color(0xFF42A5F5))
                        Spacer(Modifier.height(6.dp))
                        HorizontalDivider(color = Divider, thickness = 0.5.dp)
                    }
                }
            }

            // === 完整信息表 ===
            item {
                Spacer(Modifier.height(8.dp))
                InfoRow("类型", d?.genres?.joinToString(" / "))
                InfoRow("地区", d?.countries?.joinToString(" / "))
                InfoRow("语言", d?.languages?.joinToString(" / "))
                InfoRow("片长", d?.duration?.let { "$it 分钟" })
                InfoRow("上映日期", d?.releaseDates?.joinToString("\n"))
                InfoRow("又名", d?.aliases?.joinToString(" / "))

                val directors = d?.directors?.takeIf { it.isNotEmpty() }
                if (directors != null) InfoRow("导演", directors.joinToString(" / "))

                val writers = d?.writers?.takeIf { it.isNotEmpty() }
                if (writers != null) InfoRow("编剧", writers.joinToString(" / "))

                val actors = d?.actors?.takeIf { it.isNotEmpty() }
                if (actors != null) InfoRow("主演", actors.joinToString(" / "))

                Spacer(Modifier.height(8.dp))
            }

            // === 按钮 ===
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = { onSearch(title) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("搜磁力")
                    }
                    OutlinedButton(onClick = onOpenDouban, modifier = Modifier.weight(1f)) { Text("打开豆瓣") }
                }
                HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 10.dp))
            }

            // === 加载中 / 错误 ===
            if (detailLoading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            detailError?.let { item { Text("加载失败: $it", color = Color.Red, modifier = Modifier.padding(12.dp)) } }

            // === 剧情简介 ===
            val fullStory = d?.fullSummary?.takeIf { it.isNotBlank() } ?: movie.summary
            if (fullStory.isNotBlank()) {
                item {
                    Text("剧情简介", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                    Text(fullStory, fontSize = 13.sp, color = TextPrimary.copy(alpha = 0.85f), lineHeight = 20.sp)
                    HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
                }
            }

            // === 热门短评 ===
            val comments = d?.comments ?: emptyList()
            if (comments.isNotEmpty()) {
                item { Text("热门短评", fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp)) }
                items(comments) { c -> CommentItem(c); Spacer(Modifier.height(4.dp)) }
                item { Spacer(Modifier.height(24.dp)) }
            } else if (!detailLoading) {
                item { Text("暂无短评", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TextSecondary, fontSize = 13.sp, modifier = Modifier.width(70.dp))
        Text(value, color = TextPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun RatingBarRow(label: String, percent: Float, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 12.sp, modifier = Modifier.width(28.dp))
        Box(
            modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(2.dp)).background(Color(0xFFEEEEEE))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent.coerceAtMost(100f) / 100f)
                    .fillMaxHeight()
                    .background(color)
            )
        }
        Text("${"%.1f".format(percent)}%", fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(42.dp).padding(start = 6.dp))
    }
}

@Composable
private fun CommentItem(c: DoubanComment) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFF7F7F7)), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                if (c.rating > 0f) { Spacer(Modifier.width(6.dp)); Text("★ ${c.rating}", color = Accent, fontSize = 11.sp) }
                if (c.date.isNotBlank()) { Spacer(Modifier.width(6.dp)); Text(c.date, color = TextSecondary, fontSize = 11.sp) }
            }
            Spacer(Modifier.height(4.dp))
            Text(c.content, fontSize = 13.sp, color = TextPrimary, lineHeight = 18.sp)
        }
    }
}

private fun formatVoteCount(n: Int): String = when {
    n >= 10_000_000 -> "${n / 1_000_000}百万"
    n >= 10_000 -> "%.1f万".format(n / 10_000.0)
    else -> n.toString()
}
