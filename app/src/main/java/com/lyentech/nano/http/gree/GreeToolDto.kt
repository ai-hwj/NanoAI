package com.lyentech.nano.http.gree

import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Tool
import com.google.adk.kt.types.Type
import org.json.JSONArray
import org.json.JSONObject

/**
 * @author by jason-何伟杰，2026/10/10
 * des: 工具调用在 OpenAI 兼容协议上的双向映射。
 *
 * 请求方向：ADK 的 [Tool]/[FunctionDeclaration]/[Schema] -> `tools` 数组；历史里的
 * [FunctionCall]/[FunctionResponse] -> `assistant.tool_calls` 与 `role = "tool"` 消息。
 * 响应方向：解析出来的 [ParsedToolCall] -> ADK 的 [FunctionCall]。
 *
 * 和 GreeChatDto / GreeChatStreamParser 一样，这里只做数据搬运：不发请求、不起协程，
 * 因此可以被宿主 JVM 单测直接调用。
 */

/** `tools[].type` 与 `tool_calls[].type` 的判别值；chat-completions 目前只认 function。 */
private const val WIRE_TYPE_FUNCTION = "function"

/** 工具结果消息的角色。它不属于 ADK 的 Role 枚举，只在 wire 上存在。 */
internal const val WIRE_ROLE_TOOL = "tool"

/**
 * ADK 已经合并好的工具声明 -> OpenAI 的 `tools` 数组。
 *
 * ADK 把多个 `FunctionDeclaration` 放在同一个 [Tool] 里（见 `LlmRequest.appendTools`），而
 * OpenAI 是一个函数一个条目，所以这里要拍平。没有可声明的函数时返回 null，让请求体干脆不带
 * `tools` 字段 —— 带一个空数组会让部分兼容端点直接判 400。
 */
internal fun List<Tool>.toWireTools(): JSONArray? {
    val declarations = flatMap { it.functionDeclarations ?: emptyList() }
    if (declarations.isEmpty()) return null
    return JSONArray().apply { declarations.forEach { put(it.toWireTool()) } }
}

internal fun FunctionDeclaration.toWireTool(): JSONObject =
    JSONObject()
        .put("type", WIRE_TYPE_FUNCTION)
        .put(
            "function",
            JSONObject()
                .put("name", name)
                .put("description", description)
                .put("parameters", parameters.toWireParameters()),
        )

/**
 * ADK 的 [Schema] 是 Google 风味的一份 JSON Schema 子集，这里降级成标准 JSON Schema。
 *
 * 明确丢掉的两处：`format`（ADK 用 `int32`/`int64`/`enum` 这类取值，是 Gemini 的私有约定，
 * JSON Schema 校验器会把 `enum` 当成非法 format）和 `anyOf`（本项目工具没用到，真要联合类型
 * 时再按端点支持的情况补）。
 */
internal fun Schema?.toWireParameters(): JSONObject {
    val schema = this ?: Schema(type = Type.OBJECT)
    val json = schema.toJsonSchema()
    // 函数参数在协议上必须是 object；根节点缺 type 时补上，免得端点拒收。
    if (!json.has("type")) json.put("type", "object")
    return json
}

private fun Schema.toJsonSchema(): JSONObject =
    JSONObject().apply {
        type?.wireName()?.let { put("type", it) }
        description?.let { put("description", it) }
        title?.let { put("title", it) }
        enum?.takeIf { it.isNotEmpty() }?.let { put("enum", JSONArray(it)) }
        if (nullable == true) put("nullable", true)
        default?.let { put("default", it.toWireJsonValue()) }
        properties?.takeIf { it.isNotEmpty() }?.let { props ->
            put(
                "properties",
                JSONObject().apply { props.forEach { (key, value) -> put(key, value.toJsonSchema()) } },
            )
        }
        required?.takeIf { it.isNotEmpty() }?.let { put("required", JSONArray(it)) }
        items?.let { put("items", it.toJsonSchema()) }
        // 数值/字符串/数组的约束直接透传，都是 JSON Schema 标准字段。
        minimum?.let { put("minimum", it) }
        maximum?.let { put("maximum", it) }
        minLength?.let { put("minLength", it) }
        maxLength?.let { put("maxLength", it) }
        minItems?.let { put("minItems", it) }
        maxItems?.let { put("maxItems", it) }
        pattern?.let { put("pattern", it) }
        // object 即使没有属性也要给一个空 properties，否则部分端点的校验器认为 schema 不完整。
        if (optString("type") == "object" && !has("properties")) put("properties", JSONObject())
    }

