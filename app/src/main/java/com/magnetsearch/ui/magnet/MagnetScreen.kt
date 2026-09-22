package com.magnetsearch.ui.magnet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items as lazyItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.magnetsearch.data.model.MediaType
import com.magnetsearch.data.model.QualityFilter
import com.magnetsearch.data.model.SearchSource
import com.magnetsearch.data.model.SortBy
import com.magnetsearch.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MagnetScreen(
    prefillQuery: String = "",
    viewModel: MagnetViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var query by remember { mutableStateOf(prefillQuery) }
    var mediaType by remember { mutableStateOf(MediaType.MOVIE) }
    var showFilterSheet by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(prefillQuery) {
        if (prefillQuery.isNotBlank()) {
            query = prefillQuery
            viewModel.search(prefillQuery, mediaType)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("磁力搜索", fontWeight = FontWeight.SemiBold) },
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
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).height(50.dp),
                    placeholder = { Text("输入片名", fontSize = 14.sp, color = TextTertiary) },
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
                    onClick = { if (query.isNotBlank()) viewModel.search(query, mediaType) },
                    modifier = Modifier.height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("搜索", fontSize = 14.sp)
                }
            }

            // === 媒体类型切换 ===
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilterChip(
                    selected = mediaType == MediaType.MOVIE,
                    onClick = { mediaType = MediaType.MOVIE },
                    label = { Text("电影") },
                    shape = RoundedCornerShape(8.dp)
                )
                FilterChip(
                    selected = mediaType == MediaType.TV,
                    onClick = { mediaType = MediaType.TV },
                    label = { Text("剧/综艺") },
                    shape = RoundedCornerShape(8.dp)
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showFilterSheet = true }) {
                    Icon(Icons.Default.FilterList, "筛选", tint = Primary)
                }
            }

            // === 激活的筛选提示 ===
            val filterHints = buildList {
                if (state.translatedQuery != null)
                    add("已翻译为 ${state.translatedQuery}")
                if (state.enabledSources.size != SearchSource.all().size)
                    add("已选 ${state.enabledSources.size}/${SearchSource.all().size} 源")
                if (state.qualityFilter != QualityFilter.ALL)
                    add("分辨率: ${state.qualityFilter.displayName}")
                add("按 ${state.sortBy.displayName} 排序")
            }
            if (filterHints.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    filterHints.forEach { hint ->
                        SuggestionChip(
                            onClick = { showFilterSheet = true },
                            label = { Text(hint, fontSize = 11.sp) },
                            shape = RoundedCornerShape(8.dp)
                        )
                    }
                }
            }

            // === 结果列表 ===
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.rawResults.isEmpty() ->
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Primary)

                    state.error != null -> Text(
                        text = state.error!!,
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Red, fontSize = 14.sp
                    )

                    state.filteredResults.isEmpty() -> Text(
                        text = if (state.rawResults.isEmpty()) "输入关键词开始搜索"
                               else "当前筛选无结果，点击筛选图标调整",
                        modifier = Modifier.align(Alignment.Center),
                        color = TextTertiary, fontSize = 14.sp
                    )

                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp)
                    ) {
                        lazyItems(state.filteredResults) { r ->
                            MagnetItem(
                                title = r.title,
                                source = r.source,
                                size = r.size,
                                quality = r.quality,
                                seeders = r.seeders,
                                leechers = r.leechers,
                                magnet = r.magnet,
                                onCopy = { copyToClipboard(context, "magnet", r.magnet) },
                                onDownload = { downloadMagnet(context, r.magnet) }
                            )
                        }
                        item {
                            Text(
                                text = "共 ${state.filteredResults.size} 条（原 ${state.rawResults.size}）",
                                modifier = Modifier.fillMaxWidth().padding(16.dp),
                                fontSize = 12.sp,
                                color = TextTertiary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        FilterBottomSheet(
            state = state,
            viewModel = viewModel,
            onDismiss = { showFilterSheet = false }
        )
    }
}

// ========== 筛选底部弹出 ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBottomSheet(
    state: MagnetUiState,
    viewModel: MagnetViewModel,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text("搜索源", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 10.dp))
            SearchSource.values().filter { it != SearchSource.ALL }.forEach { source ->
                val checked = state.enabledSources.contains(source)
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { viewModel.toggleSource(source, !checked) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { viewModel.toggleSource(source, it) },
                        colors = CheckboxDefaults.colors(checkedColor = Primary)
                    )
                    Text(source.displayName, fontSize = 14.sp)
                }
            }

            HorizontalDivider(color = Divider, modifier = Modifier.padding(vertical = 12.dp))

            Text("分辨率筛选", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(QualityFilter.values().toList()) { q ->
                    FilterChip(
                        selected = state.qualityFilter == q,
                        onClick = { viewModel.setQualityFilter(q) },
                        label = { Text(q.displayName) },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            HorizontalDivider(color = Divider, modifier = Modifier.padding(vertical = 12.dp))

            Text("排序", fontWeight = FontWeight.Bold, fontSize = 16.sp,
                modifier = Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SortBy.values().forEach { s ->
                    FilterChip(
                        selected = state.sortBy == s,
                        onClick = { viewModel.setSortBy(s) },
                        label = { Text(s.displayName) },
                        shape = RoundedCornerShape(8.dp)
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ========== 结果卡片 ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MagnetItem(
    title: String,
    source: String,
    size: String,
    quality: String,
    seeders: Int,
    leechers: Int,
    @Suppress("UNUSED_PARAMETER")
    magnet: String,
    onCopy: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 5.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = TextPrimary
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = source, fontSize = 12.sp, color = TextSecondary)
                SuggestionChip(
                    onClick = {},
                    label = { Text(quality, fontSize = 11.sp) },
                    shape = RoundedCornerShape(6.dp)
                )
                Text(text = size, fontSize = 12.sp, color = TextSecondary)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "↑$seeders  ↓$leechers",
                    fontSize = 12.sp,
                    color = if (seeders > 0) Quality720p else TextSecondary
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDownload,
                    contentPadding = PaddingValues(horizontal = 10.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("下载", fontSize = 12.sp)
                }
            }
        }
    }
}

// ========== 工具函数 ==========

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun downloadMagnet(context: Context, magnet: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(magnet))
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
