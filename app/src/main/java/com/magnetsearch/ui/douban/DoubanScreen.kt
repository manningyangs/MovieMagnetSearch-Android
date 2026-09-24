@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.magnetsearch.ui.douban

import android.content.Intent
import android.net.Uri
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.magnetsearch.data.model.CastMember
import com.magnetsearch.data.model.DoubanComment
import com.magnetsearch.data.model.DoubanDetail
import com.magnetsearch.data.model.DoubanMovie
import com.magnetsearch.data.model.DoubanReview
import com.magnetsearch.data.model.Trailer
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

    // === 统一预览：大图 or 视频 ===
    // null=关闭, "image:$index"=剧照大图, "video:$url"=预告片播放
    var previewTarget by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = previewTarget != null) { previewTarget = null }

    val stills = d?.stills ?: emptyList()

    Box(Modifier.fillMaxSize()) {
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
                            contentScale = ContentScale.Crop,
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
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.imdb.com/title/$imdb/"))
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

            // === 演职员横滑头像 Row ===
            val castMembers = d?.castMembers ?: emptyList()
            if (castMembers.isNotEmpty()) {
                item {
                    Text("演职员", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        items(castMembers) { member -> CastAvatarItem(member) }
                    }
                    HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // === 预告片 ===
            val trailers = d?.trailers ?: emptyList()
            if (trailers.isNotEmpty()) {
                item {
                    Text("视频", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(trailers) { trailer ->
                            TrailerCard(trailer) { previewTarget = "video:${trailer.videoUrl}" }
                        }
                    }
                    HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(top = 8.dp))
                }
            }

            // === 剧照横向滚动（跟演职员同款） ===
            if (stills.isNotEmpty()) {
                item {
                    Text("剧照", fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(stills) { idx, url ->
                            Box(
                                modifier = Modifier.width(140.dp).height(90.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Divider)
                                    .clickable { previewTarget = "image:$idx" }
                            ) {
                                AsyncImage(
                                    model = url, contentDescription = "剧照",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop,
                                    placeholder = painterResource(id = android.R.drawable.ic_menu_report_image),
                                    error = painterResource(id = android.R.drawable.ic_menu_report_image)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(top = 8.dp))
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

            // === 剧情简介（带展开/收起） ===
            val fullStory = d?.fullSummary?.takeIf { it.isNotBlank() } ?: movie.summary
            if (fullStory.isNotBlank()) {
                item {
                    var expanded by remember { mutableStateOf(false) }
                    Text("剧情简介", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
                    Text(
                        text = fullStory,
                        fontSize = 13.sp,
                        color = TextPrimary.copy(alpha = 0.85f),
                        lineHeight = 20.sp,
                        maxLines = if (expanded) Int.MAX_VALUE else 4,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(onClick = { expanded = !expanded }) {
                            Text(if (expanded) "收起" else "展开全文", fontSize = 13.sp)
                            Icon(
                                if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = Divider, thickness = 0.5.dp, modifier = Modifier.padding(vertical = 4.dp))
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
            }

            // === 影评（长评） ===
            val reviews = d?.reviews ?: emptyList()
            if (reviews.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text("影评", fontWeight = FontWeight.Bold, fontSize = 15.sp,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
                items(reviews) { r -> ReviewCard(r, context); Spacer(Modifier.height(8.dp)) }
            }

            item { Spacer(Modifier.height(24.dp)) }
        }  // LazyColumn
    }  // Scaffold

    // === 全屏剧照预览 overlay（单张 + 左右按钮） ===
    AnimatedVisibility(
        visible = previewTarget != null,
        enter = fadeIn(), exit = fadeOut()
    ) {
        val target = previewTarget ?: return@AnimatedVisibility
        val (type, payload) = target.split(":", limit = 2)

        Box(
            Modifier.fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
        ) {
            IconButton(
                onClick = { previewTarget = null },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "关闭", tint = Color.White, modifier = Modifier.size(28.dp))
            }

            when (type) {
                "image" -> {
                    val startIdx = payload.toIntOrNull() ?: return@AnimatedVisibility
                    val context = LocalContext.current
                    val clipboard = LocalClipboardManager.current
                    val pagerState = rememberPagerState(
                        initialPage = startIdx,
                        pageCount = { stills.size }
                    )
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        val url = stills.getOrNull(page) ?: return@HorizontalPager
                        AsyncImage(
                            model = url, contentDescription = "剧照",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .combinedClickable(
                                    onClick = { previewTarget = null },
                                    onLongClick = {
                                        saveImageToGallery(context, url) { msg ->
                                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onDoubleClick = {
                                        clipboard.setText(AnnotatedString(url))
                                        Toast.makeText(context, "图片链接已复制", Toast.LENGTH_SHORT).show()
                                    }
                                ),
                            contentScale = ContentScale.Fit
                        )
                    }
                    Text(
                        "${pagerState.currentPage + 1} / ${stills.size}",
                        color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                    )
                }
                "video" -> {
                    val videoUrl = payload
                    AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.mediaPlaybackRequiresUserGesture = false
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
                                webViewClient = object : android.webkit.WebViewClient() {
                                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView?, request: android.webkit.WebResourceRequest?) = false
                                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        // 自动播放：找所有 <video> 标签调 play()
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                var videos = document.querySelectorAll('video');
                                                videos.forEach(function(v) {
                                                    v.muted = false;
                                                    v.controls = true;
                                                    var p = v.play();
                                                    if (p && p.catch) p.catch(function() { v.muted = true; v.play(); });
                                                });
                                            })();
                                            """.trimIndent(), null
                                        )
                                    }
                                }
                            }
                        },
                        update = { webView ->
                            webView.loadUrl(videoUrl)
                        },
                        modifier = Modifier.fillMaxSize().padding(top = 44.dp)
                    )
                }
            }
        }
    }

    }  // Box close (wraps Scaffold + overlay)
}  // DoubanDetailScreen close

// ============================================================
// 演职员头像横滑项
// ============================================================
@Composable
private fun CastAvatarItem(member: CastMember) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(72.dp)
    ) {
        Box(
            modifier = Modifier.size(64.dp)
                .clip(CircleShape)
                .background(Divider),
            contentAlignment = Alignment.Center
        ) {
            if (member.avatarUrl.isNotBlank()) {
                AsyncImage(
                    model = member.avatarUrl, contentDescription = member.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(id = android.R.drawable.ic_menu_report_image),
                    error = painterResource(id = android.R.drawable.ic_menu_report_image)
                )
            } else {
                Text(member.name.take(1), fontSize = 22.sp, color = TextTertiary, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            member.name, fontSize = 12.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        if (member.role.isNotBlank()) {
            Text(
                member.role, fontSize = 10.sp, color = TextTertiary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
            )
        }
    }
}

// ============================================================
// 预告片卡片
// ============================================================
@Composable
private fun TrailerCard(trailer: Trailer, onClick: () -> Unit) {
    Box(
        modifier = Modifier.width(160.dp).height(90.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF1565C0), Color(0xFF0D47A1), Color(0xFF01579B))
                )
            )
            .clickable { onClick() }
    ) {
        if (trailer.coverUrl.isNotBlank()) {
            AsyncImage(
                model = trailer.coverUrl, contentDescription = trailer.title.ifBlank { "预告片" },
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(id = android.R.drawable.ic_menu_report_image),
                error = painterResource(id = android.R.drawable.ic_menu_report_image)
            )
        }
        // 半透明遮罩 + 播放图标
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.PlayArrow, "播放",
                tint = Color.White,
                modifier = Modifier.size(44.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.3f))
            )
        }
        if (trailer.title.isNotBlank()) {
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            ) {
                Text(
                    trailer.title, fontSize = 11.sp, color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ============================================================
// 影评卡片（长评）
// ============================================================
@Composable
private fun ReviewCard(r: DoubanReview, context: android.content.Context) {
    var expanded by remember { mutableStateOf(false) }
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(r.doubanUrl))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF3F4F6)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 头部：头像 + 作者 + 评分 + 日期
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (r.avatarUrl.isNotBlank()) {
                    AsyncImage(
                        model = r.avatarUrl, contentDescription = r.author,
                        modifier = Modifier.size(28.dp).clip(CircleShape).background(Divider),
                        contentScale = ContentScale.Crop,
                        placeholder = painterResource(id = android.R.drawable.ic_menu_report_image),
                        error = painterResource(id = android.R.drawable.ic_menu_report_image)
                    )
                } else {
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).background(Divider),
                        contentAlignment = Alignment.Center
                    ) { Text(r.author.take(1), fontSize = 12.sp, color = TextTertiary, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(8.dp))
                Text(r.author, fontWeight = FontWeight.Medium, fontSize = 13.sp)
                if (r.rating > 0f) {
                    Spacer(Modifier.width(6.dp))
                    Text("★ ${"%.1f".format(r.rating)}", color = Accent, fontSize = 11.sp)
                }
                if (r.date.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(r.date, color = TextSecondary, fontSize = 11.sp)
                }
            }

            // 标题
            if (r.title.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(r.title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            }

            // 正文（可展开）
            if (r.content.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                val lineCount = r.content.lineSequence().count()
                Text(
                    text = r.content, fontSize = 13.sp, color = TextPrimary,
                    lineHeight = 20.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis
                )
                if (lineCount > 6) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text(if (expanded) "收起" else "展开全文", fontSize = 12.sp)
                        }
                    }
                }
            }

            // 查看原帖链接
            if (r.doubanUrl.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = { context.startActivity(intent) },
                        contentPadding = PaddingValues(4.dp)
                    ) { Text("查看原帖 →", fontSize = 12.sp) }
                }
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

/** 下载网络图片保存到相册（Android 10+ 用 MediaStore，低版本用 WRITE_EXTERNAL_STORAGE） */
private fun saveImageToGallery(
    context: android.content.Context,
    url: String,
    onResult: (String) -> Unit
) {
    CoroutineScope(Dispatchers.IO).launch {
        try {
            val okHttpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            val request = okhttp3.Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
                .header("Referer", "https://movie.douban.com/")
                .header("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .header("Cache-Control", "no-cache")
                .build()
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    withContext(Dispatchers.Main) { onResult("下载失败 HTTP ${resp.code}") }
                    return@launch
                }
                val body = resp.body ?: run {
                    withContext(Dispatchers.Main) { onResult("下载失败：空 body") }
                    return@launch
                }
                val bytes = body.bytes()

                val fileName = "mg_${System.currentTimeMillis()}.jpg"
                val mimeType = "image/jpeg"

                // Android 10+ (API 29+) 用 MediaStore
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = android.content.ContentValues().apply {
                        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName)
                        put(android.provider.MediaStore.Images.Media.MIME_TYPE, mimeType)
                        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/MovieMagnetSearch")
                        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = context.contentResolver.insert(
                        android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                    ) ?: run {
                        withContext(kotlinx.coroutines.Dispatchers.Main) { onResult("保存失败：无法创建相册条目") }
                        return@launch
                    }
                    context.contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(bytes)
                    }
                    values.clear()
                    values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(uri, values, null, null)
                    withContext(kotlinx.coroutines.Dispatchers.Main) { onResult("已保存到相册 ✅") }
                } else {
                    // Android 9 及以下
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
                        "MovieMagnetSearch"
                    )
                    if (!dir.exists()) dir.mkdirs()
                    val file = java.io.File(dir, fileName)
                    java.io.FileOutputStream(file).use { it.write(bytes) }
                    context.sendBroadcast(android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(file)))
                    withContext(Dispatchers.Main) { onResult("已保存到相册 ✅") }
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { onResult("保存失败：${e.message}") }
        }
    }
}
