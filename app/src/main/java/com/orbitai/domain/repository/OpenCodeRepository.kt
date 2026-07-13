package com.orbitai.domain.repository

import com.orbitai.domain.models.DownloadableAgent
import kotlinx.coroutines.flow.Flow

interface OpenCodeRepository {
    fun getAvailableAgents(): Flow<List<DownloadableAgent>>
    suspend fun refreshCatalog()
    suspend fun getAgentById(id: String): DownloadableAgent?
}
