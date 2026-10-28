package com.lyentech.nano.ui.litellm

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyentech.nano.ui.chat.ChatTimelineItem
import com.lyentech.nano.ui.chat.TimelineItemView
import java.io.File

/**
 * @author by jason-何伟杰，2026/10/15
 * des: 本地模型测试页的界面层。只做参数展示与事件回传，状态全在 [LocalModelLabViewModel]。
 *
 * 与示例页 [com.lyentech.nano.ui.RtLlmChatActivity] 的区别：不走网络下载，模型一律来自导入目录；
 * 后端/线程数/thinking/提示词/工具开关全部摆在明面上，方便在真机上逐个变量试。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LocalModelLabScreen(
    models: List<File>,
    selected: File?,
    loadResults: Map<String, LocalModelLabViewModel.LoadResult>,
    ready: Boolean,
    busy: Boolean,
    sending: Boolean,
    items: List<ChatTimelineItem>,
    backend: BackendChoice,
    threads: CpuThreads,
    thinking: ThinkingSetting,
    streaming: Boolean,
    instruction: String,
    toolsEnabled: Boolean,
    importDirectoryPath: String,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onPick: (File) -> Unit,
    onImport: () -> Unit,
    onLoad: (File) -> Unit,
    onBackendChange: (BackendChoice) -> Unit,
    onThreadsChange: (CpuThreads) -> Unit,
    onThinkingChange: (ThinkingSetting) -> Unit,
    onStreamingChange: (Boolean) -> Unit,
    onInstructionChange: (String) -> Unit,
    onToolsEnabledChange: (Boolean) -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("本地模型测试") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Filled.Refresh, contentDescription = "重新扫描导入目录")
                    }
                    TextButton(onClick = onClear) { Text("清空") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
        ) {
            ModelPanel(
                models = models,
                selected = selected,
                loadResults = loadResults,
                busy = busy,
                sending = sending,
                backend = backend,
                threads = threads,
                thinking = thinking,
                streaming = streaming,
                instruction = instruction,
                toolsEnabled = toolsEnabled,
                importDirectoryPath = importDirectoryPath,
                onPick = onPick,
                onImport = onImport,
                onLoad = onLoad,
                onBackendChange = onBackendChange,
                onThreadsChange = onThreadsChange,
                onThinkingChange = onThinkingChange,
                onStreamingChange = onStreamingChange,
                onInstructionChange = onInstructionChange,
                onToolsEnabledChange = onToolsEnabledChange,
            )
            Transcript(items = items, modifier = Modifier.weight(1f))
            Box(Modifier.navigationBarsPadding()) {
                LabInputBar(
                    // 能不能发取决于「有没有加载好模型」，与时间线里有没有内容无关：加载结果那些
                    // 提示行本身就在时间线里，用 items.isNotEmpty() 判断会恒为真，等于没判断。
                    enabled = ready && !busy && selected != null,
                    sending = sending,
                    onSend = onSend,
                    onStop = onStop,
                )
            }
        }
    }
}

/**
 * 模型与参数面板：可折叠。
 *
 * 折叠不是装饰 —— 展开时它占掉大半屏，而真机测试时用户既要看参数又要看输出，允许收起才能两者兼顾。
 */
