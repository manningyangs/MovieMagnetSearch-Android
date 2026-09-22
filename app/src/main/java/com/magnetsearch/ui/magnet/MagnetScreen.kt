package com.magnetsearch.ui.magnet

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
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
import com.magnetsearch.data.model.SearchSource
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
    val context = LocalContext.current

    LaunchedEffect(prefillQuery) {
        if (prefillQuery.isNotBlank()) {
            query = prefillQuery
            viewModel.search(prefillQuery, mediaType)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("磁力搜索") }) }
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
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入电影名") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        Row {
                            FilterChip(
                                selected = mediaType == MediaType.MOVIE,
                                onClick = { mediaType = MediaType.MOVIE },
                                label = { Text("电影", fontSize = 11.sp) },
                                modifier = Modifier.height(32.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            FilterChip(
                                selected = mediaType == MediaType.TV,
                                onClick = { mediaType = MediaType.TV },
                                label = { Text("剧/综艺", fontSize = 11.sp) },
                                modifier = Modifier.height(32.dp)
                            )
                        }
                    }
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Button(
                    onClick = {
                        if (query.isNotBlank()) {
                            viewModel.search(query, mediaType)
                        }
                    }
                ) {
                    Text("搜索")
                }
            }

            // 结果列表
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    state.isLoading && state.results.isEmpty() -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                    state.error != null -> Text(
                        text = state.error!!,
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Red
                    )
                    state.results.isEmpty() -> Text(
                        text = "输入关键词开始搜索",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Gray
                    )
                    else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(state.results) { r ->
                            MagnetItem(
                                title = r.title,
                                source = r.source,
                                size = r.size,
                                seeders = r.seeders,
                                leechers = r.leechers,
                                magnet = r.magnet,
                                onCopy = { copyToClipboard(context, "magnet", r.magnet) },
                                onDownload = { downloadMagnet(context, r.magnet) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MagnetItem(
    title: String,
    source: String,
    size: String,
    seeders: Int,
    leechers: Int,
    magnet: String,
    onCopy: () -> Unit,
    onDownload: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(text = "来源: $source", fontSize = 12.sp, color = TextSecondary)
                Text(text = size, fontSize = 12.sp, color = TextSecondary)
                Text(
                    text = "↑$seeders  ↓$leechers",
                    fontSize = 12.sp,
                    color = if (seeders > 0) Color(0xFF2E7D32) else TextSecondary
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onCopy,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, null, modifier = Modifier.width(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("复制", fontSize = 12.sp)
                }
                OutlinedButton(
                    onClick = onDownload,
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.Download, null, modifier = Modifier.width(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("下载", fontSize = 12.sp)
                }
            }
        }
    }
}

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun downloadMagnet(context: Context, magnet: String) {
    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(magnet))
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
