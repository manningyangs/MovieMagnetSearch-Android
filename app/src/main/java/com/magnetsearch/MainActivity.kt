package com.magnetsearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import coil.ImageLoader
import coil.request.CachePolicy
import com.magnetsearch.data.api.HttpClient
import com.magnetsearch.ui.douban.DoubanScreen
import com.magnetsearch.ui.magnet.MagnetScreen
import com.magnetsearch.ui.theme.MovieMagnetSearchTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全局配置 Coil：用 IPv4-only OkHttp，加速封面加载
        val imageLoader = ImageLoader.Builder(this)
            .okHttpClient { HttpClient.forImage() }
            .respectCacheHeaders(false)
            .diskCachePolicy(CachePolicy.ENABLED)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .build()
        ImageLoaderHolder.loader = imageLoader

        setContent {
            MovieMagnetSearchTheme {
                RootScaffold()
            }
        }
    }
}

/** 让 Coil 在 Composable 中用自定义 ImageLoader。 */
object ImageLoaderHolder {
    lateinit var loader: ImageLoader
}

private enum class Tab { MAGNET, DOUBAN }

@Composable
private fun RootScaffold() {
    var currentTab by remember { mutableStateOf(Tab.DOUBAN) }
    var prefillQuery by remember { mutableStateOf("") }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == Tab.DOUBAN,
                    onClick = { currentTab = Tab.DOUBAN },
                    icon = { Icon(Icons.Default.Movie, null) },
                    label = { Text("豆瓣发现") }
                )
                NavigationBarItem(
                    selected = currentTab == Tab.MAGNET,
                    onClick = { currentTab = Tab.MAGNET },
                    icon = { Icon(Icons.Default.Search, null) },
                    label = { Text("磁力搜索") }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (currentTab) {
                Tab.DOUBAN -> DoubanScreen(
                    onMagnetSearch = {
                        prefillQuery = it
                        currentTab = Tab.MAGNET
                    }
                )
                Tab.MAGNET -> MagnetScreen(prefillQuery = prefillQuery)
            }
        }
    }
}
