package com.lyentech.nano.data.repo

import com.google.adk.kt.events.Event
import com.google.adk.kt.sessions.SessionKey
import com.google.adk.kt.sessions.SessionService
import com.lyentech.nano.data.db.dao.ConversationDao
import com.lyentech.nano.data.db.entity.ConversationEntity
import com.lyentech.nano.data.model.Conversation
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 会话仓库。把 ADK 的事件日志（RoomSessionService）和本应用的会话元数据（Room）缝在一起：
 *      会话 id 就是 ADK 的 sessionId，元数据表只负责标题、归属项目和排序时间。
 */
class ConversationRepository(
    private val conversationDao: ConversationDao,
    private val sessionService: SessionService,
    private val appName: String,
    private val userId: String,
) {

    fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { list -> list.map { it.toModel() } }

    fun observeConversationsByProject(projectId: String): Flow<List<Conversation>> =
        conversationDao.observeByProject(projectId).map { list -> list.map { it.toModel() } }

    suspend fun findById(conversationId: String): Conversation? =
        conversationDao.findById(conversationId)?.toModel()

    /**
     * 建一个新会话：先让 ADK 建 session（id 由这里生成，便于两库对齐），再写元数据行。
     *
     * @param projectId 归属项目，null 表示不属于任何项目。
     * @param title 初始标题，通常先给「新对话」，首条用户消息发出后再改写。
     */
    suspend fun createConversation(projectId: String? = null, title: String = DEFAULT_TITLE): Conversation {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()
        sessionService.createSession(SessionKey(appName, userId, id))
        val entity = ConversationEntity(
            id = id,
            title = title,
            projectId = projectId,
            createTime = now,
            updateTime = now,
        )
        conversationDao.upsert(entity)
        return entity.toModel()
    }

    /** 读取一个会话的历史事件，用于进入会话时重建界面。 */
    suspend fun loadEvents(conversationId: String): List<Event> =
        sessionService.listEvents(SessionKey(appName, userId, conversationId)).events

    suspend fun rename(conversationId: String, title: String) {
        conversationDao.rename(conversationId, title, System.currentTimeMillis())
    }

    suspend fun assignToProject(conversationId: String, projectId: String?) {
        conversationDao.assignProject(conversationId, projectId, System.currentTimeMillis())
    }

    /** 删除会话：先删 ADK 的事件（外键级联），再删元数据行。 */
    suspend fun delete(conversationId: String) {
        sessionService.deleteSession(SessionKey(appName, userId, conversationId))
        conversationDao.delete(conversationId)
    }

    /** 会话产生新消息时刷新排序时间，让最近用过的会话排在抽屉最上方。 */
    suspend fun touch(conversationId: String) {
        val current = conversationDao.findById(conversationId) ?: return
        conversationDao.upsert(current.copy(updateTime = System.currentTimeMillis()))
    }

    private fun ConversationEntity.toModel() = Conversation(
        id = id,
        title = title,
        projectId = projectId,
        createTime = createTime,
        updateTime = updateTime,
    )

    companion object {
        const val DEFAULT_TITLE = "新对话"
    }
}
