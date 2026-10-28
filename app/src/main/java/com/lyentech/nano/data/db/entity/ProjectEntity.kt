package com.lyentech.nano.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 项目表。一个项目是若干会话的归类容器，删除项目时其下会话的归属会被清空而不是级联删除。
 */
@Entity(tableName = "project", indices = [Index(value = ["updateTime"])])
data class ProjectEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "name") val name: String,
    /** 项目描述，显示在项目卡片副标题，允许为空。 */
    @ColumnInfo(name = "description") val description: String = "",
    @ColumnInfo(name = "createTime") val createTime: Long,
    @ColumnInfo(name = "updateTime") val updateTime: Long,
)
