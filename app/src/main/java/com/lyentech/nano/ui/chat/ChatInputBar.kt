package com.lyentech.nano.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyentech.nano.data.model.ReasoningEffort

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 底部输入区。左侧「+」弹出图片来源（相册/拍照），右侧是发送键；生成中发送键变成停止键。
 *
 * 待发送图片与推理强度都由调用方持有状态，本组件只负责展示与回调，方便单独替换样式。
 */
@Composable
fun ChatInputBar(
    value: String,
    onValueChange: (String) -> Unit,
    pendingImages: List<PendingImage>,
    onPickImage: () -> Unit,
    onTakePhoto: () -> Unit,
    onRemoveImage: (String) -> Unit,
    reasoningEffort: ReasoningEffort,
    onReasoningEffortChange: (ReasoningEffort) -> Unit,
    sending: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    var attachMenuOpen by remember { mutableStateOf(false) }
    var effortMenuOpen by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (pendingImages.isNotEmpty()) {
                PendingImageStrip(pendingImages, onRemoveImage)
                Spacer(Modifier.size(6.dp))
            }

            Row(verticalAlignment = Alignment.Bottom) {
                // 「+」：图片来源
                Box {
                    IconButton(onClick = { attachMenuOpen = true }) {
                        Icon(Icons.Filled.Add, contentDescription = "添加图片")
                    }
                    DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("从相册选择") },
                            leadingIcon = { Icon(Icons.Filled.PhotoLibrary, null) },
                            onClick = {
                                attachMenuOpen = false
                                onPickImage()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("拍照") },
                            leadingIcon = { Icon(Icons.Filled.PhotoCamera, null) },
                            onClick = {
                                attachMenuOpen = false
                                onTakePhoto()
                            },
                        )
                    }
                }

                TextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp, max = 132.dp),
                    placeholder = { Text("发消息…") },
                    maxLines = 6,
                    shape = RoundedCornerShape(22.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    ),
                )

                Spacer(Modifier.width(4.dp))

                // 推理强度：当前档位直接显示在按钮上，点开切换。
                // 用带内边距的 Row 而不是 IconButton —— IconButton 会强加 48dp 固定尺寸，
                // 「图标 + 两个汉字」会被挤到裁切。
                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .clickable { effortMenuOpen = true }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.Tune,
                            contentDescription = "推理强度",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            reasoningEffort.label,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                    DropdownMenu(expanded = effortMenuOpen, onDismissRequest = { effortMenuOpen = false }) {
                        ReasoningEffort.entries.forEach { effort ->
                            DropdownMenuItem(
                                text = { Text(effort.label) },
                                onClick = {
                                    effortMenuOpen = false
                                    onReasoningEffortChange(effort)
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.width(4.dp))

                // 生成中显示停止，否则显示发送
                FilledIconButton(
                    onClick = { if (sending) onStop() else onSend() },
                    enabled = sending || value.isNotBlank() || pendingImages.isNotEmpty(),
                    modifier = Modifier.padding(bottom = 2.dp),
                ) {
                    Icon(
                        if (sending) Icons.Filled.Stop else Icons.AutoMirrored.Filled.Send,
                        contentDescription = if (sending) "停止生成" else "发送",
                    )
                }
            }
        }
    }
}

/** 待发送图片的缩略图，右上角带移除按钮。 */
@Composable
private fun PendingImageStrip(images: List<PendingImage>, onRemove: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        images.forEach { image ->
            Box {
                AsyncImage(
                    model = image.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                )
                IconButton(
                    onClick = { onRemove(image.uri.toString()) },
                    modifier = Modifier.align(Alignment.TopEnd).size(20.dp),
                ) {
                    Box(Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.scrim)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "移除图片",
                            tint = MaterialTheme.colorScheme.inverseOnSurface,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}
