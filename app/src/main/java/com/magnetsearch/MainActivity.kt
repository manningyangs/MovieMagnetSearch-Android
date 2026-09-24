package com.magnetsearch

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.Coil
import coil.ImageLoader
import coil.request.CachePolicy
import com.magnetsearch.data.api.HttpClient
import com.magnetsearch.data.model.AppState
import com.magnetsearch.ui.bili.BiliScreen
import com.magnetsearch.ui.douban.DoubanScreen
import com.magnetsearch.ui.magnet.MagnetScreen
import com.magnetsearch.ui.theme.MovieMagnetSearchTheme
import com.magnetsearch.ui.theme.Primary

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全局配置 Coil：用 IPv4-only OkHttp
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RootScaffold() {
    var currentTab by remember { mutableStateOf(Tab.DOUBAN) }
    var prefillQuery by remember { mutableStateOf("") }
    val isVideoFullscreen by AppState::isVideoFullscreen

    Box(Modifier.fillMaxSize()) {
        // 内容区 —— 全屏，不被底部按钮挤压
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

        // 底部 Tab —— 悬浮覆盖，不挤占内容
        if (!isVideoFullscreen) {
            TransparentBottomBar(
                currentTab = currentTab,
                onTabChange = { currentTab = it },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

/** 透明玻璃效果的底部导航栏 —— 无白色横条，3 个按钮悬浮 */
@Composable
private fun TransparentBottomBar(
    currentTab: Tab,
    onTabChange: (Tab) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color(0x55000000))
                )
            )
            .padding(top = 10.dp, bottom = 10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabButton(
                icon = { Icon(Icons.Default.Search, contentDescription = null) },
                label = "磁力搜索",
                selected = currentTab == Tab.MAGNET,
                selectedColor = Color(0xFF3B82F6),
                onSelect = { onTabChange(Tab.MAGNET) }
            )
            TabButton(
                icon = { Icon(Icons.Default.Movie, contentDescription = null) },
                label = "豆瓣发现",
                selected = currentTab == Tab.DOUBAN,
                selectedColor = Color(0xFF22C55E),
                onSelect = { onTabChange(Tab.DOUBAN) }
            )
            TabButton(
                icon = { Icon(Icons.Default.PlayArrow, contentDescription = null) },
                label = "B站畅游",
                selected = currentTab == Tab.BILI,
                selectedColor = Color(0xFFFB7299),
                onSelect = { onTabChange(Tab.BILI) }
            )
        }
    }
}

@Composable
private fun TabButton(
    icon: @Composable () -> Unit,
    label: String,
    selected: Boolean,
    selectedColor: Color,
    onSelect: () -> Unit
) {
    val bgColor = if (selected) selectedColor.copy(alpha = 0.92f) else Color.Transparent
    val iconTint = Color.White
    val textColor = Color.White
    val iconSize = 20.dp    // Material 3 底部导航标准图标
    val textSize = 10.sp    // Material 3 底部导航标准文字

    Surface(
        color = bgColor,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = if (selected) 4.dp else 0.dp,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onSelect
            )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            CompositionLocalProvider(LocalContentColor provides iconTint) {
                Box(modifier = Modifier.size(iconSize)) { icon() }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                label,
                color = textColor,
                fontSize = textSize,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}
