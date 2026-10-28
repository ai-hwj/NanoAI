package com.lyentech.nano.ui.litellm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.litertlm.LiteRtLmModel
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.lyentech.nano.http.LiteRtLmModelStore
import com.lyentech.nano.ui.chat.ChatTimelineItem
import com.lyentech.nano.ui.chat.TimelineReducer
import java.io.File
import kotlin.concurrent.thread
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @author by jason-何伟杰，2026/10/15
 * des: 本地模型测试页的状态机 —— 只负责「选文件 → 开引擎 → 跑一轮对话」，不碰界面。
 *
 * 与正式聊天页 [com.lyentech.nano.ui.chat.ChatViewModel] 的分工：这里不做会话持久化、不做项目归类，
 * 因为测试页的目标是把模型跑起来并看见原始输出，任何额外持久化只会让「加载失败」的现场变模糊。
 * 时间线仍然复用 [TimelineReducer]，这样工具调用卡片和推理块的渲染与正式页完全一致。
 */
internal class LocalModelLabViewModel(application: Application) : AndroidViewModel(application) {

    private val sessionService = InMemorySessionService()

    /** 当前打开的原生引擎。它持有 native 句柄，换模型/退出页面都必须显式关掉。 */
    private var loaded: LiteRtLmModel? = null
    private var runner: InMemoryRunner? = null
    private var turnJob: Job? = null
    private var reducer = TimelineReducer()

    // ---- 对外状态 ----

    private val _timeline = MutableStateFlow<List<ChatTimelineItem>>(emptyList())
    val timeline: StateFlow<List<ChatTimelineItem>> = _timeline.asStateFlow()

    /** 导入目录里的候选模型，按修改时间倒序。 */
    private val _models = MutableStateFlow<List<File>>(emptyList())
    val models: StateFlow<List<File>> = _models.asStateFlow()

    private val _selected = MutableStateFlow<File?>(null)
    val selected: StateFlow<File?> = _selected.asStateFlow()

    /** 每个模型各自的加载结果，key 是文件绝对路径；失败信息留在界面上供反复对照。 */
    private val _loadResults = MutableStateFlow<Map<String, LoadResult>>(emptyMap())
    val loadResults: StateFlow<Map<String, LoadResult>> = _loadResults.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** 一轮对话是否在跑：跑的时候输入框与「加载」按钮都要锁住，引擎一次只能有一个会话。 */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    /**
     * 是否已经有一个可用的引擎。
     *
     * 单独暴露而不是让界面看 `loadResults`：加载成功但随后切到另一个模型时，上一个模型的 Ok 记录
     * 还在 map 里，用 map 推导会得出「可以发消息」的错误结论，而此刻 runner 是空的。
     */
    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * 加载参数。改这些值不会自动重载 —— 上一次的加载结果还留在屏幕上，用户点「加载」才生效，
     * 否则每拖一次滑块就重新初始化一次引擎（每次几秒到十几秒），页面会卡得没法操作。
     */
    private val _backend = MutableStateFlow(BackendChoice.CPU)
    val backend: StateFlow<BackendChoice> = _backend.asStateFlow()

    private val _threads = MutableStateFlow(CpuThreads.AUTO)
    val threads: StateFlow<CpuThreads> = _threads.asStateFlow()

    private val _thinking = MutableStateFlow(ThinkingSetting.OFF)
    val thinking: StateFlow<ThinkingSetting> = _thinking.asStateFlow()

    private val _streaming = MutableStateFlow(true)
    val streaming: StateFlow<Boolean> = _streaming.asStateFlow()

    /** 提示词与是否挂设备工具，都做成可改的：测试页的价值就在于换着试。 */
    private val _instruction = MutableStateFlow(LiteRtLmChatAgent.DEFAULT_INSTRUCTION.trimIndent())
    val instruction: StateFlow<String> = _instruction.asStateFlow()

    private val _toolsEnabled = MutableStateFlow(true)
    val toolsEnabled: StateFlow<Boolean> = _toolsEnabled.asStateFlow()

    val importDirectoryPath: String = LiteRtLmModelStore.importDirectoryPath(application)

    init {
        refreshModels()
    }

