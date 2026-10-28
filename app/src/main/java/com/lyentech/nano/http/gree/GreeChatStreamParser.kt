package com.lyentech.nano.http.gree

import org.json.JSONObject

/**
 * @author by jason-何伟杰，2026/9/29
 *
 * des: 把 OpenAI 兼容的响应 JSON 翻译成一小段「流事件」，屏蔽 chunk 里哪些字段为空或缺失的差异。
 *      不依赖 ADK，也不发网络请求，便于单测。
 */

/** What a single streaming chunk contributes. Every field is optional on the wire. */
internal data class StreamDelta(
    val text: String? = null,
    val model: String? = null,
    /** 这一片里带回来的工具调用分片；非工具响应时为空。 */
    val toolCalls: List<ToolCallFragment> = emptyList(),
)

/**
 * 流式 `delta.tool_calls[i]` 的一片。
 *
 * OpenAI 的形状是：同一个调用按 `index` 编号，`function.name` 只在第一片出现，
 * `function.arguments` 则是一段 JSON 文本被切成若干片依次到达。
 */
internal data class ToolCallFragment(
    val index: Int,
    val id: String?,
    val name: String?,
    val argumentsDelta: String?,
)

/** 一条已经拼完的工具调用：arguments 是完整的 JSON 文本，还没解析成结构。 */
internal data class ParsedToolCall(val id: String?, val name: String, val arguments: String)

/** What a single non-streaming response contributes. */
internal data class CompletionResult(
    val text: String,
    val toolCalls: List<ParsedToolCall> = emptyList(),
    val finishReason: String? = null,
    val model: String? = null,
)

/**
 * 把流式分片拼成完整调用。
 *
 * 按 `index` 归位而不是按 `id`：有的兼容端点只在首片带 id、后续片省略。`name` 采取「先到先得」
 * 而不是拼接，因为规范上它只出现一次，而重复下发整名的端点若按拼接处理就会得到
 * `web_searchweb_search` 这种不存在的函数名。
 */
internal class ToolCallAccumulator {

    private class Slot(var id: String? = null, var name: String = "") {
        val arguments = StringBuilder()
    }

    private val slots = LinkedHashMap<Int, Slot>()

    fun feed(fragments: List<ToolCallFragment>) {
        fragments.forEach { feed(it) }
    }

    fun feed(fragment: ToolCallFragment) {
        val slot = slots.getOrPut(fragment.index) { Slot() }
        if (slot.id.isNullOrBlank()) slot.id = fragment.id?.takeIf { it.isNotBlank() }
        if (slot.name.isEmpty()) fragment.name?.takeIf { it.isNotBlank() }?.let { slot.name = it }
        fragment.argumentsDelta?.let { slot.arguments.append(it) }
    }

    /** 只有拿到函数名的槽位才算一次调用；光有参数没有名字的碎片是端点的噪音。 */
    fun calls(): List<ParsedToolCall> =
        slots.values.filter { it.name.isNotEmpty() }.map { ParsedToolCall(it.id, it.name, it.arguments.toString()) }
}

/**
 * Reads every `choices[0].delta.content` fragment out of an SSE body.
 *
 * Payloads that are not JSON, that carry no `choices`, or whose delta has no usable content are
 * skipped: servers routinely send a first chunk with an empty delta and a role-only payload, and
 * surfacing those as empty partial responses would make the UI render nothing.
 */
internal fun parseStreamDeltas(raw: String): List<StreamDelta> {
    val deltas = mutableListOf<StreamDelta>()
    parseSsePayloads(raw) { payload -> parseStreamChunk(payload)?.let { deltas.add(it) } }
    return deltas
}

/**
 * Parses one SSE payload, returning null when it carries nothing usable.
 *
 * "Usable" now means a text fragment or at least one tool-call fragment: a chunk that only opens a
 * tool call has no content at all, and dropping it would silently lose the call.
 *
 * `optString` is deliberately not used for the nullable checks: it returns the four-character string
 * `"null"` for a JSON null (it only substitutes a fallback when the key is *absent*), which would
 * leak a literal `null` into the prompt. `isNull` is the only check that distinguishes the two cases.
 */
internal fun parseStreamChunk(payload: String): StreamDelta? {
    val json = runCatching { JSONObject(payload) }.getOrNull() ?: return null
    val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return null
    val text = if (delta.isNull("content")) null else delta.optString("content").takeIf { it.isNotEmpty() }
    val toolCalls = delta.parseToolCallFragments()
    if (text == null && toolCalls.isEmpty()) return null
    return StreamDelta(text = text, model = json.optionalString("model"), toolCalls = toolCalls)
}

/** `delta.tool_calls` -> 分片列表；缺失或形状不对时给空列表，不让一碎片拖垮整条流。 */
internal fun JSONObject.parseToolCallFragments(): List<ToolCallFragment> {
    val calls = optJSONArray("tool_calls") ?: return emptyList()
    return (0 until calls.length()).mapNotNull { i ->
        val call = calls.optJSONObject(i) ?: return@mapNotNull null
        val function = call.optJSONObject("function")
        ToolCallFragment(
            index = call.optInt("index", i),
            id = call.optionalString("id"),
            name = function?.optionalString("name"),
            // arguments 可能是空串（首片常常只有 name），这里必须区分「没有这个字段」和「空串」，
            // 两者都不能凭空造出内容，所以直接把可空值交给累积器。
            argumentsDelta = if (function == null || function.isNull("arguments")) null else function.optString("arguments"),
        )
    }
}

/**
 * Reads a full (non-streaming) chat completion.
 *
 * @return null when the message carries neither text nor tool calls, which means the server answered
 *   with something this client cannot act on. A `finish_reason = "tool_calls"` response legitimately
 *   has `content: null`, so text alone is not the criterion.
 */
internal fun parseCompletion(body: String): CompletionResult? {
    val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
    val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return null
    val message = choice.optJSONObject("message") ?: return null
    val text = if (message.isNull("content")) "" else message.optString("content")
    val toolCalls = message.parseCompleteToolCalls()
    if (text.isEmpty() && toolCalls.isEmpty()) return null
    return CompletionResult(
        text = text,
        toolCalls = toolCalls,
        finishReason = choice.optionalString("finish_reason"),
        model = json.optionalString("model"),
    )
}

/** `message.tool_calls` -> 完整调用列表；没有名字的条目直接忽略。 */
internal fun JSONObject.parseCompleteToolCalls(): List<ParsedToolCall> {
    val calls = optJSONArray("tool_calls") ?: return emptyList()
    return (0 until calls.length()).mapNotNull { i ->
        val call = calls.optJSONObject(i) ?: return@mapNotNull null
        val function = call.optJSONObject("function") ?: return@mapNotNull null
        val name = function.optionalString("name") ?: return@mapNotNull null
        ParsedToolCall(
            id = call.optionalString("id"),
            name = name,
            arguments = if (function.isNull("arguments")) "" else function.optString("arguments"),
        )
    }
}

/** A string field that is present, non-null and non-empty; anything else reads as absent. */
private fun JSONObject.optionalString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
