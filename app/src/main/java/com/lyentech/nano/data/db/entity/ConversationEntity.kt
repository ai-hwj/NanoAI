package com.lyentech.nano.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 会话元数据表。正文与事件日志由 ADK 的 RoomSessionService 负责落库（adk_sessions.db），
 *      这张表只存 UI 需要的会话属性，[id] 与 ADK 的 sessionId 一一对应。
 *
 * [projectId] 为空表示会话不属于任何项目（显示在抽屉的"最近对话"里）。
 */
@Entity(
    tableName = "conversation",
    indices = [Index(value = ["projectId"]), Index(value = ["updateTime"])],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    /** 会话标题，取首条用户消息的前若干字符，允许用户后续重命名。 */
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "projectId") val projectId: String? = null,
    @ColumnInfo(name = "createTime") val createTime: Long,
    @ColumnInfo(name = "updateTime") val updateTime: Long,
)
