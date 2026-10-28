package com.lyentech.nano.data.model

/**
 * @author by jason-何伟杰，2026/10/9
 * des: 项目领域模型。conversationCount 由仓库层聚合，不落库，避免多余的计数字段同步。
 */
data class Project(
    val id: String,
    val name: String,
    val description: String,
    val createTime: Long,
    val updateTime: Long,
    val conversationCount: Int = 0,
)
