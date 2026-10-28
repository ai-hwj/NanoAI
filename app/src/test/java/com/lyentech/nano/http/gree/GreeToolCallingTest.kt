package com.lyentech.nano.http.gree

import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Tool
import com.lyentech.nano.agent.tools.TodoStore
import com.lyentech.nano.agent.tools.TodoWriteTool
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用的双向映射，全部跑硬编码 fixture：不碰网络、不碰端点，所以失败一定能在本机解释。
 *
 * 端点是否真的按 OpenAI 形状返回 tool_calls，由 [GreeToolLoopLiveTest] 那条要显式开启的活体测试负责。
 */
class GreeToolCallingTest {

    // ---- 请求侧：tools 声明 ----

    @Test
    fun adkDeclarationsBecomeOpenAiFunctionTools() {
        val declaration = TodoWriteTool(TodoStore()).declaration()
        val request =
            LlmRequest(
                contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "列个计划")))),
                config = GenerateContentConfig(tools = listOf(Tool(functionDeclarations = listOf(declaration)))),
            )

        val body = request.toChatRequestJson(stream = false)
        val tools = body.getJSONArray("tools")

        assertEquals(1, tools.length())
        val tool = tools.getJSONObject(0)
        assertEquals("function", tool.getString("type"))
        val fn = tool.getJSONObject("function")
        assertEquals(TodoWriteTool.NAME, fn.getString("name"))
        assertTrue(fn.getString("description").contains("任务"))

        val parameters = fn.getJSONObject("parameters")
        assertEquals("object", parameters.getString("type"))
        assertEquals("todos", parameters.getJSONArray("required").getString(0))

        // 嵌套结构要一路展开：数组的 items 是对象，对象里的 status 带着 enum。
        val item = parameters.getJSONObject("properties").getJSONObject("todos").getJSONObject("items")
        assertEquals("object", item.getString("type"))
        assertEquals("content", item.getJSONArray("required").getString(0))
        val status = item.getJSONObject("properties").getJSONObject("status")
        val allowed = status.getJSONArray("enum")
        assertEquals(
            listOf("PENDING", "IN_PROGRESS", "COMPLETED"),
            (0 until allowed.length()).map { allowed.getString(it) },
        )
        // "auto" 是 OpenAI 的默认值，写出来是为了不依赖各家兼容端点各自的缺省。
        assertEquals("auto", body.getString("tool_choice"))
    }

    @Test
    fun aRequestWithoutToolsCarriesNeitherToolsNorToolChoice() {
        val request =
            LlmRequest(contents = listOf(Content(role = Role.USER, parts = listOf(Part(text = "hi")))))

        val body = request.toChatRequestJson(stream = false)

        assertFalse(body.has("tools"))
        assertFalse(body.has("tool_choice"))
    }

    @Test
    fun aToolWithoutParametersStillDeclaresAnObjectSchema() {
        val request =
            LlmRequest(
                config =
                    GenerateContentConfig(
                        tools =
                            listOf(
                                Tool(
                                    functionDeclarations =
                                        listOf(
                                            FunctionDeclaration(
                                                name = "get_battery_level",
                                                description = "当前电量百分比。",
                                            )
                                        )
                                )
                            )
                    )
            )

        val parameters =
            request.toChatRequestJson(stream = false)
                .getJSONArray("tools")
                .getJSONObject(0)
                .getJSONObject("function")
                .getJSONObject("parameters")

        assertEquals("object", parameters.getString("type"))
        assertEquals(0, parameters.getJSONObject("properties").length())
    }

    // ---- 请求侧：历史回灌 ----

    private val callContent =
        Content(
            role = Role.MODEL,
            parts =
                listOf(
                    Part(text = "我查一下"),
                    Part(
                        functionCall =
                            FunctionCall(
                                name = "get_battery_level",
                                args = mapOf("unit" to "percent", "limit" to 3),
                                id = "adk-1",
                            )
                    ),
                ),
        )

    private val responseContent =
        Content(
            role = Role.USER,
            parts =
                listOf(
                    Part(
                        functionResponse =
                            FunctionResponse(
                                name = "get_battery_level",
                                response = mapOf("result" to "87"),
                                id = "adk-1",
                            )
                    )
                ),
        )

    @Test
    fun functionCallHistoryBecomesAssistantToolCallsFollowedByToolResult() {
        val request =
            LlmRequest(
                contents =
                    listOf(
                        Content(role = Role.USER, parts = listOf(Part(text = "还剩多少电"))),
                        callContent,
                        responseContent,
                    )
            )

        val messages = request.toChatRequestJson(stream = false).getJSONArray("messages")

        assertEquals(3, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))

        val assistant = messages.getJSONObject(1)
        assertEquals("assistant", assistant.getString("role"))
        // 正文和调用共用一条 assistant 消息。
        assertEquals("我查一下", assistant.getString("content"))
        val call = assistant.getJSONArray("tool_calls").getJSONObject(0)
        assertEquals("adk-1", call.getString("id"))
        assertEquals("function", call.getString("type"))
        assertEquals("get_battery_level", call.getJSONObject("function").getString("name"))
        // arguments 是一段 JSON 文本，不是对象。
        val arguments = JSONObject(call.getJSONObject("function").getString("arguments"))
        assertEquals("percent", arguments.getString("unit"))
        assertEquals(3, (arguments.get("limit") as Number).toInt())

        val tool = messages.getJSONObject(2)
        assertEquals("tool", tool.getString("role"))
        assertEquals("adk-1", tool.getString("tool_call_id"))
        assertEquals("get_battery_level", tool.getString("name"))
        assertEquals("87", JSONObject(tool.getString("content")).getString("result"))
    }

    @Test
    fun assistantContentIsNullWhenTheModelOnlyCallsTools() {
        val callsOnly = callContent.copy(parts = callContent.parts.filter { it.functionCall != null })
        val request = LlmRequest(contents = listOf(callsOnly, responseContent))

        val assistant = request.toChatRequestJson(stream = false).getJSONArray("messages").getJSONObject(0)

        assertTrue(assistant.isNull("content"))
        assertEquals(1, assistant.getJSONArray("tool_calls").length())
    }

    @Test
    fun aToolCallWithoutItsResultIsNotSentBack() {
        // 协议要求每个 tool_calls[].id 后面必须紧跟同 id 的 tool 消息，缺一条端点就判 400，
        // 所以还没结果的调用只能整条丢掉，正文留着。
        val request = LlmRequest(contents = listOf(callContent))

        val messages = request.toChatRequestJson(stream = false).getJSONArray("messages")

        assertEquals(1, messages.length())
        assertEquals("我查一下", messages.getJSONObject(0).getString("content"))
        assertFalse(messages.getJSONObject(0).has("tool_calls"))
    }

    @Test
    fun idsStrippedByAdkAreRebuiltByPosition() {
        // ADK-Kotlin 1.3.1 的 HistoryRewriterProcessor 会无条件把 adk- 前缀的 id 置空后才交给我们，
        // 所以配对不能依赖 id 本身：调用先进队、结果出队，两侧必须拿到同一个非空 id。
        val request = LlmRequest(contents = listOf(stripIds(callContent), stripIds(responseContent)))

        val messages = request.toChatRequestJson(stream = false).getJSONArray("messages")
        val assistant = messages.getJSONObject(0)
        val tool = messages.getJSONObject(1)

        val callId = assistant.getJSONArray("tool_calls").getJSONObject(0).getString("id")
        assertTrue(callId.isNotBlank())
        assertEquals(callId, tool.getString("tool_call_id"))
        assertEquals("get_battery_level", tool.getString("name"))
    }

    @Test
    fun severalCallsInOneTurnArePairedInOrder() {
        val calls =
            Content(
                role = Role.MODEL,
                parts =
                    listOf(
                        Part(functionCall = FunctionCall(name = "a", args = mapOf("q" to 1))),
                        Part(functionCall = FunctionCall(name = "b", args = mapOf("q" to 2))),
                    ),
            )
        val results =
            Content(
                role = Role.USER,
                parts =
                    listOf(
                        Part(functionResponse = FunctionResponse(name = "a", response = mapOf("result" to "1"))),
                        Part(functionResponse = FunctionResponse(name = "b", response = mapOf("result" to "2"))),
                    ),
            )

        val messages = LlmRequest(contents = listOf(calls, results)).toChatRequestJson(stream = false).getJSONArray("messages")

        val toolCalls = messages.getJSONObject(0).getJSONArray("tool_calls")
        val firstId = toolCalls.getJSONObject(0).getString("id")
        val secondId = toolCalls.getJSONObject(1).getString("id")
        assertTrue(firstId != secondId)

        // 第一个结果配第一个调用，第二个配第二个：顺序错位会让模型把答案接到错误的工具上。
        assertEquals(firstId, messages.getJSONObject(1).getString("tool_call_id"))
        assertEquals("a", messages.getJSONObject(1).getString("name"))
        assertEquals(secondId, messages.getJSONObject(2).getString("tool_call_id"))
        assertEquals("b", messages.getJSONObject(2).getString("name"))
    }

    @Test
    fun anExplicitResponseIdIsWrittenOnBothSides() {
        // 调用侧的 id 被剥掉了、结果侧还留着时，以结果侧那个为准：两侧写成同一个值才是合法请求。
        val call = Content(role = Role.MODEL, parts = listOf(Part(functionCall = FunctionCall(name = "a", args = mapOf()))))
        val result =
            Content(
                role = Role.USER,
                parts = listOf(Part(functionResponse = FunctionResponse(name = "a", response = mapOf(), id = "call-9"))),
            )

        val messages = LlmRequest(contents = listOf(call, result)).toChatRequestJson(stream = false).getJSONArray("messages")

        assertEquals("call-9", messages.getJSONObject(0).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals("call-9", messages.getJSONObject(1).getString("tool_call_id"))
    }

    @Test
    fun aResultWithoutItsCallIsDropped() {
        // 反过来孤儿结果也不能发：一条没有 tool_calls 可配的 role=tool 消息同样让端点判 400。
        val request = LlmRequest(contents = listOf(responseContent))

        assertEquals(0, request.toChatRequestJson(stream = false).getJSONArray("messages").length())
    }

    /** 模拟 ADK 交给模型前的那一步：把历史里的客户端 id 全部置空。 */
    private fun stripIds(content: Content): Content =
        content.copy(
            parts =
                content.parts.map { part ->
                    val call = part.functionCall
                    val response = part.functionResponse
                    when {
                        call != null -> part.copy(functionCall = call.copy(id = null))
                        response != null -> part.copy(functionResponse = response.copy(id = null))
                        else -> part
                    }
                },
        )

    // ---- 响应侧：流式分片 ----

    @Test
    fun aToolCallOnlyChunkIsAUsableDelta() {
        val delta =
            parseStreamChunk(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1",""" +
                    """"function":{"name":"web_search","arguments":""}}]}}]}"""
            )

        assertNull(delta?.text)
        assertEquals(1, delta?.toolCalls?.size)
        assertEquals("web_search", delta!!.toolCalls[0].name)
    }

    @Test
    fun fragmentsAreMergedByIndexAndArgumentsAreConcatenated() {
        val accumulator = ToolCallAccumulator()
        // 一次调用：name 在首片，arguments 被切成两片。
        accumulator.feed(fragments("""{"index":0,"id":"call_a","function":{"name":"todo_write","arguments":"{\"ti"}}"""))
        accumulator.feed(fragments("""{"index":0,"function":{"arguments":"tle\":\"x\"}"}}"""))
        // 另一次调用（index 1）：name 重复下发，也不该拼成 web_fetchweb_fetch。
        accumulator.feed(fragments("""{"index":1,"id":"call_b","function":{"name":"web_fetch","arguments":"{\"url"}}"""))
        accumulator.feed(fragments("""{"index":1,"function":{"name":"web_fetch","arguments":"\":\"u\"}"}}"""))

        val calls = accumulator.calls()

        assertEquals(2, calls.size)
        assertEquals("call_a", calls[0].id)
        assertEquals("todo_write", calls[0].name)
        assertEquals("""{"title":"x"}""", calls[0].arguments)
        assertEquals("web_fetch", calls[1].name)
        assertEquals("""{"url":"u"}""", calls[1].arguments)
    }

    /** 把一条 `delta.tool_calls` 里的单个元素解析成分片。 */
    private fun fragments(callItem: String): List<ToolCallFragment> =
        parseStreamChunk("""{"choices":[{"delta":{"tool_calls":[$callItem]}}]}""")!!.toolCalls

    @Test
    fun textAndToolCallFragmentsBothSurviveOneStream() {
        val fixture =
            "data: {\"choices\":[{\"delta\":{\"content\":\"等一下\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"c\"," +
                "\"function\":{\"name\":\"f\",\"arguments\":\"{}\"}}]}}]}\n\n" +
                "data: [DONE]\n"

        val deltas = parseStreamDeltas(fixture)

        assertEquals(listOf("等一下", null), deltas.map { it.text })
        assertEquals(listOf("f"), deltas.flatMap { it.toolCalls }.mapNotNull { it.name })
    }

    // ---- 响应侧：非流式 ----

    @Test
    fun aToolCallCompletionWithoutAnyTextIsStillAResult() {
        val completion =
            parseCompletion(
                """
                {"choices":[{"message":{"role":"assistant","content":null,
                "tool_calls":[{"id":"call_1","type":"function","function":{"name":"web_search","arguments":"{\"query\":\"a\"}"}}]},
                "finish_reason":"tool_calls"}]}
                """
                    .trimIndent()
            )

        assertEquals("", completion?.text)
        assertEquals("tool_calls", completion?.finishReason)
        assertEquals(1, completion?.toolCalls?.size)
        assertEquals("call_1", completion!!.toolCalls[0].id)
        assertEquals("""{"query":"a"}""", completion.toolCalls[0].arguments)
    }

    @Test
    fun textAndToolCallsCanBeReturnedTogether() {
        val completion =
            parseCompletion(
                """
                {"choices":[{"message":{"content":"我查一下",
                "tool_calls":[{"id":"c1","function":{"name":"a","arguments":"{}"}},
                {"id":"c2","function":{"name":"b","arguments":"{}"}}]}}]}
                """
                    .trimIndent()
            )

        assertEquals("我查一下", completion?.text)
        assertEquals(listOf("a", "b"), completion!!.toolCalls.map { it.name })
    }

    // ---- 响应侧：翻成 ADK 的 FunctionCall ----

    @Test
    fun openAiCallIdIsReplacedByAnAdkPrefixedId() {
        val fc =
            ParsedToolCall(
                id = "call_abc",
                name = "web_search",
                arguments = """{"query":"a","n":2,"deep":{"k":[1,"x",null]}}""",
            )
                .toAdkFunctionCall()

        // ADK 侧约定客户端 id 带 adk- 前缀，下一轮回灌时 tool_call_id 用的就是这个值，配对靠它。
        assertTrue(fc.id!!.startsWith(FunctionCall.ADK_FUNCTION_CALL_ID_PREFIX))
        assertEquals("web_search", fc.name)
        assertEquals("a", fc.args["query"])
        assertEquals(2, (fc.args["n"] as Number).toInt())
        // 嵌套结构必须是普通 Kotlin 类型：ADK 落库时按 JSON-native 校验，塞 POJO 会直接抛。
        val deep = fc.args["deep"] as Map<*, *>
        assertEquals(
            listOf<Any?>(1, "x", null),
            (deep["k"] as List<*>).map { (it as? Number)?.toInt() ?: it },
        )
    }

    @Test
    fun anAlreadyAdkPrefixedIdIsKeptAsIs() {
        val fc = ParsedToolCall(id = "adk-keep", name = "f", arguments = "{}").toAdkFunctionCall()

        assertEquals("adk-keep", fc.id)
    }

    @Test
    fun missingOrMalformedArgumentsDegradeToAnEmptyMap() {
        // 参数残缺时不抛异常：工具自己的必填校验会返回模型读得懂的错误，比炸掉整轮更好排查。
        assertTrue(ParsedToolCall(null, "f", "").toAdkFunctionCall().args.isEmpty())
        assertTrue(ParsedToolCall(null, "f", "not json").toAdkFunctionCall().args.isEmpty())
        assertTrue(ParsedToolCall(null, "f", "{\"a\":}").toAdkFunctionCall().args.isEmpty())
    }

    @Test
    fun toolResultsAreSerializedBackToJsonText() {
        val call =
            Content(
                role = Role.MODEL,
                parts = listOf(Part(functionCall = FunctionCall(name = "f", args = mapOf("k" to 1), id = "adk-9"))),
            )
        val response =
            Content(
                role = Role.USER,
                parts =
                    listOf(
                        Part(
                            functionResponse =
                                FunctionResponse(
                                    name = "f",
                                    response = mapOf("result" to listOf("a", 1), "error" to null),
                                    id = "adk-9",
                                )
                        )
                    ),
            )

        val message =
            LlmRequest(contents = listOf(call, response))
                .toChatRequestJson(stream = false)
                .getJSONArray("messages")
                .getJSONObject(1)

        assertEquals("adk-9", message.getString("tool_call_id"))
        val payload = JSONObject(message.getString("content"))
        assertEquals("a", payload.getJSONArray("result").getString(0))
        assertTrue(payload.isNull("error"))
    }
}
