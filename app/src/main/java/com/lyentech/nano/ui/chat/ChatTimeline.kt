package com.lyentech.nano.ui.chat

import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Part

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 聊天时间线模型 —— ADK 的 [Event] 流与界面之间的唯一契约。
 *
 * 界面只认识 [ChatTimelineItem]，不认识 ADK 类型，所以正文/推理/工具卡片的渲染层可以整体替换
 * 而不动事件订阅逻辑；反过来，事件到条目的翻译逻辑集中在 [TimelineReducer] 里，也不掺 UI 细节。
 */
sealed interface ChatTimelineItem {
    /** 稳定标识：用于 LazyColumn 的 key，流式更新时不重建整个列表。 */
    val id: String

    data class UserMessage(
        override val id: String,
        val text: String,
        val images: List<ChatImage>,
    ) : ChatTimelineItem

    /** 模型正文。流式回复期间同一个 [id] 被反复替换成更长的文本。 */
    data class AgentMessage(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
    ) : ChatTimelineItem

    /** 推理过程。默认折叠，展开后显示 [text]。 */
    data class Reasoning(
        override val id: String,
        val text: String,
        val streaming: Boolean = false,
    ) : ChatTimelineItem

    /** 一次工具调用。执行中 [result] 为 null，结果回来后就地补上。 */
    data class ToolCall(
        override val id: String,
        val name: String,
        val args: Map<String, Any?>,
        val result: String? = null,
        val error: String? = null,
    ) : ChatTimelineItem

    /** 状态行（就绪提示、错误），居中显示，不做气泡。 */
    data class Notice(
        override val id: String,
        val text: String,
    ) : ChatTimelineItem
}

/** 用户消息里的一张图片，[uri] 为本地 content/file uri。 */
data class ChatImage(val uri: String, val mimeType: String? = null)

/**
 * 把 ADK 事件流累积成时间线条目。
 *
 * 一次 [accept] 只处理一个事件，内部按需追加或原地更新条目，因此可以直接挂在事件订阅循环里逐条
 * 调用。条目顺序即列表顺序：正文块被工具调用打断时会各自成为独立条目，和 ChatGPT 的呈现一致。
 */
class TimelineReducer {

    private val items = mutableListOf<ChatTimelineItem>()
    private var counter = 0

    /** 当前流式正文条目的 id；null 表示下一个正文块要新建条目。 */
    private var openTextId: String? = null
    private var openReasoningId: String? = null

    fun snapshot(): List<ChatTimelineItem> = items.toList()

    fun addUserMessage(text: String, images: List<ChatImage> = emptyList()) {
        endText()
        endReasoning()
        items += ChatTimelineItem.UserMessage(nextId("user"), text, images)
    }

    fun addNotice(text: String) {
        endText()
        endReasoning()
        items += ChatTimelineItem.Notice(nextId("notice"), text)
    }

    /**
     * 从历史事件列表重建时间线，用于进入已有会话。
     *
     * 只回放非 partial 的事件：partial 是流式中间态，它们的最终聚合结果已经作为非 partial
     * 事件落库，重复回放会让同一段文字出现两次。
     */
    fun replay(events: List<Event>, agentName: String) {
        events.forEach { event ->
            if (event.partial) return@forEach
            when (event.author) {
                agentName -> accept(event)
                USER_AUTHOR -> {
                    val text = event.content?.text(includeThoughts = false).orEmpty()
                    if (text.isNotBlank()) addUserMessage(text)
                }
            }
        }
    }

    /**
     * 吸收一个模型事件。正文、推理、工具调用在同一个事件里可以并存，所以这里按 part 分派，
     * 而不是假设一个事件只携带一种内容。
     */
    fun accept(event: Event) {
        event.errorMessage?.let { addNotice("模型返回错误：$it") }

        val parts = event.content?.parts.orEmpty()
        val hasToolPart = parts.any { it.functionCall != null || it.functionResponse != null }
        // 出现工具调用意味着当前正文块已经说完，后续文字属于新的回复块。
        if (hasToolPart) endText()

        parts.forEach { part ->
            when {
                part.functionCall != null -> appendToolCall(part)
                part.functionResponse != null -> completeToolCall(part)
                part.thought == true -> appendReasoning(part.text, event.partial)
                !part.text.isNullOrEmpty() -> appendText(part.text.orEmpty(), event.partial)
            }
        }

        // 结束一个完整（非 partial）事件后收束正文，让下一轮回复另起一条。
        if (!event.partial && !hasToolPart) endText()
    }

    /**
     * 把一段正文写进当前正文块。
     *
     * partial 事件携带的是**增量**，追加累积；非 partial 事件携带本轮**完整**文本，是权威版本，
     * 因此覆盖而不是追加 —— 否则流式分片会和最终聚合结果叠加，同一段话出现两遍。
     */
    private fun appendText(chunk: String, streaming: Boolean) {
        endReasoning()
        val id = openTextId
        if (id == null) {
            val newId = nextId("agent")
            openTextId = newId
            items += ChatTimelineItem.AgentMessage(newId, chunk, streaming)
            return
        }
        val index = items.indexOfLast { it.id == id }
        val current = items[index] as ChatTimelineItem.AgentMessage
        val merged = if (streaming) current.text + chunk else chunk
        items[index] = current.copy(text = merged, streaming = streaming)
    }

    /** 与 [appendText] 同样的增量/覆盖规则。 */
    private fun appendReasoning(chunk: String?, streaming: Boolean) {
        val text = chunk.orEmpty()
        if (text.isEmpty()) return
        val id = openReasoningId
        if (id == null) {
            val newId = nextId("reason")
            openReasoningId = newId
            items += ChatTimelineItem.Reasoning(newId, text, streaming)
            return
        }
        val index = items.indexOfLast { it.id == id }
        val current = items[index] as ChatTimelineItem.Reasoning
        val merged = if (streaming) current.text + text else text
        items[index] = current.copy(text = merged, streaming = streaming)
    }

    private fun appendToolCall(part: Part) {
        endReasoning()
        val call = part.functionCall ?: return
        // 以 ADK 的 call id 作条目 id，工具结果回来时才能准确定位到对应卡片。
        val id = call.id ?: nextId("tool")
        // 流式工具调用的多个分片会共用同一个 call id（参数是逐步拼出来的）。条目 id 同时是
        // LazyColumn 的 key，重复 key 会直接崩，所以已存在的卡片只就地补充参数，不再追加。
        val existing = items.indexOfLast { it is ChatTimelineItem.ToolCall && it.id == id }
        if (existing >= 0) {
            val current = items[existing] as ChatTimelineItem.ToolCall
            items[existing] = current.copy(args = current.args + call.args.orEmpty())
            return
        }
        items += ChatTimelineItem.ToolCall(id = id, name = call.name.orEmpty(), args = call.args.orEmpty())
    }

    private fun completeToolCall(part: Part) {
        val response = part.functionResponse ?: return
        val id = response.id ?: return
        val index = items.indexOfLast { it is ChatTimelineItem.ToolCall && it.id == id }
        if (index < 0) return
        val call = items[index] as ChatTimelineItem.ToolCall
        val payload = response.response.orEmpty()
        items[index] =
            if (payload.containsKey("error")) {
                call.copy(error = payload["error"]?.toString())
            } else {
                call.copy(result = payload.entries.joinToString(", ") { "${it.key}=${it.value}" })
            }
    }

    private fun endText() {
        openTextId = null
    }

    private fun endReasoning() {
        openReasoningId = null
    }

    private fun nextId(prefix: String): String = "$prefix-${counter++}"

    private companion object {
        const val USER_AUTHOR = "user"
    }
}
