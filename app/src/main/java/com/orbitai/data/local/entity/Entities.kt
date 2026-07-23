package com.orbitai.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.orbitai.domain.models.*

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "sessions",
    indices = [Index("projectId")]
)
data class SessionEntity(
    @PrimaryKey val id: String,
    val projectId: String?,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "messages",
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    // Persisted attachment display names (JSON array string). Lets the sent
    // message bubble + history actually show what was attached (the old code
    // stored only `content`, so attachments vanished on send).
    val attachments: String = ""
)

private fun encodeAttachments(list: List<String>): String =
    if (list.isEmpty()) "" else list.joinToString("\u0000")

private fun decodeAttachments(raw: String): List<String> =
    if (raw.isBlank()) emptyList() else raw.split("\u0000")

@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val runCommand: String = ""
)

@Entity(tableName = "termux_logs")
data class TermuxLogEntity(
    @PrimaryKey val id: String,
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestamp: Long
)

fun ProjectEntity.toProject() = Project(id, name, description, createdAt, updatedAt)
fun Project.toEntity() = ProjectEntity(id, name, description, createdAt, updatedAt)

fun SessionEntity.toSession() = ChatSession(id, projectId, title, createdAt, updatedAt)
fun ChatSession.toEntity() = SessionEntity(id, projectId, title, createdAt, updatedAt)

fun MessageEntity.toMessage() = Message(id, sessionId, MessageRole.valueOf(role), content, timestamp,
    decodeAttachments(attachments))
fun Message.toEntity() = MessageEntity(id, sessionId, role.name, content, timestamp,
    encodeAttachments(attachments))

fun AgentEntity.toAgent() = Agent(id, name, description, systemPrompt, runCommand)
fun Agent.toEntity() = AgentEntity(id, name, description, systemPrompt, runCommand)

fun TermuxLogEntity.toTermuxLog() = TermuxLog(id, command, output, exitCode, timestamp)
fun TermuxLog.toEntity() = TermuxLogEntity(id, command, output, exitCode, timestamp)

@Entity(tableName = "skills")
data class SkillEntity(
    @PrimaryKey val id: String,
    val name: String,
    val content: String,
    val enabled: Boolean = true
)

fun SkillEntity.toSkill() = Skill(id, name, content, enabled)
fun Skill.toEntity() = SkillEntity(id, name, content, enabled)