@Composable
private fun ModelPanel(
    models: List<File>,
    selected: File?,
    loadResults: Map<String, LocalModelLabViewModel.LoadResult>,
    busy: Boolean,
    sending: Boolean,
    backend: BackendChoice,
    threads: CpuThreads,
    thinking: ThinkingSetting,
    streaming: Boolean,
    instruction: String,
    toolsEnabled: Boolean,
    importDirectoryPath: String,
    onPick: (File) -> Unit,
    onImport: () -> Unit,
    onLoad: (File) -> Unit,
    onBackendChange: (BackendChoice) -> Unit,
    onThreadsChange: (CpuThreads) -> Unit,
    onThinkingChange: (ThinkingSetting) -> Unit,
    onStreamingChange: (Boolean) -> Unit,
    onInstructionChange: (String) -> Unit,
    onToolsEnabledChange: (Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(true) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Description,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = selected?.name ?: "未选择模型",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起" else "展开") }
            }

            if (!expanded) return@Column

            if (models.isEmpty()) {
                Text(
                    text = "导入目录里还没有 .litertlm 文件。\n" +
                            "电脑上下好后，adb push 到：\n$importDirectoryPath/\n" +
                            "或者直接用下面的「导入」按钮从手机存储里选。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                // 限高 + 内滚：导入目录里堆十几个模型时，面板不能把输出区域挤没。
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    models.forEach { file ->
                        ModelRow(
                            file = file,
                            isSelected = file.absolutePath == selected?.absolutePath,
                            result = loadResults[file.absolutePath],
                            onPick = { onPick(file) },
                            onLoad = { onLoad(file) },
                        )
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onImport, enabled = !busy && !sending) { Text("导入模型文件") }
                Text(
                    text = "目录：$importDirectoryPath",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }

            OptionRow("后端", BackendChoice.entries, backend, { it.label }, onBackendChange)
            // 线程数只对 CPU 有意义，GPU 档位下直接置灰，免得用户调了没反应以为坏了。
            OptionRow(
                label = "CPU 线程",
                options = CpuThreads.entries,
                selected = threads,
                labelOf = { it.label },
                onSelect = onThreadsChange,
                enabled = backend == BackendChoice.CPU,
            )
            OptionRow(
                "思考模式",
                ThinkingSetting.entries,
                thinking,
                { it.label },
                onThinkingChange,
            )
            ToggleRow("流式输出", streaming, onStreamingChange)
            ToggleRow("挂设备工具", toolsEnabled, onToolsEnabledChange)

            OutlinedTextField(
                value = instruction,
                onValueChange = onInstructionChange,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(96.dp),
                label = { Text("系统提示词") },
                textStyle = MaterialTheme.typography.bodySmall,
                keyboardOptions = KeyboardOptions.Default,
            )
        }
    }
}

@Composable
private fun ModelRow(
    file: File,
    isSelected: Boolean,
    result: LocalModelLabViewModel.LoadResult?,
    onPick: () -> Unit,
    onLoad: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPick)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.surface
            )
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                file.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val detail =
                when (result) {
                    // 加载结果直接标在文件名下面：换后端重试时能一眼对比上一次为什么失败。
                    is LocalModelLabViewModel.LoadResult.Ok ->
                        "${formatBytes(file.length())} · 加载成功 ${result.backend} ${result.elapsedMillis / 1000.0}s"

                    is LocalModelLabViewModel.LoadResult.Failed ->
                        "${formatBytes(file.length())} · 上次失败：${result.reason}"

                    null -> formatBytes(file.length())
                }
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color =
                    if (result is LocalModelLabViewModel.LoadResult.Failed) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onLoad) { Text("加载") }
    }
}

/** 一排单选筹码。选项少的时候比下拉菜单少一次点击，测试页里每次点击都是时间。 */
@Composable
private fun <T> OptionRow(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    onSelect: (T) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color =
                if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.width(72.dp),
        )
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                enabled = enabled,
                label = { Text(labelOf(option), style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.padding(end = 6.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun Transcript(items: List<ChatTimelineItem>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    // 跟随的是「最后一条的长度」而不是列表长度：流式回复时长度不变、只有同一条文本在变长。
    val tailLength =
        when (val last = items.lastOrNull()) {
            is ChatTimelineItem.AgentMessage -> last.text.length
            is ChatTimelineItem.Reasoning -> last.text.length
            else -> 0
        }
    LaunchedEffect(items.size, tailLength) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(items, key = { it.id }) { item ->
            Box(Modifier.fillMaxWidth()) { TimelineItemView(item) }
        }
    }
}

@Composable
private fun LabInputBar(enabled: Boolean, sending: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (enabled) "发消息…" else "先选一个模型并加载") },
                enabled = enabled && !sending,
                maxLines = 5,
                shape = RoundedCornerShape(22.dp),
            )
            Spacer(Modifier.width(6.dp))
            // 忙的时候给转圈而不是禁用图标：加载能跑十几秒，没有反馈用户会以为点漏了。
            if (sending) {
                FilledIconButton(onClick = onStop) {
                    Icon(Icons.Filled.Stop, contentDescription = "停止生成")
                }
            } else {
                FilledIconButton(
                    onClick = {
                        val trimmed = text.trim()
                        if (trimmed.isNotEmpty()) {
                            onSend(trimmed)
                            text = ""
                        }
                    },
                    enabled = enabled && text.isNotBlank(),
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String =
    when {
        bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
        bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
        else -> "$bytes B"
    }
