package com.orbitai.domain.models

data class Project(
    val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long
)

data class ChatSession(
    val id: String,
    val projectId: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

enum class MessageRole { USER, MODEL, SYSTEM, TOOL }

data class Message(
    val id: String,
    val sessionId: String,
    val role: MessageRole,
    val content: String,
    val timestamp: Long,
    // Attachment display names captured at send time, so the bubble + history
    // show what was attached (previously discarded on send).
    val attachments: List<String> = emptyList()
)

data class Agent(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val runCommand: String = ""
)

data class TermuxLog(
    val id: String,
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestamp: Long
)

data class Skill(
    val id: String,
    val name: String,
    val content: String,
    val enabled: Boolean = true
)
