package com.lyentech.nano.agent.tools

import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * @author by jason-何伟杰，2026/10/10
 * des: 任务清单工具 —— 任务型 Agent 的规划骨架。
 *
 * 移植自 IDEA 插件的 TodoWriteTool，是那一批工具里**唯一可以近乎原样搬运**的：
 * 它不碰文件系统、不依赖 PSI，核心就是"维护一份带状态的清单 + 约束模型只用合法状态"。
 *
 * 与插件版的两处差异：
 *  - 插件把标题推给 IDE 的 tab 栏；Android 端改为对外暴露 [StateFlow]，由聊天页自己渲染，
 *    这样界面可以直接把清单画成任务卡（对齐 ChatGPT 的步骤显示）。
 *  - 清单按 sessionId 隔离并常驻内存：它是"这一步该干什么"的临时工作记忆，
 *    不是会话历史，不该落库，否则下次打开会看到上次残留的已完成清单。
 */

/** 任务状态。取值与插件保持一致，便于模型沿用既有习惯。 */
enum class TodoStatus(val label: String) {
    PENDING("待办"),
    IN_PROGRESS("进行中"),
    COMPLETED("已完成"),
}

/** 清单里的一条任务。[activeForm] 是进行中的进行时表述，用于界面显示"正在做…"。 */
data class TodoItem(
    val content: String,
    val status: TodoStatus,
    val activeForm: String? = null,
)

/** 一份任务清单。 */
data class TodoList(
    val title: String,
    val items: List<TodoItem>,
) {
    val pendingCount: Int get() = items.count { it.status == TodoStatus.PENDING }
    val inProgressCount: Int get() = items.count { it.status == TodoStatus.IN_PROGRESS }
    val completedCount: Int get() = items.count { it.status == TodoStatus.COMPLETED }

    /** 全部完成才算收尾；空清单不算完成，否则模型可以靠清空列表来"假装做完"。 */
    val allDone: Boolean get() = items.isNotEmpty() && pendingCount == 0 && inProgressCount == 0
}

/**
 * 清单仓库。按会话隔离，并对外暴露可观察状态供界面渲染。
 *
 * 用 [ConcurrentHashMap] 是因为工具在 Dispatchers.IO 上执行，而界面可能在主线程读。
 */
class TodoStore {

    private val lists = ConcurrentHashMap<String, MutableStateFlow<TodoList?>>()

    fun observe(sessionId: String): StateFlow<TodoList?> =
        flowFor(sessionId).asStateFlow()

    fun get(sessionId: String): TodoList? = flowFor(sessionId).value

    fun put(sessionId: String, list: TodoList) {
        // title 为空时沿用上一份标题，避免模型漏传标题把界面上的标题清掉。
        val previous = flowFor(sessionId).value
        val merged = if (list.title.isBlank() && previous != null) {
            list.copy(title = previous.title)
        } else {
            list
        }
        flowFor(sessionId).value = merged
    }

    fun clear(sessionId: String) {
        flowFor(sessionId).value = null
    }

    private fun flowFor(sessionId: String): MutableStateFlow<TodoList?> =
        lists.computeIfAbsent(sessionId) { MutableStateFlow(null) }
}

/**
 * 维护任务清单的工具。
 *
 * 描述文案大量沿用了插件版的"何时用 / 何时不用"，因为那是实测调出来的：
 * 模型很容易对一句话的简单请求也建清单，反而啰嗦，所以要显式写出跳过条件。
 */
