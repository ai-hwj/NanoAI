package com.lyentech.nano.ui.litellm

import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lyentech.nano.global.AppTheme
import com.lyentech.nano.global.ScopedActivity

/**
 * @author by jason-何伟杰，2026/10/15
 * des: 本地模型测试页 —— 只用导入目录里的 .litertlm，不触发任何网络下载。
 *
 * 存在的理由：正式示例页 [com.lyentech.nano.ui.RtLlmChatActivity] 的模型来自 HuggingFace 下载或
 * adb push，模型一失败就整页停在「需要下载」；而端侧调参（换后端、开 thinking、改提示词）需要
 * 一个能反复加载、并且把失败原因留在屏幕上的地方。
 */
class LocalModelLabActivity : ScopedActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val vm: LocalModelLabViewModel = viewModel()
                val models by vm.models.collectAsState()
                val selected by vm.selected.collectAsState()
                val loadResults by vm.loadResults.collectAsState()
                val ready by vm.ready.collectAsState()
                val busy by vm.busy.collectAsState()
                val sending by vm.sending.collectAsState()
                val items by vm.timeline.collectAsState()
                val backend by vm.backend.collectAsState()
                val threads by vm.threads.collectAsState()
                val thinking by vm.thinking.collectAsState()
                val streaming by vm.streaming.collectAsState()
                val instruction by vm.instruction.collectAsState()
                val toolsEnabled by vm.toolsEnabled.collectAsState()

                // 文件选择：OpenDocument 而不是 GetContent，因为 GetContent 拿不到持久可用的 uri，
                // 而模型要在导入目录里长期存在，需要一个能反复读的授权。
                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.OpenDocument()
                ) { uri -> uri?.let(vm::importModel) }

                LocalModelLabScreen(
                    models = models,
                    selected = selected,
                    loadResults = loadResults,
                    ready = ready,
                    busy = busy,
                    sending = sending,
                    items = items,
                    backend = backend,
                    threads = threads,
                    thinking = thinking,
                    streaming = streaming,
                    instruction = instruction,
                    toolsEnabled = toolsEnabled,
                    importDirectoryPath = vm.importDirectoryPath,
                    onBack = ::finish,
                    // 回来时重扫一次：用户常常在这期间用 adb push 补了文件。
                    onRefresh = vm::refreshModels,
                    onPick = vm::select,
                    onImport = { picker.launch(arrayOf("*/*")) },
                    onLoad = vm::load,
                    onBackendChange = vm::setBackend,
                    onThreadsChange = vm::setThreads,
                    onThinkingChange = vm::setThinking,
                    onStreamingChange = vm::setStreaming,
                    onInstructionChange = vm::setInstruction,
                    onToolsEnabledChange = vm::setToolsEnabled,
                    onSend = vm::send,
                    onStop = vm::stop,
                    onClear = vm::clearTimeline,
                )
            }
        }
    }
}