    /** 重新扫描导入目录。用户 adb push 完回来点一下就能看到新文件，不用重开页面。 */
    fun refreshModels() {
        val directory = LiteRtLmModelStore.importDirectory(getApplication())
        val found = LiteRtLmModelStore.listModels(directory)
        _models.value = found
        // 选中项可能已被用户删掉：只有仍存在于列表里才保留，否则退回第一个。
        val current = _selected.value
        _selected.value =
            when {
                current != null && found.any { it.absolutePath == current.absolutePath } -> current
                else -> found.firstOrNull()
            }
    }

    fun select(file: File) {
        _selected.value = file
    }

    /**
     * 把用户挑中的文件搬进导入目录，然后刷新列表并选中它。
     *
     * 走 IO 线程：拷贝一个 1.5 GB 的模型要几十秒，放主线程必然 ANR。
     */
    fun importModel(uri: Uri) {
        if (_busy.value || _sending.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val file = withContext(Dispatchers.IO) {
                    LiteRtLmModelStore.importFrom(getApplication(), uri)
                }
                refreshModels()
                _selected.value = _models.value.firstOrNull { it.absolutePath == file.absolutePath } ?: file
                note("已导入 ${file.name}（${formatSize(file.length())}）")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                note("导入失败：${e.message ?: e::class.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }

    /**
     * 打开 [file] 并初始化引擎。
     *
     * 为什么先 close 再 load：一个进程里同时持两个 LiteRT-LM 引擎（各 1.5 GB 权重）在手机上会
     * 直接 OOM，所以切换模型必须先释放上一个。close 放在自己的线程里 —— native 释放不保证快，
     * 而这里已经在 viewModelScope 的协程中，阻塞的是 IO 线程，可以接受。
     */
    fun load(file: File) {
        if (_busy.value || _sending.value) return
        viewModelScope.launch {
            _busy.value = true
            releaseEngine()
            val backend = _backend.value
            val threads = _threads.value
            val startedAt = System.currentTimeMillis()
            note("开始加载 ${file.name}（${formatSize(file.length())}，${backend.label}）…")
            try {
                val model = withContext(Dispatchers.IO) {
                    LiteRtLmChatAgent.createModel(file, getApplication<Application>().cacheDir, backend, threads)
                }
                // 立刻 initialize：坏文件/不支持的量化格式要在这里暴露，而不是拖到第一条消息。
                withContext(Dispatchers.IO) { model.engine.initialize() }
                loaded = model
                buildRunner(model, file)
                val elapsed = System.currentTimeMillis() - startedAt
                mark(
                    file,
                    LoadResult.Ok(backend = backend.label, elapsedMillis = elapsed),
                )
                _ready.value = true
                note("加载成功：${file.name}，${backend.label}，耗时 ${elapsed / 1000.0} 秒。可以开始提问了。")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Throwable 而不是 Exception：缺少 native 库的设备抛的是 UnsatisfiedLinkError，
                // 那种情况下必须让页面活下来并显示原因，而不是整个进程挂掉。
                mark(
                    file,
                    LoadResult.Failed("${e::class.simpleName}: ${e.message ?: "无详细信息"}"),
                )
                note("加载失败：${e.message ?: e::class.simpleName}")
            } finally {
                _busy.value = false
            }
        }
    }

    private fun buildRunner(model: LiteRtLmModel, file: File) {
        val instruction = _instruction.value
        val withTools = _toolsEnabled.value
        // 不挂工具时走同一条装配路径，只是 tools 空着；这样对比「模型自身能力」与「工具调用能力」时，
        // 除工具外的变量完全一致。
        val agent =
            if (withTools) {
                LiteRtLmChatAgent.create(model, getApplication(), instruction)
            } else {
                LlmAgent(
                    name = LiteRtLmChatAgent.NAME,
                    model = model,
                    instruction = Instruction(instruction),
                )
            }
        runner =
            InMemoryRunner(
                agent = agent,
                appName = APP_NAME,
                sessionService = sessionService,
            )
        note(
            "已绑定 ${file.name}：提示词 ${if (instruction.isBlank()) "空" else "${instruction.length} 字符"}，" +
                    "设备工具${if (withTools) "已开启" else "已关闭"}。"
        )
    }

    /**
     * 换提示词或工具开关后只记下来、不动现场：这两项在 agent 构造时就绑定了，改它们必须重建 runner，
     * 而重建要重新加载模型。用户点「加载」时才生效，避免每次敲字都重来一遍。
     */
    fun setInstruction(text: String) {
        _instruction.value = text
    }

    fun setToolsEnabled(enabled: Boolean) {
        _toolsEnabled.value = enabled
    }

    fun setBackend(choice: BackendChoice) {
        _backend.value = choice
    }

    fun setThreads(choice: CpuThreads) {
        _threads.value = choice
    }

    fun setThinking(setting: ThinkingSetting) {
        _thinking.value = setting
    }

    fun setStreaming(enabled: Boolean) {
        _streaming.value = enabled
    }

    fun send(text: String) {
        val active = runner ?: return
        if (_sending.value || _busy.value) return
        if (text.isBlank()) return
        reducer.addUserMessage(text)
        _timeline.value = reducer.snapshot()
        _sending.value = true
        val useStreaming = _streaming.value
        val sessionId = SESSION_ID

        turnJob = viewModelScope.launch {
            try {
                val events =
                    active.runAsync(
                        userId = USER_ID,
                        sessionId = sessionId,
                        newMessage = Content(role = Role.USER, parts = listOf(Part(text = text))),
                        runConfig =
                            RunConfig(
                                streamingMode = if (useStreaming) StreamingMode.SSE else StreamingMode.NONE,
                                // 小模型会在工具上打转，而整个回合里输入是锁住的，这里和示例页保持一致。
                                maxLlmCalls = MAX_LLM_CALLS,
                            ),
                    )
                events.collect { event: Event ->
                    // 只有 agent 自己的事件进时间线；用户消息已经本地上屏。
                    if (event.author != LiteRtLmChatAgent.NAME) return@collect
                    // 取消之后仍可能有缓冲事件到达：此时 reducer 已经属于新的一轮，必须丢掉，
                    // 否则上一轮的文字会串到下一轮的时间线上（守卫理由同 ChatViewModel.streamTurn）。
                    if (!_sending.value) return@collect
                    reducer.accept(event)
                    val next = reducer.snapshot()
                    withContext(Dispatchers.Main) { _timeline.value = next }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reducer.addNotice("生成失败：${e.message ?: e::class.simpleName}")
                _timeline.value = reducer.snapshot()
            } finally {
                _sending.value = false
            }
        }
    }

    fun stop() {
        turnJob?.cancel()
        turnJob = null
        _sending.value = false
    }

    /** 清空时间线并重建一条会话，避免上一轮的历史影响下一轮的对比。 */
    fun clearTimeline() {
        stop()
        reducer = TimelineReducer()
        _timeline.value = emptyList()
        note("已清空对话记录。")
    }

    /**
     * 释放 native 引擎。
     *
     * `close()` 不保证快（要拆掉 native 线程与显存/内存），而 onCleared 之后没有任何界面可以承接
     * 异常，所以放在自己起的一次性线程里并吞掉失败 —— 这里崩掉会带崩整个进程。
     */
    private fun releaseEngine() {
        runner = null
        // 同步清 ready：这个函数在 load() 里是在新引擎建好之前就调用的，晚清会让界面在
        // 「旧引擎已释放、新引擎还没好」的窗口里以为可以发消息。
        _ready.value = false
        val model = loaded ?: return
        loaded = null
        thread(name = "litertlm-close") {
            try {
                model.close()
            } catch (_: Throwable) {
                // 没有可上报的对象：持有它的页面已经离开。
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        releaseEngine()
    }

    private fun note(text: String) {
        reducer.addNotice(text)
        _timeline.value = reducer.snapshot()
    }

    private fun mark(file: File, result: LoadResult) {
        _loadResults.value = _loadResults.value + (file.absolutePath to result)
    }

    /** 一次加载的结果，用于在模型列表上直接标出「这个文件我试过了，行/不行」。 */
    sealed interface LoadResult {
        data class Ok(val backend: String, val elapsedMillis: Long) : LoadResult

        data class Failed(val reason: String) : LoadResult
    }

    private companion object {
        const val APP_NAME = "LocalModelLab"
        const val USER_ID = "local-user"
        const val SESSION_ID = "local-model-lab"
        const val MAX_LLM_CALLS = 8

        /** 文件大小按 MB/GB 显示，模型动辄上 G，用字节数读不出来。 */
        fun formatSize(bytes: Long): String =
            when {
                bytes >= 1L shl 30 -> "%.2f GB".format(bytes.toDouble() / (1L shl 30))
                bytes >= 1L shl 20 -> "%.1f MB".format(bytes.toDouble() / (1L shl 20))
                else -> "$bytes B"
            }
    }
}
