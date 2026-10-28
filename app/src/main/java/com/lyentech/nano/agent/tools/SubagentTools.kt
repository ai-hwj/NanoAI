package com.lyentech.nano.agent.tools

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.Model
import com.google.adk.kt.tools.AgentTool
import com.google.adk.kt.tools.BaseTool
import com.google.adk.kt.tools.FunctionTool
import com.google.adk.kt.tools.ToolContext
import com.google.adk.kt.types.FunctionDeclaration
import com.google.adk.kt.types.Schema
import com.google.adk.kt.types.Type

/**
 * @author by jason-何伟杰，2026/10/10
 * des: 子 Agent 编排 —— 插件 TaskTool 在 Android 上的等价实现。
 *
 * ## 先说清楚 TaskTool 到底做了什么
 *
 * 它不是"创建一个待办事项"，而是**递归启动一个子 Agent**：
 * 主 Agent 调 `Task(subagent_type, prompt)` -> 起一个独立的 Agent（可带自己的工具集与指令）
 * -> 子 Agent 在自己的上下文里跑完 -> 只把最终结论作为工具结果返回主 Agent。
 *
 * 价值在于**上下文隔离**：让子 Agent 去"搜 20 个网页、读 5 份文档"，主 Agent 的上下文里
 * 最终只多出一条结论，而不是 20 段网页正文。这就是它被归到"任务编排"的原因。
 *
 * ## 实现选择：站在 ADK 的 AgentTool 上
 *
 * ADK-Kotlin 已经内置 `AgentTool`（把 [BaseAgent] 包成工具，内部用隔离 session 运行、
 * 只回传最终文本）。所以这里**不重写运行逻辑**，只补 TaskTool 里 ADK 没有的那部分：
 * 让模型通过 `subagent_type` 选择跑哪个子 Agent。
 *
 * 用一个"调度器"工具而非把每个子 Agent 都直接挂上，是因为：
 *  - 直接挂 N 个 [AgentTool] 会让工具列表随子 Agent 数量膨胀，实测模型容易选错；
 *  - 一个带 `enum` 约束的 `subagent_type` 参数，等于把可选值写进 schema，模型不会瞎编类型。
 *
 * ## 与插件版的能力差异（有意为之）
 *
 * 插件版还做了「子 Agent 每次工具调用桥接回 IDE UI」「累计子 Agent token 消耗」。
 * 前者在端上对应聊天页的 timeline，等子 Agent 真接进主流程时再接
 * （[com.lyentech.nano.ui.chat.ChatTimelineItem.ToolCall] 的渲染已经就绪）；
 * 后者需要 usage metadata，当前 Gree 端点未返回，暂不做。
 */

/** 一个可被调度的子 Agent 定义。 */
data class SubagentSpec(
    /** 模型在 `subagent_type` 里要填的标识，必须简短稳定、全小写下划线。 */
    val type: String,
    /** 出现在工具 schema 描述里的用途说明，模型靠它选类型。 */
    val description: String,
    /** 子 Agent 的系统指令，决定它的行为边界。 */
    val instruction: String,
    /** 子 Agent 可用的工具。默认给空集，即"只用模型自身知识作答"。 */
    val tools: List<BaseTool> = emptyList(),
)

/**
 * 把一组子 Agent 定义变成主 Agent 可调用的 `task` 工具。
 *
 * @param specs 可调度的子 Agent。
 * @param model 子 Agent 使用的模型。没有单独的模型路由时，传主 Agent 同一个即可。
 * @param maxSteps 子 Agent 的最大步数：防止它在工具循环里打转。
 */
