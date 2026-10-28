package com.lyentech.nano.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 专业聊天页骨架：顶部工具栏（抽屉/标题/新建/更多）+ 消息时间线 + 悬浮置底按钮 + 输入区。
 *
 * 页面本身不持有业务状态，全部通过参数注入，方便后续替换任意一层（输入区、时间线渲染）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreenV2(
    title: String,
    subtitle: String?,
    items: List<ChatTimelineItem>,
    sending: Boolean,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onShowMoreMenu: () -> Unit,
    inputBar: @Composable () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (subtitle != null) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onOpenDrawer) {
                        Icon(Icons.Filled.Menu, contentDescription = "打开抽屉")
                    }
                },
                actions = {
                    IconButton(onClick = onNewChat) {
                        Icon(Icons.Filled.Edit, contentDescription = "新建对话")
                    }
                    IconButton(onClick = onShowMoreMenu) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            MessageTimeline(
                items = items,
                modifier = Modifier.weight(1f),
            )
            Box(Modifier.navigationBarsPadding()) {
                inputBar()
            }
        }
    }
}

/**
 * 消息时间线：空列表时显示欢迎语；用户手动滚动离开底部后浮出「回到底部」按钮，
 * 生成过程中新内容到达则自动跟随。
 */
@Composable
private fun MessageTimeline(
    items: List<ChatTimelineItem>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 是否贴着底部。这里必须让 derivedStateOf 每次重组都读到最新的 items，所以把它写在
    // rememberUpdatedState 里而不是用 remember{} 把它连同首次的 items 一起冻结。
    val latestItems by rememberUpdatedState(items)
    val isAtBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= latestItems.lastIndex
        }
    }

    /**
     * 新内容到达时自动跟随到底部。
     *
     * 监听的是「最后一条条目的内容」而不是 items.size：流式回复期间列表长度不变，只是同一条
     * AgentMessage 的文本在变长，只盯 size 会导致生成过程完全不跟随。
     */
    val tailKey = items.lastOrNull()?.let { item ->
        when (item) {
            is ChatTimelineItem.AgentMessage -> item.text.length
            is ChatTimelineItem.Reasoning -> item.text.length
            else -> 0
        }
    } ?: 0

    LaunchedEffect(listState, items.size, tailKey) {
        if (items.isEmpty()) return@LaunchedEffect
        // 首次进入（或切会话）无条件拉到底，否则打开历史会话会停在最旧的消息上。
        val firstLayout = listState.layoutInfo.totalItemsCount == 0
        if (firstLayout || isAtBottom) {
            listState.animateScrollToItem(items.lastIndex)
        }
    }

    Box(modifier.fillMaxWidth()) {
        if (items.isEmpty()) {
            EmptyState()
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Box(Modifier.fillMaxWidth()) { TimelineItemView(item) }
                }
            }
        }

        AnimatedVisibility(
            visible = items.isNotEmpty() && !isAtBottom,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            FilledTonalIconButton(
                onClick = { scope.launch { listState.animateScrollToItem(items.lastIndex) } },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Filled.ArrowDownward, contentDescription = "回到底部")
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "今天想聊点什么？",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(max = 280.dp),
        )
    }
}
