package com.magnetsearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import com.magnetsearch.data.api.HttpClient
import com.magnetsearch.ui.bili.BiliScreen
import com.magnetsearch.ui.douban.DoubanScreen
import com.magnetsearch.ui.magnet.MagnetScreen
import com.magnetsearch.ui.theme.MovieMagnetSearchTheme
import com.magnetsearch.ui.theme.Primary
import com.magnetsearch.ui.theme.Surface

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全局配置 Coil：用 IPv4-only OkHttp（豆瓣 img CDN / 海外图床 IPv6 不通）
        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient { HttpClient.forImage() }
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .crossfade(true)
            .build()
        Coil.setImageLoader(imageLoader)

        setContent {
            MovieMagnetSearchTheme {
                RootScaffold()
            }
        }
    }
}

private enum class Tab { MAGNET, DOUBAN, BILI }

@Composable
private fun RootScaffold() {
    var currentTab by remember { mutableStateOf(Tab.DOUBAN) }
    var prefillQuery by remember { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = Surface,
                tonalElevation = 6.dp
            ) {
                NavigationBarItem(
                    selected = currentTab == Tab.MAGNET,
                    onClick = { currentTab = Tab.MAGNET },
                    icon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("磁力搜索") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Primary,
                        selectedTextColor = Primary,
                        indicatorColor = Primary.copy(alpha = 0.12f)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == Tab.DOUBAN,
                    onClick = { currentTab = Tab.DOUBAN },
                    icon = { Icon(Icons.Default.Movie, contentDescription = null) },
                    label = { Text("豆瓣发现") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Primary,
                        selectedTextColor = Primary,
                        indicatorColor = Primary.copy(alpha = 0.12f)
                    )
                )
                NavigationBarItem(
                    selected = currentTab == Tab.BILI,
                    onClick = { currentTab = Tab.BILI },
                    icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                    label = { Text("B站畅游") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color(0xFFFB7299),
                        selectedTextColor = Color(0xFFFB7299),
                        indicatorColor = Color(0xFFFB7299).copy(alpha = 0.15f)
                    )
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (currentTab) {
                Tab.MAGNET -> MagnetScreen(prefillQuery = prefillQuery)
                Tab.DOUBAN -> DoubanScreen(
                    onMagnetSearch = {
                        prefillQuery = it
                        currentTab = Tab.MAGNET
                    }
                )
                Tab.BILI -> BiliScreen()
            }
        }
    }
}
