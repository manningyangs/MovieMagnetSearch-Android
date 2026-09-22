package com.magnetsearch.ui.douban

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoubanScreen(
    onMagnetSearch: (String) -> Unit,
    viewModel: DoubanViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var searchText by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("豆瓣发现") }
            )
        }
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
                Button(onClick = { if (searchText.isNotBlank()) viewModel.search(searchText) }) {
                    Text("搜索")
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(
                    onClick = { viewModel.loadTop250() }
                ) {
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
                        color = androidx.compose.ui.graphics.Color.Red
                    )
                    state.movies.isEmpty() -> Text(
                        text = "点击「豆瓣 Top 250」开始",
                        modifier = Modifier.align(Alignment.Center),
                        color = androidx.compose.ui.graphics.Color.Gray
                    )
                    else -> LazyColumn(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(state.movies) { movie ->
                            MovieCard(
                                movie = movie,
                                onSearch = { onMagnetSearch(it) }
                            )
                        }
                        item {
                            Text(
                                text = if (state.isTop250) "共 ${state.movies.size} 部" else "共 ${state.movies.size} 条结果",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                color = androidx.compose.ui.graphics.Color.Gray,
                                style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }

    // 首次自动加载 Top250
    LaunchedEffect(Unit) {
        if (state.movies.isEmpty() && !state.isLoading) {
            viewModel.loadTop250()
        }
    }
}
