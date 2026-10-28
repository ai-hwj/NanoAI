package com.lyentech.nano.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyentech.nano.data.model.Conversation
import com.lyentech.nano.data.model.Project
import com.lyentech.nano.data.model.ThemeMode

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 抽屉内容。上半部分是固定菜单（主题切换、项目、定时任务），下半部分是对话历史，
 *      两者用一个可滚动列表承载，条目多时不会把菜单挤出屏幕。
 */
@Composable
fun ChatDrawerContent(
    conversations: List<Conversation>,
    projects: List<Project>,
    themeMode: ThemeMode,
    currentConversationId: String?,
    onSelectConversation: (Conversation) -> Unit,
    onDeleteConversation: (Conversation) -> Unit,
    onAssignToProject: (Conversation, String?) -> Unit,
    onNewChat: () -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onOpenProjects: () -> Unit,
) {
    var themeMenuOpen by remember { mutableStateOf(false) }

    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 12.dp),
        ) {
            // ---- 顶部菜单 ----
            item {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    Text(
                        "Nano AI",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 12.dp),
                    )
                }
            }

            item {
                Box {
                    DrawerMenuItem(
                        icon = { Icon(themeIcon(themeMode), contentDescription = null) },
                        label = "主题：${themeMode.label}",
                        onClick = { themeMenuOpen = true },
                    )
                    DropdownMenu(expanded = themeMenuOpen, onDismissRequest = { themeMenuOpen = false }) {
                        ThemeMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = {
                                    themeMenuOpen = false
                                    onThemeModeChange(mode)
                                },
                            )
                        }
                    }
                }
            }

            item {
                DrawerMenuItem(
                    icon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                    label = "项目",
                    trailing = projects.size.takeIf { it > 0 }?.toString(),
                    onClick = onOpenProjects,
                )
            }

            item {
                DrawerMenuItem(
                    icon = { Icon(Icons.Filled.Alarm, contentDescription = null) },
                    label = "定时任务",
                    badge = "待开发",
                    enabled = false,
                    onClick = {},
                )
            }

            item {
                DrawerMenuItem(
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    label = "新建对话",
                    onClick = onNewChat,
                )
            }

            item {
                HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                Text(
                    "对话历史",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 24.dp, bottom = 4.dp),
                )
            }

            if (conversations.isEmpty()) {
                item {
                    Text(
                        "还没有对话记录",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, top = 8.dp),
                    )
                }
            }

            items(conversations, key = { it.id }) { conversation ->
                ConversationRow(
                    conversation = conversation,
                    projectName = projects.firstOrNull { it.id == conversation.projectId }?.name,
                    selected = conversation.id == currentConversationId,
                    projects = projects,
                    onSelect = { onSelectConversation(conversation) },
                    onDelete = { onDeleteConversation(conversation) },
                    onAssign = { projectId -> onAssignToProject(conversation, projectId) },
                )
            }
        }
    }
}

/** 单个菜单行；[badge] 用于「待开发」这类标记，[enabled] 为 false 时整体置灰且不可点。 */
@Composable
private fun DrawerMenuItem(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
    trailing: String? = null,
    badge: String? = null,
    enabled: Boolean = true,
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 图标与文字统一跟随 enabled 变淡，而不是各写一份颜色。
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { icon() }
        }
        Spacer(Modifier.size(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (badge != null) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        if (trailing != null) {
            Text(
                trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 历史会话行：点击切换，右侧「…」可归类到项目或删除。 */
@Composable
private fun ConversationRow(
    conversation: Conversation,
    projectName: String?,
    selected: Boolean,
    projects: List<Project>,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onAssign: (String?) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .background(
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                shape = RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer
                else MaterialTheme.colorScheme.onSurface,
            )
            if (projectName != null) {
                Text(
                    projectName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Filled.MoreHoriz,
                    contentDescription = "会话操作",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Text(
                    "添加到项目",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
                )
                DropdownMenuItem(
                    text = { Text("不归类") },
                    onClick = {
                        menuOpen = false
                        onAssign(null)
                    },
                )
                projects.forEach { project ->
                    DropdownMenuItem(
                        text = { Text(project.name) },
                        onClick = {
                            menuOpen = false
                            onAssign(project.id)
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("删除对话") },
                    leadingIcon = { Icon(Icons.Filled.DeleteOutline, null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** 主题图标跟随当前模式，让菜单一眼看出会切到什么。 */
private fun themeIcon(mode: ThemeMode) = when (mode) {
    ThemeMode.SYSTEM -> Icons.Filled.SettingsBrightness
    ThemeMode.LIGHT -> Icons.Filled.LightMode
    ThemeMode.DARK -> Icons.Filled.DarkMode
}

/** 抽屉顶部留白，避免内容顶到状态栏。 */
@Composable
fun DrawerHeaderSpacer() {
    Spacer(Modifier.height(32.dp))
}
