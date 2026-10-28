package com.lyentech.nano.ui.chat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.events.Event
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.types.Blob
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.lyentech.nano.agent.tools.AgentToolkit
import com.lyentech.nano.data.db.NanoDatabase
import com.lyentech.nano.data.model.Conversation
import com.lyentech.nano.data.model.Project
import com.lyentech.nano.data.model.ReasoningEffort
import com.lyentech.nano.data.model.ThemeMode
import com.lyentech.nano.data.repo.AgentServices
import com.lyentech.nano.data.repo.ConversationRepository
import com.lyentech.nano.data.repo.ProjectRepository
import com.lyentech.nano.data.repo.SettingsRepository
import com.lyentech.nano.http.gree.GreeChatModel
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 聊天页的 ViewModel。负责会话/项目数据、ADK runner 生命周期和一次对话回合的执行。
 *
 * 依赖全部在这里手工装配（项目里已有的 Koin 尚未接线，为不引入额外改动先保持显式构造）。
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val database = NanoDatabase.get(application)
    private val settings = SettingsRepository(application)

    /**
     * 会话事件持久化交给 ADK 自带的 Room session service，和本应用库是两个独立文件。
     * 走进程级单例，避免屏幕重建时新旧数据库句柄互相干扰，见 [AgentServices]。
     */
    private val sessionService = AgentServices.sessionService(application)

    private val conversationRepo = ConversationRepository(
        conversationDao = database.conversationDao(),
        sessionService = sessionService,
        appName = APP_NAME,
        userId = USER_ID,
    )
    private val projectRepo = ProjectRepository(database.projectDao(), database.conversationDao())

    /**
     * 工具装配点。挂在 ViewModel 上而不是 buildRunner 里：切换推理强度会重建 runner 和 agent，
     * 而工具带着状态（任务清单），不该跟着一起清零。
     */
    private val toolkit by lazy { AgentToolkit(getApplication()) }

    /**
     * ADK runner。它在构造时就绑定了 agent（进而绑定了模型），而推理强度是模型的一个参数，
     * 所以改变推理强度必须换一个新的 runner，不能原地改 —— 见 [setReasoningEffort]。
     */
    private var runner: InMemoryRunner = buildRunner(settings.reasoningEffort.value)

    private fun buildRunner(effort: ReasoningEffort): InMemoryRunner =
        AgentServices.runner(getApplication(), createAgent(effort))

    // ---- 对外状态 ----

    private val _currentId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentId.asStateFlow()

    private val _timeline = MutableStateFlow<List<ChatTimelineItem>>(emptyList())
    val timeline: StateFlow<List<ChatTimelineItem>> = _timeline.asStateFlow()

    private val _sending = MutableStateFlow(false)
    val sending: StateFlow<Boolean> = _sending.asStateFlow()

    private val _reasoningEffort = settings.reasoningEffort
    val reasoningEffort: StateFlow<ReasoningEffort> = _reasoningEffort

    val themeMode: StateFlow<ThemeMode> = settings.themeMode

    val conversations: StateFlow<List<Conversation>> =
        conversationRepo.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val projects: StateFlow<List<Project>> =
        projectRepo.observeProjects()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * 当前会话所属项目，用于标题栏副标题；null 表示未归类。
     *
     * 由「当前会话 id」和「项目列表」共同推导，而不是单独维护一份状态，避免归类/删除项目后
     * 忘了同步这个字段。
     */
    val currentProjectName: StateFlow<String?> =
        combine(_currentId, conversations, projects) { id, conversationList, projectList ->
            val projectId = conversationList.firstOrNull { it.id == id }?.projectId ?: return@combine null
            projectList.firstOrNull { it.id == projectId }?.name
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private var turnJob: Job? = null
    private var reducer = TimelineReducer()

    init {
        viewModelScope.launch {
            // 冷启动恢复上次会话。这里必须直接查库，不能读 conversations.value：
            // 它是 stateIn 产物，此刻还没开始收集，值仍是初始的空列表。
            val last = settings.lastConversationId.value
            val restored = last?.let { conversationRepo.findById(it) }
            if (restored != null) openConversation(restored.id) else newConversation()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // 只关自己拥有的 runner。sessionService 是进程级共享的，不能在这里关 ——
        // 屏幕重建时旧 ViewModel 的 onCleared 可能晚于新 ViewModel 的初始化。
        runner.close()
    }

    // ---- 会话操作 ----

    /** 新建会话并切换过去，[projectId] 决定它是否落在某个项目里。 */
    fun newConversation(projectId: String? = null) {
        viewModelScope.launch {
            val conversation = conversationRepo.createConversation(projectId)
            // 直接调 suspend 版本，不要走 openConversation()：那个会再起一个协程，
            // 建会话与切换之间就出现了可以插入别的操作的窗口。
            activate(conversation.id)
        }
    }

    /** 切换到指定会话：从 ADK 读回历史事件并回放成时间线。 */
    fun openConversation(conversationId: String) {
        viewModelScope.launch { activate(conversationId) }
    }

    private suspend fun activate(conversationId: String) {
        turnJob?.cancel()
        turnJob = null
        _currentId.value = conversationId
        settings.setLastConversationId(conversationId)
        reducer = TimelineReducer()
        val events = conversationRepo.loadEvents(conversationId)
        reducer.replay(events, AGENT_NAME)
        _timeline.value = reducer.snapshot()
        _sending.value = false
    }

    /**
     * 删除会话。删的是当前会话时，回退到一个新会话，避免停在已删除的页面上。
     */
    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            conversationRepo.delete(conversationId)
            if (_currentId.value == conversationId) newConversation()
        }
    }

    fun renameConversation(conversationId: String, title: String) {
        viewModelScope.launch { conversationRepo.rename(conversationId, title) }
    }

    fun assignConversationToProject(conversationId: String, projectId: String?) {
        viewModelScope.launch { conversationRepo.assignToProject(conversationId, projectId) }
    }

    // ---- 项目操作 ----

    fun createProject(name: String, description: String = "") {
        viewModelScope.launch { projectRepo.create(name, description) }
    }

    fun renameProject(id: String, name: String) {
        viewModelScope.launch { projectRepo.rename(id, name) }
    }

    /** 删除项目，其下会话退回未归类；聊天记录保留。 */
    fun deleteProject(id: String) {
        viewModelScope.launch { projectRepo.delete(id) }
    }

    fun setThemeMode(mode: ThemeMode) = settings.setThemeMode(mode)

    /**
     * 切换推理强度并重建 runner。若正在生成则先停止：换 runner 会让当前回合的会话状态错位。
     */
    fun setReasoningEffort(effort: ReasoningEffort) {
        if (settings.reasoningEffort.value == effort) return
        stop()
        settings.setReasoningEffort(effort)
        val old = runner
        runner = buildRunner(effort)
        // 旧 runner 已无人使用，立刻释放它持有的工具/插件资源。
        old.close()
    }

    // ---- 一轮对话 ----

    /**
     * 发送一条用户消息并开始流式接收回复。
     *
     * [imageUris] 是用户选中的本地图片；字节会在 IO 线程读出来后作为 inlineData part 交给模型。
     * 当前的 Gree 端点是否支持视觉输入取决于部署，不支持时模型会忽略该 part。
     */
    fun send(text: String, imageUris: List<Uri> = emptyList()) {
        val conversationId = _currentId.value ?: return
        if (_sending.value) return
        if (text.isBlank() && imageUris.isEmpty()) return

        // 缩略图立刻上屏（用 uri 直接渲染），图片字节在下面的 IO 线程里再读。
        reducer.addUserMessage(text, imageUris.map { ChatImage(it.toString()) })
        _timeline.value = reducer.snapshot()
        _sending.value = true

        turnJob = viewModelScope.launch {
            try {
                val images = withContext(Dispatchers.IO) { imageUris.mapNotNull { it.readImage() } }
                streamTurn(conversationId, text, images)
            } catch (e: CancellationException) {
                // 取消可能发生在用户已经切到别的会话之后：此时 reducer/_timeline 都属于新会话，
                // 往上面写「已停止生成」就会污染新会话的界面。守卫同 streamTurn。
                if (_currentId.value == conversationId) {
                    reducer.addNotice("已停止生成")
                    _timeline.value = reducer.snapshot()
                }
                throw e
            } catch (e: Exception) {
                if (_currentId.value == conversationId) {
                    reducer.addNotice("请求失败：${e.message ?: e::class.simpleName}")
                    _timeline.value = reducer.snapshot()
                }
            } finally {
                // _sending 只归当前活跃会话的回合所有：若用户已切走，新会话的输入框不该被这个
                // 早已废弃的回合解锁/上锁。
                if (_currentId.value == conversationId) _sending.value = false
                // 这两步会挂起，而本协程可能已经被 stop() 取消——在被取消的协程里继续挂起会立刻抛
                // CancellationException。改用 NonCancellable 保证收尾一定跑完（排序时间和标题更新
                // 都是用户可见的结果，不能丢）。
                withContext(NonCancellable) {
                    conversationRepo.touch(conversationId)
                    maybeAutoTitle(conversationId, text)
                }
            }
        }
    }

    /** 读一张本地图片的字节；content uri 需要 ContentResolver，file uri 直接读文件。 */
    private fun Uri.readImage(): LoadedImage? = try {
        val resolver = getApplication<Application>().contentResolver
        val mime = resolver.getType(this) ?: "image/jpeg"
        val bytes = resolver.openInputStream(this)?.use { it.readBytes() } ?: return null
        LoadedImage(this, bytes, mime)
    } catch (e: Exception) {
        // 单张图读失败不该让整条消息发不出去，跳过它即可。
        null
    }

    /** 用户主动停止本轮生成。取消订阅会连带取消 ADK 的模型调用（GreeChatModel 里 abort 了 HTTP call）。 */
    fun stop() {
        turnJob?.cancel()
        turnJob = null
        _sending.value = false
    }

    private suspend fun streamTurn(conversationId: String, text: String, images: List<LoadedImage>) {
        val events = runner.runAsync(
            userId = USER_ID,
            sessionId = conversationId,
            newMessage = buildUserContent(text, images),
            runConfig = RunConfig(
                streamingMode = StreamingMode.SSE,
                maxLlmCalls = MAX_LLM_CALLS,
            ),
        )
        // 只有模型自己的事件进时间线；用户事件已经由 addUserMessage 本地上屏。
        events.collect { event: Event ->
            if (event.author != AGENT_NAME) return@collect
            // 取消之后仍可能有已缓冲的事件到达，此时 reducer 已经属于别的会话了，必须丢掉，
            // 否则旧会话的文字会串到新会话的时间线上。
            if (_currentId.value != conversationId) return@collect
            reducer.accept(event)
            val next = reducer.snapshot()
            withContext(Dispatchers.Main) { _timeline.value = next }
        }
    }

    /** 首条用户消息发出后，用消息前 20 个字符当会话标题，替换占位的「新对话」。 */
    private suspend fun maybeAutoTitle(conversationId: String, firstMessage: String) {
        if (firstMessage.isBlank()) return
        // 同样要查库：stateIn 的当前值可能是过期的，用它判断会把已有标题的会话再改一次。
        val current = conversationRepo.findById(conversationId) ?: return
        if (current.title != ConversationRepository.DEFAULT_TITLE) return
        val title = firstMessage.replace('\n', ' ').trim().take(20)
        if (title.isNotEmpty()) conversationRepo.rename(conversationId, title)
    }

    private fun buildUserContent(text: String, images: List<LoadedImage>): Content {
        val parts = mutableListOf<Part>()
        if (text.isNotBlank()) parts += Part(text = text)
        images.forEach { image ->
            parts += Part(inlineData = Blob(mimeType = image.mimeType, data = image.bytes))
        }
        return Content(role = Role.USER, parts = parts)
    }

    /**
     * 组装本轮会话用的 agent。
     *
     * 工具来自 [AgentToolkit]：清单、工作区文件、联网、设备四类；Gree 端点走 OpenAI 兼容的
     * tool_calls，所以这些声明会被 GreeChatModel 序列化进请求体，模型返回的调用再被执行成
     * `role = "tool"` 结果回灌（一轮里可能要发多次请求，上限见 [MAX_LLM_CALLS]）。
     *
     * 子 Agent 编排（TaskTool）这一轮不接入：它需要模型工厂，且和端点的 tool_calls 支持无关，
     * 等工具链在真机上跑通再说。
     *
     * 技能目录存在时才挂 SkillToolset：ADK 会在每次请求前把 `list_skills` / `load_skill` 这类
     * 工具补进声明里，并往 system 提示注入技能清单。
     */
    private fun createAgent(effort: ReasoningEffort): LlmAgent =
        LlmAgent(
            name = AGENT_NAME,
            model = GreeChatModel(reasoningEffort = effort.wireValue),
            tools =
                toolkit.coreTools(includeWorkspaceFiles = true) +
                toolkit.webTools() +
                toolkit.deviceTools(),
            toolsets = listOfNotNull(toolkit.skillToolsetOrNull()),
            instruction =
                Instruction(
                    """
                    你是一个专业、严谨的智能助手。回答要准确、结构清晰；不确定时明确说明，不编造事实。

                    你有一批工具，用它们而不是凭记忆回答：
                    - 时效性信息、资料、数据：先 web_search 再 web_fetch，引用要给出链接。
                    - 本机状态（电量、机型、系统版本）：直接查设备工具，不要让用户自己报。
                    - 任务超过 3 步：用 todo_write 建清单，做一步改一步，收尾时把全部标记完成。
                    - 文件工具只在应用自己的工作目录内生效，路径写相对路径。
                    - 工具返回 error 字段时，按它的说明换做法，别原样重试同一个调用。
                    """
                        .trimIndent()
                ),
        )

    companion object {
        /** 与 [AgentServices] 共享，保证 runner、仓库和设置读的是同一套 app/user 标识。 */
        const val APP_NAME = AgentServices.APP_NAME
        const val USER_ID = AgentServices.USER_ID
        const val AGENT_NAME = "Assistant"
        private const val MAX_LLM_CALLS = 8
    }
}

/** 已经读取到内存的待发送图片，避免把 [Uri] 一路传到网络层。 */
data class LoadedImage(val uri: Uri, val bytes: ByteArray, val mimeType: String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LoadedImage) return false
        return uri == other.uri && mimeType == other.mimeType && bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = uri.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}
