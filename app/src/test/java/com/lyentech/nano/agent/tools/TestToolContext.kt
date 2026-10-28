package com.lyentech.nano.agent.tools

import com.google.adk.kt.agents.InvocationContext
import com.google.adk.kt.agents.LlmAgent
import com.google.adk.kt.models.LlmRequest
import com.google.adk.kt.models.LlmResponse
import com.google.adk.kt.models.Model
import com.google.adk.kt.sessions.Session
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.tools.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 测试用的最小 [ToolContext]。
 *
 * ADK 的 `ToolContext` 要一个完整的 `InvocationContext`，而后者又要 session 与 agent。
 * 这里用一个不做任何事的假模型把它们拼出来即可 —— session 是真的会传进工具，所以
 * [fakeToolContext] 允许指定 sessionId，读上下文的工具（如 todo_write 按会话隔离清单）才能在
 * 宿主单测里被验证。
 */
internal class FakeModel : Model {
    override val name: String = "fake-model"

    override fun generateContent(request: LlmRequest, stream: Boolean): Flow<LlmResponse> = flow { }
}

/** [fakeToolContext] 默认给的会话 id；断言清单归属时用它，别重复写字面量。 */
internal const val FAKE_SESSION_ID = "s1"

internal fun fakeToolContext(sessionId: String? = FAKE_SESSION_ID): ToolContext {
    val agent = LlmAgent(name = "test-agent", model = FakeModel())
    val session = Session(key = SessionKey(appName = "test-app", userId = "test-user", id = sessionId))
    val invocation = InvocationContext(session = session, agent = agent)
    return ToolContext(invocation)
}
