package com.magnetsearch.ui.douban

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.rememberAsyncImagePainter
import com.magnetsearch.ImageLoaderHolder
import com.magnetsearch.data.model.DoubanMovie
import com.magnetsearch.ui.theme.*

@Composable
fun MovieCard(
    movie: DoubanMovie,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 封面
        if (movie.coverUrl.isNotBlank()) {
            Image(
                painter = rememberAsyncImagePainter(
                    model = movie.coverUrl,
                    imageLoader = ImageLoaderHolder.loader
                ),
                contentDescription = "cover",
                modifier = Modifier
                    .width(80.dp)
                    .height(113.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(113.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFE0E0E0)),
                contentAlignment = Alignment.Center
            ) {
                Text("封面", color = Color.Gray, fontSize = 11.sp)
            }
        }

        // 信息区
        Column(modifier = Modifier.weight(1f)) {
            // 标题行：rank + title + rating
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (movie.rank > 0) {
                    Text(
                        text = "#${movie.rank}",
                        color = RedRank,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
                Text(
                    text = movie.title + if (movie.originalTitle.isNotBlank() && movie.originalTitle != movie.title) " / ${movie.originalTitle}" else "",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (movie.rating > 0f) {
                    Text(
                        text = "★ ${movie.rating}",
                        color = Accent,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }

            // 年份
            Text(text = movie.year, color = TextSecondary, fontSize = 12.sp)

            // 导演 + 主演
            val infoParts = mutableListOf<String>()
            if (movie.directors.isNotEmpty()) infoParts.add("导演: ${movie.directors.take(2).joinToString(" / ")}")
            if (movie.actors.isNotEmpty()) infoParts.add("主演: ${movie.actors.take(3).joinToString(" / ")}")
            if (infoParts.isNotEmpty()) {
                Text(
                    text = infoParts.joinToString(" | "),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // 简介
            if (movie.summary.isNotBlank()) {
                Text(
                    text = movie.summary,
                    color = TextPrimary.copy(alpha = 0.7f),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            // 按钮
            Row(
                modifier = Modifier.padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = { onSearch(movie.title) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("搜磁力", fontSize = 12.sp)
                }
                if (movie.doubanUrl.isNotBlank()) {
                    OutlinedButton(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(movie.doubanUrl))
                            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(32.dp)
                    ) {
                        Text("打开豆瓣", fontSize = 12.sp)
                    }
                }
            }
        }
    }
    HorizontalDivider(color = Divider, thickness = 0.5.dp)
}
