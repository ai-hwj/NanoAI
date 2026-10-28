package com.lyentech.nano.http.gree

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Role
import org.json.JSONArray
import org.json.JSONObject

/**
 * @author by jason-何伟杰，2026/9/29
 *
 * des: 纯数据搬运层：把 ADK 的请求/响应结构翻译成 OpenAI 兼容的 JSON，以及把响应 JSON 读回来。
 *      这里不做网络、线程和日志，方便被单测直接调用。
 */

/** The wire dialect uses OpenAI's role names, which differ from ADK's for the model turn. */
internal const val WIRE_ROLE_SYSTEM = "system"
internal const val WIRE_ROLE_USER = "user"
internal const val WIRE_ROLE_ASSISTANT = "assistant"

/**
 * Flattens [request] into the OpenAI-compatible chat-completions body, including `stream` so the
 * server knows whether to answer with SSE.
 *
 * The system instruction is emitted as a leading `system` message rather than a top-level field
 * because the endpoint follows the OpenAI chat schema, where a separate system field does not exist.
 *
 * Tool declarations come from `config.tools`, which ADK has already merged from the agent's tools
 * and toolsets (see `LlmRequest.appendTools`); when there is nothing callable the `tools` field is
 * left out entirely rather than sent as an empty array.
 *
 * [reasoningEffort] 为 UI 选择的推理强度（low/medium/high），以 OpenAI 兼容的 `reasoning_effort`
 * 字段下发；端点不识别时它只是一个被忽略的额外字段，不会让请求失败。
 */
internal fun LlmRequest.toChatRequestJson(stream: Boolean, reasoningEffort: String? = null): JSONObject {
    val messages = JSONArray()
    systemInstructionText(config)?.let { text ->
        messages.put(
            JSONObject().put("role", WIRE_ROLE_SYSTEM).put("content", text)
        )
    }
    // 工具调用与结果的配对在这一条请求内重建，见 List<Content>.toWireMessages。
    contents.toWireMessages().forEach { messages.put(it) }
    val body = JSONObject().put("messages", messages).put("stream", stream)
    config.tools?.toWireTools()?.let { tools ->
        body.put("tools", tools)
        // "auto" 让模型自己决定是否调用，这是 OpenAI 的默认值；写出来是为了不依赖各家兼容
        // 端点对缺省值各自的解释。
        body.put("tool_choice", "auto")
    }
    if (!reasoningEffort.isNullOrBlank()) body.put("reasoning_effort", reasoningEffort)
    return body
}

/**
 * The concatenated text of the system instruction, or null when there is nothing to send.
 *
 * `systemInstruction` is a structured [Content], not a String, so its text has to be rebuilt from
 * its parts; non-text parts are dropped because the wire format's `content` is a plain string.
 */
internal fun systemInstructionText(config: GenerateContentConfig): String? =
    config.systemInstruction?.let { content ->
        content.text(separator = "\n", includeThoughts = false).ifBlank { null }
    }

/** Maps an ADK role onto the wire; unknown roles fall back to `user`, the safer of the two. */
internal fun Content.toWireRole(): String =
    when (role) {
        Role.MODEL -> WIRE_ROLE_ASSISTANT
        Role.USER -> WIRE_ROLE_USER
        else -> WIRE_ROLE_USER
    }
