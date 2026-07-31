package com.codeflow.model

import com.google.gson.annotations.SerializedName

// 群信息（用于放大镜群列表展示）
data class Group(
    val id: String,
    val name: String,
    val hostName: String,
    val hostIp: String,
    val hostPort: Int,
    val hasPassword: Boolean,
    var memberCount: Int = 1,
    var isDisbanded: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

// 群成员
data class GroupMember(
    val id: String,
    val nickname: String,
    val isHost: Boolean = false
)

// 当前所在的群会话
data class GroupSession(
    val groupId: String,
    val groupName: String,
    val hostName: String,
    val hostIp: String,
    val hostPort: Int,
    val isHost: Boolean,
    val myMemberId: String,
    val myNickname: String
)
