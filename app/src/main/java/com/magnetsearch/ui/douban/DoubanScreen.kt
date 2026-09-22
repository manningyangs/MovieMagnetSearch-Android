package com.magnetsearch.ui.douban

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.compose.ui.text.style.TextAlign
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

    // ===== 列表页 LazyListState =====
    val listState = rememberLazyListState()

    // ===== 翻页后自动滚回顶部 =====
    LaunchedEffect(state.currentPage) { listState.animateScrollToItem(0) }

    BackHandler(enabled = state.selectedMovie != null) { vm.backToList() }

    Box(Modifier.fillMaxSize()) {
        // ============================================================
        // 底层：列表页（永不销毁，叠层架构保证滚动位置保留）
        // ============================================================
        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("豆瓣发现", fontWeight = FontWeight.SemiBold) },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            },
            containerColor = Surface
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding)
            ) {
                // === 搜索栏 ===
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchText,
                        onValueChange = { searchText = it },
                        modifier = Modifier.weight(1f).height(50.dp),
                        placeholder = { Text("输入片名搜索豆瓣", fontSize = 14.sp, color = TextTertiary) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, tint = TextTertiary)
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = Divider
                        )
                    )
                    Button(
                        onClick = { if (searchText.isNotBlank()) vm.search(searchText) },
                        modifier = Modifier.height(50.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("搜索", fontSize = 14.sp)
                    }
                }

                // === Top250 快捷按钮 ===
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    FilterChip(
                        selected = state.isTop250,
                        onClick = { vm.loadTop250() },
                        leadingIcon = { Icon(Icons.Default.Home, null, modifier = Modifier.size(18.dp)) },
                        label = { Text("豆瓣 Top 250", fontSize = 13.sp) }
                    )
                }

                // === 结果区域 ===
                Box(modifier = Modifier.fillMaxSize().padding(top = 8.dp)) {
                    when {
                        state.isLoading && state.movies.isEmpty() ->
                            CircularProgressIndicator(Modifier.align(Alignment.Center), color = Primary)

                        state.error != null ->
                            Text(
                                "加载失败: ${state.error!!}",
                                Modifier.align(Alignment.Center),
                                Color.Red, fontSize = 14.sp
                            )

                        state.movies.isEmpty() ->
                            Text(
                                "点击「豆瓣 Top 250」开始",
                                Modifier.align(Alignment.Center),
                                TextTertiary, fontSize = 14.sp
                            )

                        else -> Column(Modifier.fillMaxSize()) {
                            // 列表（固定 20 部一页）
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.weight(1f, fill = true),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(state.visibleMovies, key = { it.doubanId + it.rank }) { movie ->
                                    MovieCard(
                                        movie = movie,
                                        onSearch = { onMagnetSearch(it) },
                                        onClick = { vm.selectMovie(movie) }
                                    )
                                }
                            }
                            // 分页器（固定在底部）
                            PaginationBar(
                                currentPage = state.currentPage,
                                totalPages = state.totalPages,
                                totalItems = state.movies.size,
                                onPrev = { vm.prevPage() },
                                onNext = { vm.nextPage() },
                                onGoTo = { vm.goToPage(it) }
                            )
                        }
                    }
                }
            }
        }

        // ============================================================
        // 上层：详情页叠层（保留列表在底层）
        // ============================================================
        AnimatedVisibility(
            visible = state.selectedMovie != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            state.selectedMovie?.let { movie ->
                DoubanDetailScreen(
                    movie = movie,
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
            }
        }
    }

    // 首次自动加载 Top250
    LaunchedEffect(Unit) {
        if (state.movies.isEmpty() && !state.isLoading) vm.loadTop250()
    }
}

