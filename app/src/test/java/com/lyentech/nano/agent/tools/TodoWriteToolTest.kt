package com.lyentech.nano.agent.tools

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务清单是任务型 Agent 的骨架，出错方式都比较隐蔽：状态解析悄悄退化成 PENDING、
 * 清单被按会话串了、标题被意外清空。这些都用测试钉死。
 */
class TodoWriteToolTest {

    private val store = TodoStore()
    private val tool = TodoWriteTool(store)
    private val ctx = fakeToolContext()

    private fun item(content: String, status: String) = mapOf("content" to content, "status" to status)

    /** execute 的返回类型是 Any（ADK 允许返回任意结构），测试里统一按 Map 取用。 */
    @Suppress("UNCHECKED_CAST")
    private fun asMap(result: Any): Map<String, Any?> = result as Map<String, Any?>

    private fun resultText(result: Any): String =
        asMap(result)[BaseTool.RESULT_KEY] as String

    private fun errorOf(result: Any): Any? = asMap(result)[FunctionTool.ERROR_KEY]

    @Test
    fun writesAndStoresTheList() = runBlocking {
        val result = tool.execute(ctx, mapOf("todos" to listOf(item("写方案", "IN_PROGRESS"), item("跑测试", "PENDING"))))
        val text = resultText(result)

        assertTrue(text.contains("写方案"))
        assertTrue(text.contains("[~]"))
        assertTrue(text.contains("[ ]"))
        assertEquals(2, store.get(FAKE_SESSION_ID)!!.items.size)
    }

    @Test
    fun rendersCompletedWithCheckmark() = runBlocking {
        val result = tool.execute(ctx, mapOf("todos" to listOf(item("已完成的事", "COMPLETED"))))
        assertTrue(resultText(result).contains("[x]"))
    }

    @Test
    fun lowercaseStatusStillParses() = runBlocking {
        // 模型不总是严格遵守大小写；退化成 PENDING 会让进度显示错误。
        tool.execute(ctx, mapOf("todos" to listOf(item("甲", "in_progress"), item("乙", "completed"))))
        val list = store.get(FAKE_SESSION_ID)!!

        assertEquals(TodoStatus.IN_PROGRESS, list.items[0].status)
        assertEquals(TodoStatus.COMPLETED, list.items[1].status)
    }

    @Test
    fun unknownStatusFallsBackToPending() = runBlocking {
        tool.execute(ctx, mapOf("todos" to listOf(item("甲", "瞎写的状态"))))
        assertEquals(TodoStatus.PENDING, store.get(FAKE_SESSION_ID)!!.items[0].status)
    }

    @Test
    fun inProgressUsesActiveFormWhenPresent() = runBlocking {
        val result = tool.execute(
            ctx,
            mapOf(
                "todos" to listOf(
                    mapOf("content" to "运行测试", "status" to "IN_PROGRESS", "activeForm" to "正在运行测试")
                )
            ),
        )
        assertTrue(resultText(result).contains("正在运行测试"))
    }

    @Test
    fun emptyTodoListIsRejected() = runBlocking {
        val result = tool.execute(ctx, mapOf("todos" to emptyList<Any>()))
        // 空清单不能当成"全部完成"，否则模型可以靠清空列表假装做完。
        assertNotNull(errorOf(result))
    }

    @Test
    fun itemsWithoutContentAreDropped() = runBlocking {
        val result = tool.execute(ctx, mapOf("todos" to listOf(item("  ", "PENDING"), item("有用的事", "PENDING"))))
        val text = resultText(result)
        assertTrue(text.contains("有用的事"))
        assertEquals(1, store.get(FAKE_SESSION_ID)!!.items.size)
    }

    @Test
    fun titleIsPreservedWhenModelOmitsIt() = runBlocking {
        tool.execute(ctx, mapOf("title" to "修复登录", "todos" to listOf(item("甲", "PENDING"))))
        // 第二次漏传 title 时不该把已有标题清空。
        tool.execute(ctx, mapOf("todos" to listOf(item("甲", "COMPLETED"))))

        assertEquals("修复登录", store.get(FAKE_SESSION_ID)!!.title)
    }

    @Test
    fun allDoneIsReportedOnlyWhenNothingRemains() = runBlocking {
        var text = resultText(
            tool.execute(ctx, mapOf("todos" to listOf(item("甲", "COMPLETED"), item("乙", "PENDING")))),
        )
        assertTrue("还有待办时不该说全部完成", !text.contains("全部任务已完成"))

        text = resultText(
            tool.execute(ctx, mapOf("todos" to listOf(item("甲", "COMPLETED"), item("乙", "COMPLETED")))),
        )
        assertTrue(text.contains("全部任务已完成"))
    }

    @Test
    fun emptyListIsNotConsideredAllDone() {
        // 直接验证模型：空清单的 allDone 必须是 false。
        assertTrue(TodoList("t", emptyList()).allDone.not())
    }

    @Test
    fun progressCountsAreCorrect() = runBlocking {
        tool.execute(ctx, mapOf("todos" to listOf(item("甲", "PENDING"), item("乙", "IN_PROGRESS"), item("丙", "COMPLETED"))))
        val list = store.get(FAKE_SESSION_ID)!!

        assertEquals(1, list.pendingCount)
        assertEquals(1, list.inProgressCount)
        assertEquals(1, list.completedCount)
    }

    @Test
    fun clearResetsTheList() = runBlocking {
        tool.execute(ctx, mapOf("todos" to listOf(item("甲", "PENDING"))))
        store.clear(FAKE_SESSION_ID)
        assertNull(store.get(FAKE_SESSION_ID))
    }

    @Test
    fun listsAreIsolatedPerSession() = runBlocking {
        // 清单归属取自 ToolContext 的 session，而不是模型传的参数：模型不知道本机 session id。
        tool.execute(fakeToolContext("A"), mapOf("todos" to listOf(item("甲", "PENDING"))))
        tool.execute(fakeToolContext("B"), mapOf("todos" to listOf(item("乙", "COMPLETED"))))

        assertEquals(listOf("甲"), store.get("A")!!.items.map { it.content })
        assertEquals(listOf("乙"), store.get("B")!!.items.map { it.content })
    }

    @Test
    fun missingSessionIdFallsBackToTheDefaultBucket() = runBlocking {
        tool.execute(fakeToolContext(sessionId = null), mapOf("todos" to listOf(item("甲", "PENDING"))))
        assertEquals(1, store.get(TodoWriteTool.DEFAULT_SESSION)!!.items.size)
    }

    @Test
    fun declarationExposesRequiredStatusEnum() {
        val decl = tool.declaration()
        assertEquals(TodoWriteTool.NAME, decl.name)

        val todosSchema = decl.parameters!!.properties!!["todos"]!!
        val statusSchema = todosSchema.items!!.properties!!["status"]!!
        // 把合法状态写进 schema 的 enum，模型才不会自创状态值。
        assertEquals(listOf("PENDING", "IN_PROGRESS", "COMPLETED"), statusSchema.enum)
        assertTrue(todosSchema.items!!.required!!.contains("status"))
    }
}
