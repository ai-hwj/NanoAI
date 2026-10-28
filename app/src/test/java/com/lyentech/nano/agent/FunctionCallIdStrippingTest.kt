package com.lyentech.nano.agent

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.lyentech.nano.agent.tools.TodoStore
import com.lyentech.nano.agent.tools.TodoWriteTool
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记录 ADK-Kotlin 1.3.1 在 functionCall / functionResponse 的 id 上到底做了什么。
 *
 * 结论（上游 `HistoryRewriterProcessor.stripClientFunctionCallIds`，私有且没有开关）：
 * - 事件里 id 是完整的：模型给的 id 会被带进工具结果，模型没给时 ADK 补一个 `adk-` 前缀的。
 * - 但历史交给模型之前，所有 `adk-` 前缀的 id 会被**无条件置空**。
 *
 * 也就是说任何模型适配层都不能指望从请求历史里读到 id。Gree 那一侧因此在本条请求内按出现顺序
 * 重建配对，见 `http/gree/GreeToolDto.kt` 的 `List<Content>.toWireMessages`。
 * 上游哪天不再剥离，这里的第二个测试会失败，届时那处配对逻辑可以简化回按 id 匹配。
 */
class FunctionCallIdStrippingTest {

    /** 第一轮返回一个工具调用，第二轮返回收尾正文；每轮收到的历史都留一份。 */
    private inner class RecordingModel(private val callId: String?) : Model {
        override val name = "recorder"
        val turns = mutableListOf<List<Content>>()
        private var count = 0

        override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow {
            turns += request.contents
            count += 1
            val content =
                if (count == 1) {
                    Content(
                        role = Role.MODEL,
                        parts =
                            listOf(
                                Part(
                                    functionCall =
                                        FunctionCall(
                                            name = TodoWriteTool.NAME,
                                            args = mapOf("todos" to listOf(mapOf("content" to "甲", "status" to "PENDING"))),
                                            id = callId,
                                        )
                                )
                            ),
                    )
                } else {
                    Content(role = Role.MODEL, parts = listOf(Part(text = "done")))
                }
            emit(LlmResponse(content = content, partial = false))
        }
    }

    private fun runLoop(model: RecordingModel) = runBlocking {
        val agent =
            LlmAgent(
                name = "Recorder",
                model = model,
                tools = listOf(TodoWriteTool(TodoStore())),
                instruction = Instruction("recorder"),
            )
        val runner = InMemoryRunner(agent = agent, appName = "recorderApp", sessionService = InMemorySessionService())
        try {
            runner
                .runAsync(
                    userId = "u",
                    sessionId = "s",
                    newMessage = Content(role = Role.USER, parts = listOf(Part(text = "go"))),
                    runConfig = RunConfig(streamingMode = StreamingMode.NONE, maxLlmCalls = 4),
                )
                .toList()
        } finally {
            runner.close()
        }
    }

    private fun List<Content>.calls(): List<FunctionCall> = flatMap { it.parts }.mapNotNull { it.functionCall }

    private fun List<Content>.responses(): List<FunctionResponse> = flatMap { it.parts }.mapNotNull { it.functionResponse }

    @Test
    fun theIdOnTheEventSideSurvivesTheToolRoundTrip() {
        val model = RecordingModel("adk-keep-id")
        val events = runLoop(model)

        // 事件（也是 session 历史）里，工具结果拿到的就是模型那次调用的 id。
        val responseId = events.flatMap { it.content?.parts.orEmpty() }.firstNotNullOf { it.functionResponse }.id
        assertEquals("adk-keep-id", responseId)
    }

    @Test
    fun adkFillsAnAdkPrefixedIdWhenTheModelOmitsIt() {
        val events = runLoop(RecordingModel(null))

        val id = events.flatMap { it.content?.parts.orEmpty() }.firstNotNullOf { it.functionResponse }.id
        assertTrue("expected an adk- prefixed id, got $id", id?.startsWith(FunctionCall.ADK_FUNCTION_CALL_ID_PREFIX) == true)
    }

    @Test
    fun adkStripsClientIdsFromTheHistoryItHandsToTheModel() {
        val model = RecordingModel(null)
        runLoop(model)

        assertEquals(2, model.turns.size)
        val historySeenByTheSecondTurn = model.turns[1]
        val calls = historySeenByTheSecondTurn.calls()
        val responses = historySeenByTheSecondTurn.responses()

        // 历史确实带着调用与结果回来了 —— 配对信息还在，只是 id 被剥掉了。
        assertEquals(1, calls.size)
        assertEquals(1, responses.size)
        assertNull(calls[0].id)
        assertNull(responses[0].id)
    }
}
