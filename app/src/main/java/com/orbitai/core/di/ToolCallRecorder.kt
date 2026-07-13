package com.orbitai.core.di

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ToolCallRecord(
    val sessionId: String,
    val command: String,
    val output: String,
    val exitCode: Int,
    val timestamp: Long = System.currentTimeMillis()
)

class ToolCallRecorder {
    private val _records = MutableStateFlow<List<ToolCallRecord>>(emptyList())
    val records: StateFlow<List<ToolCallRecord>> = _records.asStateFlow()

    fun record(record: ToolCallRecord) {
        _records.value = _records.value + record
    }

    fun getRecordsForSession(sessionId: String): List<ToolCallRecord> =
        _records.value.filter { it.sessionId == sessionId }

    fun clearSession(sessionId: String) {
        _records.value = _records.value.filter { it.sessionId != sessionId }
    }

    fun clearAll() {
        _records.value = emptyList()
    }
}
