package com.lyentech.nano.data.repo

import com.lyentech.nano.data.db.dao.ConversationDao
import com.lyentech.nano.data.db.dao.ProjectDao
import com.lyentech.nano.data.db.entity.ProjectEntity
import com.lyentech.nano.data.model.Project
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 项目仓库。项目与会话计数的聚合放在这里，UI 层只消费领域模型。
 */
class ProjectRepository(
    private val projectDao: ProjectDao,
    private val conversationDao: ConversationDao,
) {

    /** 全部项目，附带各自的会话数量，按最近更新排序。 */
    fun observeProjects(): Flow<List<Project>> =
        combine(projectDao.observeAll(), conversationDao.observeAll()) { projects, conversations ->
            val counts = conversations.groupingBy { it.projectId }.eachCount()
            projects.map { it.toModel(counts[it.id] ?: 0) }
        }

    suspend fun create(name: String, description: String = ""): Project {
        val now = System.currentTimeMillis()
        val entity = ProjectEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            description = description,
            createTime = now,
            updateTime = now,
        )
        projectDao.upsert(entity)
        return entity.toModel(0)
    }

    suspend fun rename(id: String, name: String, description: String? = null) {
        val current = projectDao.findById(id) ?: return
        projectDao.upsert(
            current.copy(
                name = name,
                description = description ?: current.description,
                updateTime = System.currentTimeMillis(),
            )
        )
    }

    /** 删除项目并把其下会话退回「不属于任何项目」，聊天记录本身保留。 */
    suspend fun delete(id: String) {
        conversationDao.detachFromProject(id, System.currentTimeMillis())
        projectDao.delete(id)
    }

    private fun ProjectEntity.toModel(count: Int) = Project(
        id = id,
        name = name,
        description = description,
        createTime = createTime,
        updateTime = updateTime,
        conversationCount = count,
    )
}
