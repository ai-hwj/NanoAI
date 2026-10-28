package com.lyentech.nano.data.repo

import android.content.Context
import com.google.adk.kt.agents.BaseAgent
import com.google.adk.kt.runners.InMemoryRunner
import com.google.adk.kt.sessions.room.RoomSessionService

/**
 * @author by jason-何伟杰，2026/10/9
 * des: ADK 侧的长生命周期服务。
 *
 * [RoomSessionService.fromContext] 每次调用都会新建一个 Room 数据库句柄（ADK 内部没有做缓存），
 * 而它官方推荐「每个 Application 一个实例」。如果由 ViewModel 各自创建，屏幕重建时新旧句柄会并存，
 * 旧实例 `close()` 时可能把新实例正在用的数据库关掉，所以这里收敛成进程级单例。
 *
 * 进程存活期间不关闭：Android 不要求显式关闭数据库，进程退出时由系统回收。
 */
object AgentServices {

    @Volatile private var sessionService: RoomSessionService? = null

    fun sessionService(context: Context): RoomSessionService =
        sessionService ?: synchronized(this) {
            sessionService ?: RoomSessionService.fromContext(context.applicationContext)
                .also { sessionService = it }
        }

    /**
     * 应用级 appName。所有会话都挂在它下面，会话 id 才是区分维度。
     */
    const val APP_NAME = "NanoAgentApp"

    /** 本机单用户应用，固定一个 userId。 */
    const val USER_ID = "local-user"

    /** 创建绑定到 [agent] 的 runner，session 存储统一走进程级单例。 */
    fun runner(context: Context, agent: BaseAgent): InMemoryRunner =
        InMemoryRunner(
            agent = agent,
            appName = APP_NAME,
            sessionService = sessionService(context),
        )
}