class TodoWriteTool(private val store: TodoStore) : FunctionTool(
    name = NAME,
    description = DESCRIPTION,
) {

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
        // 会话 id 从 ToolContext 取，不让模型传：模型不知道本机 session id，声明里也本来没有这个
        // 参数，靠 args 兜底只会让所有会话共用同一份清单，A 会话的任务串到 B 会话界面上。
        val sessionId = context.session.key.id ?: DEFAULT_SESSION
        val rawItems = args["todos"] as? List<*> ?: emptyList<Any?>()

        val items = rawItems.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val content = (map["content"] as? String)?.trim().orEmpty()
            if (content.isEmpty()) return@mapNotNull null
            TodoItem(
                content = content,
                status = parseStatus(map["status"]),
                activeForm = (map["activeForm"] as? String)
                    ?: (map["active_form"] as? String),
            )
        }

        if (items.isEmpty()) {
            return mapOf(
                FunctionTool.ERROR_KEY to
                    "任务清单为空。请传入至少一条任务，每条包含 content 与 status。"
            )
        }

        val title = args.stringArg("title").orEmpty()
        val list = TodoList(title = title, items = items)
        store.put(sessionId, list)

        return mapOf(BaseTool.RESULT_KEY to render(list))
    }

    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = NAME,
        description = DESCRIPTION,
        parameters = Schema(
            type = Type.OBJECT,
            properties = mapOf(
                "title" to Schema(
                    type = Type.STRING,
                    description = "本次任务的简短标题（建议 4 个词以内，祈使句形式），可选。",
                ),
                "todos" to Schema(
                    type = Type.ARRAY,
                    description = "更新后的完整任务清单。是覆盖式提交：每次都要传全量列表，而不是增量。",
                    items = Schema(
                        type = Type.OBJECT,
                        properties = mapOf(
                            "content" to Schema(
                                type = Type.STRING,
                                description = "任务内容，祈使句形式，例如「运行测试」。",
                            ),
                            "status" to Schema(
                                type = Type.STRING,
                                description = "必须是 PENDING、IN_PROGRESS、COMPLETED 三者之一。",
                                enum = listOf("PENDING", "IN_PROGRESS", "COMPLETED"),
                            ),
                            "activeForm" to Schema(
                                type = Type.STRING,
                                description = "进行时表述，例如「正在运行测试」，可选。",
                            ),
                        ),
                        required = listOf("content", "status"),
                    ),
                ),
            ),
            required = listOf("todos"),
        ),
    )

    private fun parseStatus(value: Any?): TodoStatus = when (val text = value?.toString()?.trim()?.uppercase()) {
        "IN_PROGRESS", "INPROGRESS", "进行中" -> TodoStatus.IN_PROGRESS
        "COMPLETED", "DONE", "已完成" -> TodoStatus.COMPLETED
        else -> TodoStatus.PENDING
    }

    /** 回给模型的是纯文本清单：比 JSON 更省 token，也更容易被下一轮正确复述。 */
    private fun render(list: TodoList): String = buildString {
        appendLine("任务清单已更新")
        appendLine()
        appendLine("进度：待办 ${list.pendingCount} / 进行中 ${list.inProgressCount} / 已完成 ${list.completedCount}")
        appendLine()
        list.items.forEachIndexed { index, item ->
            val mark = when (item.status) {
                TodoStatus.PENDING -> "[ ]"
                TodoStatus.IN_PROGRESS -> "[~]"
                TodoStatus.COMPLETED -> "[x]"
            }
            // 进行中的条目优先显示进行时表述，界面上就是"正在做…"。
            val text = if (item.status == TodoStatus.IN_PROGRESS && !item.activeForm.isNullOrBlank()) {
                item.activeForm
            } else {
                item.content
            }
            appendLine("${index + 1}. $mark $text")
        }
        if (list.allDone) {
            appendLine()
            append("全部任务已完成。")
        }
    }.trimEnd()

    companion object {
        const val NAME = "todo_write"

        /** 工具拿不到 ADK 的 session 上下文时用它兜底，保证清单至少有一个稳定归属。 */
        const val DEFAULT_SESSION = "default"

        private val DESCRIPTION = """
            维护当前任务的待办清单，用于跟踪进度、组织多步任务，并把计划展示给用户。

            什么时候该用：
            - 任务需要 3 个以上明确步骤
            - 任务复杂、需要先规划再动手
            - 用户明确要求使用清单
            - 用户一次性给了多条待办（编号列表或逗号分隔）
            - 收到新指令后，先把要求记成待办
            - 开始做某项时，先把它标记为 IN_PROGRESS（同一时间最好只有一项进行中）
            - 完成一项后标记为 COMPLETED，并把过程中发现的新任务补进去

            什么时候不该用：
            - 只有一件简单的事，或几步就能做完
            - 纯对话、纯问答，没有需要跟踪的动作
            - 给单件小事建清单只会显得啰嗦，直接做即可

            提交方式：这是覆盖式提交，每次都要传完整的 todos 数组（包含已完成项），
            而不是只传变化的那几条，否则清单会残缺。
        """.trimIndent()
    }
}

/** 从模型给的参数里取字符串；缺参或类型不符时返回 null。 */
internal fun Map<String, Any?>.stringArg(key: String): String? =
    (this[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }

internal fun Map<String, Any?>.intArg(key: String): Int? = when (val value = this[key]) {
    is Int -> value
    is Number -> value.toInt()
    is String -> value.toIntOrNull()
    else -> null
}

internal fun Map<String, Any?>.boolArg(key: String): Boolean = when (val value = this[key]) {
    is Boolean -> value
    is String -> value.equals("true", ignoreCase = true)
    else -> false
}