/** ADK 的 [Type] -> JSON Schema 的类型名；未指定与 NULL 不产出类型字段。 */
private fun Type.wireName(): String? =
    when (this) {
        Type.STRING -> "string"
        Type.NUMBER -> "number"
        Type.INTEGER -> "integer"
        Type.BOOLEAN -> "boolean"
        Type.ARRAY -> "array"
        Type.OBJECT -> "object"
        Type.TYPE_UNSPECIFIED, Type.NULL -> null
    }

/**
 * 历史 contents -> `messages` 条目。
 *
 * 这里必须先做一次「调用 ↔ 结果」配对，原因有两层：
 *
 * 1. ADK-Kotlin 1.3.1 的 `HistoryRewriterProcessor.stripClientFunctionCallIds` 会在把历史交给模型
 *    之前，**无条件**把 `adk-` 前缀的 `FunctionCall.id` / `FunctionResponse.id` 置空（私有实现，
 *    没有开关）。也就是说模型层从历史里拿到的 id 一定是 null。
 * 2. OpenAI 协议又要求 `assistant.tool_calls[].id` 与紧随其后的 `role = "tool"` 消息里的
 *    `tool_call_id` 严格对应，缺一条就会被端点判 400。
 *
 * 所以 id 在这一条请求内部按出现顺序重建：调用先进队，结果出队配对，两侧写同一个 id。ADK 本身已经
 * 保证了 `[…, model(call), user(response), …]` 的相邻顺序（配不上对它自己就抛 IllegalStateException），
 * 顺序配对因此可靠；显式 id 若真出现（未来的 ADK 不再剥离）优先按 id 认，并覆盖合成值。
 *
 * 还没有结果的调用会连同它所在消息里的 tool_calls 一起丢掉；配不到调用的结果同样丢掉 —— 两种情况
 * 都没法用合法的消息形状表达，留着只会让整个请求失败。
 */
internal fun List<Content>.toWireMessages(): List<JSONObject> {
    data class Slot(var id: String, var answered: Boolean)

    val slots = mutableMapOf<Pair<Int, Int>, Slot>()
    val callOfResponse = mutableMapOf<Pair<Int, Int>, Pair<Int, Int>>()
    val pending = ArrayDeque<Pair<Int, Int>>()

    forEachIndexed { ci, content ->
        content.parts.forEachIndexed { pi, part ->
            val call = part.functionCall
            val response = part.functionResponse
            when {
                call != null -> {
                    val key = ci to pi
                    slots[key] = Slot(call.id?.takeIf { it.isNotBlank() } ?: synthesizedCallId(ci, pi), false)
                    pending.addLast(key)
                }

                response != null && pending.isNotEmpty() -> {
                    val explicit = response.id?.takeIf { it.isNotBlank() }
                    // 有显式 id 就按 id 认，否则取最早还没结果的那个调用。
                    val match =
                        explicit?.let { want -> pending.firstOrNull { slots.getValue(it).id == want } } ?: pending.first()
                    pending.remove(match)
                    val slot = slots.getValue(match)
                    if (explicit != null) slot.id = explicit
                    slot.answered = true
                    callOfResponse[ci to pi] = match
                }

                else -> Unit
            }
        }
    }

    return buildList {
        this@toWireMessages.forEachIndexed { ci, content ->
            val text = content.text(separator = "", includeThoughts = false)
            val calls = content.parts.mapIndexedNotNull { pi, part -> part.functionCall?.let { (ci to pi) to it } }
            val responses = content.parts.mapIndexedNotNull { pi, part -> part.functionResponse?.let { (ci to pi) to it } }
            val answered = calls.filter { slots.getValue(it.first).answered }

            when {
                answered.isNotEmpty() ->
                    add(assistantMessage(text, answered.map { slots.getValue(it.first).id to it.second }))
                // 没有已配对的调用：正文按原角色发；纯工具 content 且没有结果可回灌时整条不发。
                text.isNotEmpty() -> add(JSONObject().put("role", content.toWireRole()).put("content", text))
            }

            responses.forEach { (key, response) ->
                val callKey = callOfResponse[key] ?: return@forEach
                add(
                    JSONObject()
                        .put("role", WIRE_ROLE_TOOL)
                        .put("tool_call_id", slots.getValue(callKey).id)
                        .put("name", response.name)
                        .put("content", response.response.toWireJsonString())
                )
            }
        }
    }
}

