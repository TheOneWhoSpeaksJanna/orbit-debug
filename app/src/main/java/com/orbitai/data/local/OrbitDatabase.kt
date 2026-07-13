package com.orbitai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.orbitai.data.local.dao.OrbitAiDao
import com.orbitai.data.local.entity.*

@Database(
    entities = [
        ProjectEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        AgentEntity::class,
        TermuxLogEntity::class,
        SkillEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class OrbitAiDatabase : RoomDatabase() {
    abstract fun dao(): OrbitAiDao
}
