package com.lyentech.nano.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lyentech.nano.data.db.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 会话元数据读写。事件/正文不在本表，见 [ConversationEntity] 的类注释。
 */
@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversation ORDER BY updateTime DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE projectId = :projectId ORDER BY updateTime DESC")
    fun observeByProject(projectId: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversation WHERE id = :id")
    suspend fun findById(id: String): ConversationEntity?

    /** 项目删除时解除会话归属，而不是删掉用户的聊天记录。 */
    @Query("UPDATE conversation SET projectId = NULL, updateTime = :now WHERE projectId = :projectId")
    suspend fun detachFromProject(projectId: String, now: Long)

    @Upsert
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversation SET title = :title, updateTime = :now WHERE id = :id")
    suspend fun rename(id: String, title: String, now: Long)

    @Query("UPDATE conversation SET projectId = :projectId, updateTime = :now WHERE id = :id")
    suspend fun assignProject(id: String, projectId: String?, now: Long)

    @Query("DELETE FROM conversation WHERE id = :id")
    suspend fun delete(id: String)
}
