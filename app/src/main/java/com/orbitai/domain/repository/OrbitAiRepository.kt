package com.orbitai.domain.repository

import com.orbitai.domain.models.*
import kotlinx.coroutines.flow.Flow

interface OrbitAiRepository {
    fun getAllProjects(): Flow<List<Project>>
    suspend fun insertProject(project: Project)

    fun getAllSessions(): Flow<List<ChatSession>>
    fun getSessionsForProject(projectId: String): Flow<List<ChatSession>>
    suspend fun insertSession(session: ChatSession)

    fun getMessagesForSession(sessionId: String): Flow<List<Message>>
    suspend fun insertMessage(message: Message)

    suspend fun deleteEmptySessions()

    suspend fun deleteSession(sessionId: String)
    suspend fun updateSessionTitle(sessionId: String, title: String)

    fun getAllAgents(): Flow<List<Agent>>
    suspend fun insertAgent(agent: Agent)

    fun getAllTermuxLogs(): Flow<List<TermuxLog>>
    suspend fun insertTermuxLog(log: TermuxLog)

    fun getEnabledSkills(): Flow<List<Skill>>
    fun getAllSkills(): Flow<List<Skill>>
    suspend fun insertSkill(skill: Skill)
    suspend fun setSkillEnabled(skillId: String, enabled: Boolean)
    suspend fun updateSkillContent(skillId: String, content: String)
    suspend fun deleteSkill(skill: Skill)
}
