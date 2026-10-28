package com.lyentech.nano.http.gree

import com.google.adk.kt.agents.Instruction
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.agents.RunConfig
import com.google.adk.kt.agents.StreamingMode
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.InMemorySessionService
import com.google.adk.kt.types.Content
import com.google.adk.kt.types.GenerateContentConfig
import com.google.adk.kt.types.Part
import com.google.adk.kt.types.Role
import com.google.adk.kt.types.Tool
import com.lyentech.nano.BuildConfig
import com.lyentech.nano.agent.tools.TodoStore
import com.lyentech.nano.agent.tools.TodoWriteTool
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真连公司端点的工具调用活体测试。默认跳过（要网络、要密钥，不适合放进常规构建）。要验证时手动开：
 *
 *   ./gradlew :app:testDebugUnitTest --tests "*GreeToolLoopLiveTest" -Pnano.live.gree
 *
 * 为什么值得单独存在：[GreeToolCallingTest] 用的是硬编码 fixture，它全绿只能证明"我们按 OpenAI
 * 的形状拼了请求"，证明不了端点接受 `tools`、也证明不了第二轮带 `role = "tool"` 结果的回灌请求
 * 不被判 400 —— 上一版实现正是卡在后一步（400）。真连一次才算通。
 */
class GreeToolLoopLiveTest {

    /** 与联网测试同一套开关语义：`-Pnano.live.gree`（不带值）传进来的是空字符串，按非 false 即开启。 */
    private val liveEnabled = System.getProperty("nano.live.gree")?.let { it != "false" } ?: false

    private fun assumeLive() {
        assumeTrue("需要 -Pnano.live.gree 才跑端点活体测试", liveEnabled)
        assumeTrue("local.properties 里没有 GREE_API_KEY，端点会直接拒绝", BuildConfig.GREE_API_KEY.isNotBlank())
    }

    /**
     * 活体测试里日志不能吞掉：端点回 400 时，唯一有用的信息就是它错误体里写的那句话
     * （GreeChatModel 把截断后的错误体只交给日志，异常消息里刻意没有）。
     */
    private fun model(): GreeChatModel =
        GreeChatModel(
            client = loggingClient(),
            log =
                object : GreeLog {
                    override fun info(tag: String, message: String) {
                        println("[$tag] $message")
                    }

                    override fun warn(tag: String, message: String) {
                        println("[$tag] $message")
                    }
                },
        )

