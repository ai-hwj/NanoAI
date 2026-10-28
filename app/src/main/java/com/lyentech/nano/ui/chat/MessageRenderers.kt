package com.lyentech.nano.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 时间线条目的渲染层。每个条目类型对应一个独立 Composable，正文（[AgentMessageBody]）和
 *      卡片（[ToolCallCard] / [ReasoningBlock]）都是可整体替换的组件：换显示形态只改组件本体，
 *      上游事件订阅和 [TimelineReducer] 不动。
 *
 * 全部颜色取自 [MaterialTheme.colorScheme]，深浅色主题自动适配，组件内不出现写死的颜色值。
 */

/** 用户气泡：右对齐，主色底。 */
@Composable
fun UserMessageItem(item: ChatTimelineItem.UserMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp),
        ) {
            Column(Modifier.widthIn(max = 320.dp).padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (item.images.isNotEmpty()) {
                    ImageStrip(item.images)
                    if (item.text.isNotBlank()) Spacer(Modifier.height(8.dp))
                }
                if (item.text.isNotBlank()) {
                    Text(item.text, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

/**
 * 模型正文。默认走纯文本；接 markdown 渲染时只替换本函数体，不改调用方。
 * [ChatTimelineItem.AgentMessage.streaming] 为 true 时在末尾追加一个光标，提示仍在生成。
 */
@Composable
fun AgentMessageBody(item: ChatTimelineItem.AgentMessage) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(Modifier.widthIn(max = 560.dp)) {
            Column {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (item.streaming) {
                    StreamingCursor()
                }
            }
        }
    }
}

/** 流式光标：一个随时间淡入淡出的细竖条。 */
@Composable
private fun StreamingCursor() {
    val alpha by rememberInfiniteAlpha()
    Box(
        Modifier
            .padding(top = 4.dp)
            .size(width = 8.dp, height = 3.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

/** 推理过程：默认折叠，标题行可点开。 */
@Composable
fun ReasoningBlock(item: ChatTimelineItem.Reasoning) {
    var expanded by remember(item.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Filled.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = if (item.streaming) "正在思考…" else "已深度思考",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "收起" else "展开",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    text = item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** 工具调用卡片：执行中显示转圈，结束后展开显示参数与结果摘要。 */
@Composable
fun ToolCallCard(item: ChatTimelineItem.ToolCall) {
    val done = item.result != null || item.error != null
    var expanded by remember(item.id) { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (done) {
                    Icon(
                        if (item.error != null) Icons.Filled.ErrorOutline else Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = if (item.error != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Icon(
                    Icons.Filled.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (item.error != null && !expanded) {
                Text(
                    text = item.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(top = 6.dp)) {
                    if (item.args.isNotEmpty()) {
                        CodeLabel("参数")
                        Text(
                            item.args.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val result = item.error ?: item.result
                    if (result != null) {
                        Spacer(Modifier.height(6.dp))
                        CodeLabel(if (item.error != null) "错误" else "结果")
                        Text(
                            result,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = if (item.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 居中状态行。 */
@Composable
fun NoticeItem(item: ChatTimelineItem.Notice) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(
            item.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 条目分派：新增条目类型时只改这里加一个分支。 */
@Composable
fun TimelineItemView(item: ChatTimelineItem) {
    when (item) {
        is ChatTimelineItem.UserMessage -> UserMessageItem(item)
        is ChatTimelineItem.AgentMessage -> AgentMessageBody(item)
        is ChatTimelineItem.Reasoning -> ReasoningBlock(item)
        is ChatTimelineItem.ToolCall -> ToolCallCard(item)
        is ChatTimelineItem.Notice -> NoticeItem(item)
    }
}
