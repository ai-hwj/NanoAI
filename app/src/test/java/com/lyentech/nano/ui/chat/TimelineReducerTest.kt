package com.lyentech.nano.ui.chat

import com.google.adk.kt.events.Event
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.FunctionCall
import com.google.adk.kt.types.FunctionResponse
import com.google.adk.kt.types.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件到时间线条目的翻译规则是纯逻辑，且一旦写错就会表现为「同一段话出现两遍」或「工具卡片
 * 找不到结果」这类只能靠肉眼发现的 bug，所以用宿主侧单测把契约钉死。
 */
class TimelineReducerTest {

    private val agent = "Assistant"

    private fun agentEvent(vararg parts: Part, partial: Boolean = false) =
        Event(author = agent, content = Content(role = "model", parts = parts.toList()), partial = partial)

    @Test
    fun streamingChunksAccumulateIntoOneMessage() {
        val reducer = TimelineReducer()
        reducer.accept(agentEvent(Part(text = "Hel"), partial = true))
        reducer.accept(agentEvent(Part(text = "lo"), partial = true))

        val items = reducer.snapshot()
        assertEquals(1, items.size)
        assertEquals("Hello", (items[0] as ChatTimelineItem.AgentMessage).text)
    }

    @Test
    fun finalAggregatedEventReplacesInsteadOfAppending() {
        // SSE 会先来增量、再来一份完整文本；后者必须覆盖，否则文字会重复。
        val reducer = TimelineReducer()
        reducer.accept(agentEvent(Part(text = "Hel"), partial = true))
        reducer.accept(agentEvent(Part(text = "Hello"), partial = false))

        val items = reducer.snapshot()
        assertEquals(1, items.size)
        assertEquals("Hello", (items[0] as ChatTimelineItem.AgentMessage).text)
    }

    @Test
    fun toolCallAndItsResponseShareOneCard() {
        val reducer = TimelineReducer()
        reducer.accept(agentEvent(Part(functionCall = FunctionCall(name = "get_battery", args = mapOf("x" to 1), id = "adk-1"))))
        reducer.accept(agentEvent(Part(functionResponse = FunctionResponse(name = "get_battery", response = mapOf("battery_percent" to 80), id = "adk-1"))))

        val items = reducer.snapshot().filterIsInstance<ChatTimelineItem.ToolCall>()
        assertEquals(1, items.size)
        assertEquals("get_battery", items[0].name)
        assertTrue(items[0].result!!.contains("battery_percent=80"))
    }

    @Test
    fun toolErrorIsCapturedNotShownAsResult() {
        val reducer = TimelineReducer()
        reducer.accept(agentEvent(Part(functionCall = FunctionCall(name = "t", id = "adk-2"))))
        reducer.accept(agentEvent(Part(functionResponse = FunctionResponse(name = "t", response = mapOf("error" to "boom"), id = "adk-2"))))

        val card = reducer.snapshot().filterIsInstance<ChatTimelineItem.ToolCall>().single()
        assertEquals("boom", card.error)
        assertEquals(null, card.result)
    }

    @Test
    fun textAfterToolCallBecomesItsOwnMessage() {
        val reducer = TimelineReducer()
        reducer.accept(agentEvent(Part(text = "先查一下")))
        reducer.accept(agentEvent(Part(functionCall = FunctionCall(name = "t", id = "adk-3"))))
        reducer.accept(agentEvent(Part(text = "查到了")))

        val texts = reducer.snapshot().filterIsInstance<ChatTimelineItem.AgentMessage>().map { it.text }
        assertEquals(listOf("先查一下", "查到了"), texts)
    }

    @Test
    fun reasoningPartsGoToTheirOwnItem() {
        val reducer = TimelineReducer()
        reducer.accept(agentEvent(Part(text = "想想…", thought = true), partial = true))
        reducer.accept(agentEvent(Part(text = "答案"), partial = false))

        val items = reducer.snapshot()
        assertEquals("想想…", (items[0] as ChatTimelineItem.Reasoning).text)
        assertEquals("答案", (items[1] as ChatTimelineItem.AgentMessage).text)
    }

    @Test
    fun replaySkipsPartialEventsAndKeepsOrder() {
        val events = listOf(
            Event(author = "user", content = Content(role = "user", parts = listOf(Part(text = "你好")))),
            agentEvent(Part(text = "你"), partial = true),
            agentEvent(Part(text = "你好，有什么可以帮你？")),
        )

        val reducer = TimelineReducer()
        reducer.replay(events, agent)

        val items = reducer.snapshot()
        assertEquals(2, items.size)
        assertEquals("你好", (items[0] as ChatTimelineItem.UserMessage).text)
        assertEquals("你好，有什么可以帮你？", (items[1] as ChatTimelineItem.AgentMessage).text)
    }

    @Test
    fun repeatedToolCallChunkWithSameIdDoesNotDuplicateTheCard() {
        // 流式工具调用的分片共用同一个 call id；条目 id 又是 LazyColumn 的 key，
        // 重复追加会直接崩，所以必须合并成一张卡片。
        val reducer = TimelineReducer()
        reducer.accept(agentEvent(Part(functionCall = FunctionCall(name = "t", args = mapOf("a" to 1), id = "adk-9"))))
        reducer.accept(agentEvent(Part(functionCall = FunctionCall(name = "t", args = mapOf("b" to 2), id = "adk-9"))))

        val cards = reducer.snapshot().filterIsInstance<ChatTimelineItem.ToolCall>()
        assertEquals(1, cards.size)
    }

    @Test
    fun everyItemIdIsUnique() {
        val reducer = TimelineReducer()
        reducer.accept(agentEvent(Part(text = "a")))
        reducer.accept(agentEvent(Part(functionCall = FunctionCall(name = "t", id = "adk-1"))))
        reducer.accept(agentEvent(Part(text = "b")))
        reducer.accept(agentEvent(Part(text = "c", thought = true)))
        reducer.addNotice("n")
        reducer.addUserMessage("u")

        val ids = reducer.snapshot().map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun errorMessageBecomesNotice() {
        val reducer = TimelineReducer()
        reducer.accept(Event(author = agent, errorMessage = "quota exceeded"))

        val notice = reducer.snapshot().filterIsInstance<ChatTimelineItem.Notice>().single()
        assertTrue(notice.text.contains("quota exceeded"))
    }
}