    /**
     * 把每一次实际发出去的请求体打到标准输出。
     *
     * 端点那句 "the request was rejected by an internal MaaS component" 不指明是哪个字段，
     * 只能拿原始 body 逐个字段做减法才能定位；不写进 GreeChatModel 是因为生产环境不该把
     * 用户对话内容整条落进日志。
     */
    private fun loggingClient(): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val body = chain.request().body?.let { requestBody ->
                    okio.Buffer().also { requestBody.writeTo(it) }.readUtf8()
                }
                println("REQUEST ${body ?: "(no body)"}")
                chain.proceed(chain.request())
            }
            .build()

    /** 只验证请求形状被接受：带 `tools` 的第一次请求不能报 4xx。 */
    @Test
    fun endpointAcceptsToolDeclarations() = runBlocking {
        assumeLive()
        val tool = TodoWriteTool(TodoStore())
        val request =
            LlmRequest(
                contents =
                    listOf(
                        Content(
                            role = Role.USER,
                            parts = listOf(Part(text = "用清单工具把「先写方案、再跑测试」记成待办。")),
                        )
                    ),
                config = GenerateContentConfig(tools = listOf(Tool(functionDeclarations = listOf(tool.declaration())))),
            )

        val responses = model().generateContent(request, stream = false).toList()
        val final = responses.last()
        val calls = final.content?.parts?.mapNotNull { it.functionCall?.name }.orEmpty()

        println("终结响应 ${responses.size} 条；正文=${final.content?.text()?.take(60)}；调用=$calls")
        assertTrue("端点没返回任何终结响应", responses.isNotEmpty())
        assertTrue("既没有正文也没有工具调用，说明响应解析和端点形状对不上", calls.isNotEmpty() || !final.content?.text().isNullOrEmpty())
    }

    /**
     * 完整工具环：模型调用 → 本地执行 → 结果回灌 → 模型收口。
     *
     * 关键在第 3 步：回灌的请求里同时有 `assistant.tool_calls` 和 `role = "tool"` 消息，历史版本
     * 就是在这里被端点判 400。任何一次请求失败都会以 IOException 抛出，测试直接红。
     */
    @Test
    fun agentRunsTheToolAndFeedsItsResultBack() = runBlocking {
        assumeLive()
        val store = TodoStore()
        val agent =
            LlmAgent(
                name = AGENT_NAME,
                model = model(),
                tools = listOf(TodoWriteTool(store)),
                instruction =
                    Instruction("涉及多步任务时必须调用 todo_write 工具记录清单，不要用纯文字代替工具调用。"),
            )
        val runner =
            InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = InMemorySessionService())

        try {
            val events =
                runner
                    .runAsync(
                        userId = USER_ID,
                        sessionId = SESSION_ID,
                        newMessage =
                            Content(
                                role = Role.USER,
                                parts = listOf(Part(text = "帮我规划两件事：先写方案，再跑测试。必须先调用工具。")),
                            ),
                        runConfig = RunConfig(streamingMode = StreamingMode.NONE, maxLlmCalls = 6),
                    )
                    .toList()

            val list = store.get(SESSION_ID)
            println("事件 ${events.size} 条；清单=${list?.items?.map { it.status }}")

            assertTrue("模型没有调用工具：端点可能没返回 tool_calls，或返回的形状没被解析出来", list != null)
            assertTrue(
                "工具调用没有被回灌成第二轮请求（清单为空）",
                list!!.items.isNotEmpty(),
            )
            // 工具执行的错误也要看得见：todo_write 只会用 error 键报错，正文里未必会出现。
            events.forEach { it.errorMessage?.let { message -> error("回合中出现模型错误：$message") } }
        } finally {
            runner.close()
        }
    }

    /** 流式路径下的工具环：分片 tool_calls 会被累积成一次完整调用。 */
    @Test
    fun streamingTurnAccumulatesToolCallFragments() = runBlocking {
        assumeLive()
        val store = TodoStore()
        val agent =
            LlmAgent(
                name = AGENT_NAME,
                model = model(),
                tools = listOf(TodoWriteTool(store)),
                instruction = Instruction("必须先调用 todo_write 工具建清单，再回答。"),
            )
        val runner =
            InMemoryRunner(agent = agent, appName = APP_NAME, sessionService = InMemorySessionService())

        try {
            val events =
                runner
                    .runAsync(
                        userId = USER_ID,
                        sessionId = SESSION_ID,
                        newMessage =
                            Content(role = Role.USER, parts = listOf(Part(text = "把「读代码、改配置、验证」记成待办清单。"))),
                        runConfig = RunConfig(streamingMode = StreamingMode.SSE, maxLlmCalls = 6),
                    )
                    .toList()

            val calls = events.flatMap { it.content?.parts.orEmpty() }.mapNotNull { it.functionCall?.name }
            println("事件 ${events.size} 条；出现的调用=$calls；清单=${store.get(SESSION_ID)?.items?.size}")

            assertTrue("流式路径下没有出现任何工具调用分片", calls.isNotEmpty())
            events.forEach { it.errorMessage?.let { message -> error("回合中出现模型错误：$message") } }
        } finally {
            runner.close()
        }
    }

    private companion object {
        const val APP_NAME = "GreeToolLive"
        const val USER_ID = "live-user"
        const val SESSION_ID = "live-session"
        const val AGENT_NAME = "LiveAgent"
    }
}