class TaskTool(
    private val specs: List<SubagentSpec>,
    private val model: () -> Model,
    private val maxSteps: Int = DEFAULT_MAX_STEPS,
) : FunctionTool(
    name = NAME,
    description = buildDescription(specs, maxSteps),
) {

    /**
     * 每次调用都新建一个 [AgentTool]。
     *
     * 不复用是因为 [AgentTool] 内部持有一个 `InMemoryRunner`，而 runner 会随 agent 生命周期
     * 绑定工具资源；每次现建现用可以避免多个子 Agent 共享同一个 runner 实例导致的状态串扰。
     */
    private fun agentToolFor(spec: SubagentSpec): AgentTool =
        AgentTool(
            agent = LlmAgent(
                name = spec.type,
                description = spec.description,
                model = model(),
                instruction = Instruction(spec.instruction),
                tools = spec.tools,
                maxSteps = maxSteps,
            ),
        )

    override suspend fun execute(context: ToolContext, args: Map<String, Any?>): Any {
        val type = args.stringArg("subagent_type")
            ?: return err("缺少 subagent_type 参数")
        val prompt = args.stringArg("prompt")
            ?: return err("缺少 prompt 参数")

        val spec = specs.firstOrNull { it.type.equals(type, ignoreCase = true) }
            ?: return err(
                "未知的子 Agent 类型 '$type'。可用类型：${specs.joinToString(", ") { it.type }}"
            )

        // 具体执行完全交给 ADK 的 AgentTool：它负责隔离 session、跑完、只回传最终文本。
        val output = runCatching { agentToolFor(spec).run(context, mapOf("request" to prompt)) }
            .getOrElse { error ->
                return err(
                    "子 Agent '${spec.type}' 执行失败：${error.message ?: error::class.simpleName}"
                )
            }

        if (output.isBlank()) {
            return err(
                "子 Agent '${spec.type}' 没有返回任何内容。可重试一次；若持续如此，" +
                    "可能是模型端返回了空响应。"
            )
        }

        val label = args.stringArg("description").orEmpty()
        val header = buildString {
            append("子 Agent：${spec.type}")
            if (label.isNotEmpty()) append("（$label）")
        }
        return mapOf(BaseTool.RESULT_KEY to "$header\n\n${output.trim()}")
    }

    override fun declaration(): FunctionDeclaration = FunctionDeclaration(
        name = NAME,
        description = description,
        parameters = Schema(
            type = Type.OBJECT,
            properties = mapOf(
                "description" to Schema(
                    type = Type.STRING,
                    description = "这次子任务的简短标签（3-5 个词），用于在结果里区分多次调用。",
                ),
                "prompt" to Schema(
                    type = Type.STRING,
                    description = "交给子 Agent 的完整指令。子 Agent 看不到当前对话，" +
                        "所以必须自带背景、目标、期望输出与约束。",
                ),
                "subagent_type" to Schema(
                    type = Type.STRING,
                    description = "要使用的子 Agent 类型，必须严格是下列之一：" +
                        specs.joinToString(", ") { "${it.type}（${it.description}）" },
                    enum = specs.map { it.type },
                ),
            ),
            required = listOf("prompt", "subagent_type"),
        ),
    )

    private fun err(message: String) = mapOf(FunctionTool.ERROR_KEY to message)

    companion object {
        const val NAME = "task"
        private const val DEFAULT_MAX_STEPS = 12

        private fun buildDescription(specs: List<SubagentSpec>, maxSteps: Int) = buildString {
            appendLine("启动一个子 Agent 去独立完成一个多步任务，完成后只把结论返回。")
            appendLine()
            appendLine("可用的子 Agent：")
            specs.forEach { appendLine("- ${it.type}：${it.description}") }
            appendLine()
            appendLine("什么时候用：")
            appendLine("- 任务需要独立探索、会读入大量内容（例如翻很多网页、读很多文件），")
            appendLine("  而你不想让这些中间内容占满当前上下文")
            appendLine("- 有几个互不依赖的子任务，可以一次发出多个调用并行执行")
            appendLine()
            appendLine("什么时候不用：")
            appendLine("- 一两步就能直接做完的事")
            appendLine("- 需要保持在当前对话上下文里连续推理的事（子 Agent 看不到当前对话）")
            appendLine()
            appendLine("注意：")
            appendLine("- 子 Agent 的 prompt 必须自包含；它拿不到当前对话历史")
            appendLine("- 子 Agent 最多执行 $maxSteps 步，请把范围描述清楚，避免它漫无目的")
        }.trimEnd()
    }
}

/** 提供本应用默认子 Agent 集合的工厂。 */
object DefaultSubagents {

    /**
     * 默认只给一个 `general-purpose`。
     *
     * 不预先堆很多类型：多类型只在各有明确工具边界时才有价值，
     * 而端上目前可用工具还少，多个类型实际上是同一个 Agent 换了个名字，反而干扰模型选择。
     */
    fun generalPurpose(): SubagentSpec = SubagentSpec(
        type = "general-purpose",
        description = "通用子 Agent，用于独立研究、多步推理与汇总",
        instruction = """
            你是一个独立的子 Agent。你看不到主对话的历史，只依据收到的指令工作。

            要求：
            - 只在指令给定的范围内作答，不擅自扩大任务
            - 结论要具体、可执行，避免空泛的套话
            - 信息不足时明确说明缺什么，不要编造事实
            - 最终直接给出结论，不需要寒暄或复述指令
        """.trimIndent(),
    )
}
