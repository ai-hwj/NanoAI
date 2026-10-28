package com.lyentech.nano.data.model

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 会话领域模型，用于抽屉列表和会话切换。
 */
data class Conversation(
    val id: String,
    val title: String,
    val projectId: String?,
    val createTime: Long,
    val updateTime: Long,
)