// ============================================================
// 分页器组件
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaginationBar(
    currentPage: Int,
    totalPages: Int,
    totalItems: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onGoTo: (Int) -> Unit
) {
    if (totalPages <= 0) return

    Surface(
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                // 上一页
                IconButton(
                    onClick = onPrev,
                    enabled = currentPage > 1,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ChevronLeft, "上一页", tint = if (currentPage > 1) Primary else TextTertiary)
                }

                Spacer(Modifier.width(4.dp))

                // 页码按钮
                PageNumbers(currentPage = currentPage, totalPages = totalPages, onGoTo = onGoTo)

                Spacer(Modifier.width(4.dp))

                // 下一页
                IconButton(
                    onClick = onNext,
                    enabled = currentPage < totalPages,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.ChevronRight, "下一页", tint = if (currentPage < totalPages) Primary else TextTertiary)
                }
            }

            // 底部统计：共 250 部 · 第 1/13 页
            Text(
                text = "共 $totalItems 部 · 第 $currentPage/$totalPages 页",
                fontSize = 11.sp,
                color = TextTertiary,
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PageNumbers(currentPage: Int, totalPages: Int, onGoTo: (Int) -> Unit) {
    // 最多显示 7 个页码（当前页前后各 3 个 + 当前页）
    val pageWindow = 7
    val start = ((currentPage - pageWindow / 2).coerceAtLeast(1)).coerceAtMost((totalPages - pageWindow + 1).coerceAtLeast(1))
    val end = (start + pageWindow - 1).coerceAtMost(totalPages)

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        for (p in start..end) {
            val isCurrent = p == currentPage
            FilterChip(
                selected = isCurrent,
                onClick = { onGoTo(p) },
                label = {
                    Text(
                        text = p.toString(),
                        fontSize = 12.sp,
                        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
                    )
                },
                shape = RoundedCornerShape(8.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary,
                    selectedLabelColor = androidx.compose.ui.graphics.Color.White
                )
            )
        }
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
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        containerColor = Surface
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 24.dp)
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
                            modifier = Modifier.width(110.dp).height(155.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Divider),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            placeholder = painterResource(id = android.R.drawable.ic_menu_report_image),
                            error = painterResource(id = android.R.drawable.ic_menu_report_image)
                        )
                    } else {
                        Box(
                            Modifier.width(110.dp).height(155.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Divider),
                            contentAlignment = Alignment.Center
                        ) { Text("封面", color = TextTertiary, fontSize = 12.sp) }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (movie.rank > 0) {
                                Text("#${movie.rank}", color = RedRank, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
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

                        d?.imdbId?.takeIf { it.isNotBlank() }?.let { imdb ->
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "IMDb: $imdb",
                                color = Primary,
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
                        Text("评分分布", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
                        RatingBarRow("5星", rd.star5, Quality4K)
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
                d?.directors?.takeIf { it.isNotEmpty() }?.let { InfoRow("导演", it.joinToString(" / ")) }
                d?.writers?.takeIf { it.isNotEmpty() }?.let { InfoRow("编剧", it.joinToString(" / ")) }
                d?.actors?.takeIf { it.isNotEmpty() }?.let { InfoRow("主演", it.joinToString(" / ")) }
                Spacer(Modifier.height(8.dp))
            }

            // === 按钮 ===
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Button(
                        onClick = { onSearch(title) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("搜磁力")
                    }
                    OutlinedButton(
                        onClick = onOpenDouban,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("打开豆瓣") }
                }
                HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
            }

            // === 加载中 / 错误 ===
            if (detailLoading) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Primary)
                }
            }
            detailError?.let { item { Text("加载失败: $it", color = Color.Red, modifier = Modifier.padding(12.dp)) } }

            // === 剧情简介 ===
            val fullStory = d?.fullSummary?.takeIf { it.isNotBlank() } ?: movie.summary
            if (fullStory.isNotBlank()) {
                item {
                    Text("剧情简介", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                    Text(fullStory, fontSize = 13.sp, color = TextPrimary.copy(alpha = 0.85f), lineHeight = 20.sp)
                    HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 12.dp))
                }
            }

            // === 热门短评 ===
            val comments = d?.comments ?: emptyList()
            if (comments.isNotEmpty()) {
                item {
                    Text("热门短评", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                items(comments) { c -> CommentItem(c); Spacer(Modifier.height(4.dp)) }
                item { Spacer(Modifier.height(24.dp)) }
            } else if (!detailLoading) {
                item { Text("暂无短评", color = TextTertiary, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
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
            modifier = Modifier.weight(1f).height(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFFEEEEEE))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(percent.coerceAtMost(100f) / 100f)
                    .fillMaxHeight()
                    .background(color)
            )
        }
        Text("${"%.1f".format(percent)}%", fontSize = 11.sp, color = TextSecondary,
            modifier = Modifier.width(42.dp).padding(start = 6.dp))
    }
}

@Composable
private fun CommentItem(c: DoubanComment) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
