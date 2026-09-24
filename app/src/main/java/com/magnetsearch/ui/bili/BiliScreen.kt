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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

// B站分区分类（API 带 wbi 签名，WebView 嵌入最快）
private val CATEGORIES = listOf(
    "热门", "动画", "番剧", "音乐", "舞蹈", "游戏", "知识",
    "科技", "运动", "汽车", "生活", "美食", "动物圈", "时尚", "资讯", "娱乐"
)

private val BILI_COLOR = Color(0xFFFB7299)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BiliScreen(
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf("热门") }
    var searchQuery by remember { mutableStateOf("") }
    var showSearch by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(modifier = modifier.fillMaxSize()) {
        // 顶栏
        TopAppBar(
            title = { Text("B站畅游", fontWeight = FontWeight.SemiBold) },
            actions = {
                IconButton(onClick = { showSearch = !showSearch }) {
                    Icon(Icons.Default.Search, contentDescription = "搜索")
                }
            }
        )

        if (showSearch) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("搜索视频，回车即跳B站") },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true,
                trailingIcon = {
                    TextButton(
                        onClick = {
                            if (searchQuery.isNotBlank()) {
                                val url = "https://search.bilibili.com/all?keyword=${Uri.encode(searchQuery)}"
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    ) { Text("去B站") }
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
                    onClick = {
                        selectedCategory = cat
                        openBiliCategory(context, cat)
                    },
                    label = { Text(cat) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = BILI_COLOR.copy(alpha = 0.15f),
                        selectedLabelColor = BILI_COLOR
                    )
                )
            }
        }

        // 主入口：B站首页嵌入 WebView
        Box(
            Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = "🚀 B站畅游",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = BILI_COLOR
                )
                Text(
                    text = "点击下方按钮，在浏览器里完整享受 B 站体验\n搜索、分类、播放、缓存全支持",
                    color = Color.Gray,
                    fontSize = 14.sp,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { openBiliHome(context) },
                    modifier = Modifier.height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BILI_COLOR)
                ) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text("打开 B 站首页", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = { openBiliCategory(context, selectedCategory) },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("浏览：$selectedCategory", color = BILI_COLOR)
                }

                OutlinedButton(
                    onClick = { openBiliSearch(context, searchQuery.ifBlank { "影视推荐" }) },
                    modifier = Modifier.height(48.dp)
                ) {
                    Text("搜索：${searchQuery.ifBlank { "影视推荐" }}", color = BILI_COLOR)
                }
            }
        }
    }
}

private fun openBiliHome(ctx: android.content.Context) {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.bilibili.com")))
}

private fun openBiliCategory(ctx: android.content.Context, category: String) {
    // B站分区 URL 映射（用 web 搜索降级绕过 wbi 签名）
    val url = when (category) {
        "热门" -> "https://www.bilibili.com/v/popular/rank/all"
        "动画" -> "https://www.bilibili.com/v/anime/"
        "番剧" -> "https://www.bilibili.com/v/bangumi/"
        "音乐" -> "https://www.bilibili.com/v/music/"
        "舞蹈" -> "https://www.bilibili.com/v/dance/"
        "游戏" -> "https://www.bilibili.com/v/game/"
        "知识" -> "https://www.bilibili.com/v/knowledge/"
        "科技" -> "https://www.bilibili.com/v/tech/"
        "运动" -> "https://www.bilibili.com/v/sports/"
        "汽车" -> "https://www.bilibili.com/v/car/"
        "生活" -> "https://www.bilibili.com/v/life/"
        "美食" -> "https://www.bilibili.com/v/food/"
        "动物圈" -> "https://www.bilibili.com/v/animal/"
        "时尚" -> "https://www.bilibili.com/v/fashion/"
        "资讯" -> "https://www.bilibili.com/v/information/"
        "娱乐" -> "https://www.bilibili.com/v/ent/"
        else -> "https://search.bilibili.com/all?keyword=${Uri.encode(category)}"
    }
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}

private fun openBiliSearch(ctx: android.content.Context, keyword: String) {
    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://search.bilibili.com/all?keyword=${Uri.encode(keyword)}")))
}
