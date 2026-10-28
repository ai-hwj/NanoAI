package com.lyentech.nano.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.lyentech.nano.data.db.entity.ProjectEntity
import kotlinx.coroutines.flow.Flow

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 项目表读写。所有查询都返回 Flow，界面随数据库变化自动刷新。
 */
@Dao
interface ProjectDao {

    @Query("SELECT * FROM project ORDER BY updateTime DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM project WHERE id = :id")
    suspend fun findById(id: String): ProjectEntity?

    @Upsert
    suspend fun upsert(project: ProjectEntity)

    @Query("DELETE FROM project WHERE id = :id")
    suspend fun delete(id: String)
}