/** 协议上必填、但被 ADK 剥掉的 id：用「内容序号 + 部件序号」补一个在本次请求内唯一的值。 */
private fun synthesizedCallId(contentIndex: Int, partIndex: Int): String = "adk-h${contentIndex}p$partIndex"

/** 正文与工具调用共用一条 assistant 消息；没有正文时 content 用 null 而不是空串。 */
private fun assistantMessage(text: String, calls: List<Pair<String, FunctionCall>>): JSONObject =
    JSONObject()
        .put("role", WIRE_ROLE_ASSISTANT)
        .put("content", if (text.isEmpty()) JSONObject.NULL else text)
        .put(
            "tool_calls",
            JSONArray().apply {
                calls.forEach { (id, call) ->
                    put(
                        JSONObject()
                            .put("id", id)
                            .put("type", WIRE_TYPE_FUNCTION)
                            .put(
                                "function",
                                JSONObject()
                                    .put("name", call.name)
                                    // arguments 在协议上是「一段 JSON 文本」，不是对象。
                                    .put("arguments", call.args.toWireJsonString()),
                            )
                    )
                }
            },
        )

/**
 * [ParsedToolCall] -> ADK 的 [FunctionCall]。
 *
 * 这里给的 `adk-` 前缀 id 有两个用处：让 ADK 能把工具结果和这次调用配起来（`ToolContext.functionCallId`
 * -> `FunctionResponse.id`），以及让界面时间线用 id 定位工具卡片。注意它到不了下一轮请求 ——
 * ADK 交给模型的历史会把这种 id 置空，回灌时的配对见 [toWireMessages]。
 */
internal fun ParsedToolCall.toAdkFunctionCall(): FunctionCall =
    FunctionCall(
        name = name,
        args = arguments.toArgsMap(),
        id = id?.takeIf { it.startsWith(FunctionCall.ADK_FUNCTION_CALL_ID_PREFIX) }
            ?: FunctionCall.generateId(),
    )

/**
 * 把工具参数那段 JSON 文本还原成 ADK 需要的普通 Kotlin 结构（Map/List/String/Number/Boolean/null）。
 *
 * 解析失败时返回空 Map 而不是抛异常：ADK 会照常执行工具，工具自己的必填校验会给出模型读得懂的
 * 错误信息，比在这里炸掉整轮对话更好排查。
 */
internal fun String.toArgsMap(): Map<String, Any?> {
    if (isBlank()) return emptyMap()
    val json = runCatching { JSONObject(this) }.getOrNull() ?: return emptyMap()
    return json.toPlainMap()
}

/** `{"a":1}` 这样的对象 -> `mapOf("a" to 1)`，嵌套结构递归展开成 Map / List。 */
internal fun JSONObject.toPlainMap(): Map<String, Any?> =
    keys().asSequence().associateWith { opt(it).toPlainValue() }

/** JSONArray -> List，元素递归展开。 */
internal fun JSONArray.toPlainList(): List<Any?> = (0 until length()).map { opt(it).toPlainValue() }

private fun Any?.toPlainValue(): Any? =
    when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> toPlainMap()
        is JSONArray -> toPlainList()
        else -> this
    }

/** 普通 Kotlin 结构 -> 要写进 wire 的 JSON 值；不认识的类型退化成字符串，绝不抛。 */
internal fun Any?.toWireJsonValue(): Any? =
    when (this) {
        null -> JSONObject.NULL
        is JSONObject, is JSONArray -> this
        is Map<*, *> ->
            JSONObject().apply { forEach { (key, value) -> put(key.toString(), value.toWireJsonValue()) } }
        is List<*> -> JSONArray().apply { forEach { put(it.toWireJsonValue()) } }
        is Array<*> ->toList().toWireJsonValue()
        else -> this
    }

/** 工具参数 / 工具结果落成 JSON 文本，空集合给出 `{}` 而不是空串。 */
internal fun Any?.toWireJsonString(): String =
    when (val value = toWireJsonValue()) {
        is JSONObject -> value.toString()
        is JSONArray -> value.toString()
        else -> "{}"
    }
