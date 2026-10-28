package com.lyentech.nano.ui.chat

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 聊天界面共用的小组件与动画，避免在渲染层里重复实现。
 */

/** 无限循环的透明度振荡，用于流式光标等"呼吸"效果。 */
@Composable
fun rememberInfiniteAlpha(): State<Float> {
    val transition = rememberInfiniteTransition(label = "alpha")
    return transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )
}

/** 用户消息里附带的图片缩略图条。 */
@Composable
fun ImageStrip(images: List<ChatImage>) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        images.forEach { image ->
            AsyncImage(
                model = image.uri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.size(0.dp))
    }
}
