package com.orbitai.core.di

import android.content.Context
import androidx.room.Room
import com.orbitai.data.api.tools.ExecuteCommandTool
import com.orbitai.data.api.tools.SudoCommandTool
import com.orbitai.data.api.tools.ToolRegistry
import com.orbitai.data.local.OrbitAiDatabase
import com.orbitai.data.local.prefs.PreferencesManager
import com.orbitai.data.local.runner.LocalCommandRunner
import com.orbitai.data.local.runtime.OrbitAiRuntimeManager
import com.orbitai.data.local.updater.SilentUpdater
import com.orbitai.data.repository.OrbitAiRepositoryImpl
import com.orbitai.data.repository.OpenCodeRepositoryImpl
import com.orbitai.domain.api.AiProvider
import com.orbitai.domain.repository.OrbitAiRepository
import com.orbitai.domain.repository.OpenCodeRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

interface AppContainer {
    val appContext: Context
    val repository: OrbitAiRepository
    val prefsManager: PreferencesManager
    val aiProvider: AiProvider
    val toolRegistry: ToolRegistry
    val localCommandRunner: LocalCommandRunner
    val runtimeManager: com.orbitai.data.local.runtime.OrbitAiRuntimeManager
    val termuxRuntime: com.orbitai.data.local.runtime.TermuxRuntime
    val hermesRuntime: com.orbitai.data.local.runtime.HermesRuntime
    val silentUpdater: SilentUpdater
    val toolCallRecorder: ToolCallRecorder
    val openCodeRepository: OpenCodeRepository
    val okHttpClient: OkHttpClient
}

class DefaultAppContainer(private val context: Context) : AppContainer {
    override val appContext: Context get() = context
    private val database: OrbitAiDatabase by lazy {
        Room.databaseBuilder(context, OrbitAiDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()
    }

    override val repository: OrbitAiRepository by lazy {
        OrbitAiRepositoryImpl(database.dao())
    }

    override val prefsManager: PreferencesManager by lazy {
        PreferencesManager(context)
    }

    override val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectionPool(okhttp3.ConnectionPool(POOL_SIZE, POOL_KEEPALIVE_SECONDS, TimeUnit.SECONDS))
            .build()
    }

    override val aiProvider: AiProvider by lazy {
        com.orbitai.data.api.providers.AiProviderSelector(okHttpClient, context)
    }

    override val runtimeManager: com.orbitai.data.local.runtime.OrbitAiRuntimeManager by lazy {
        com.orbitai.data.local.runtime.OrbitAiRuntimeManager(context)
    }

    override val localCommandRunner: LocalCommandRunner by lazy {
        LocalCommandRunner(runtimeManager)
    }

    override val termuxRuntime: com.orbitai.data.local.runtime.TermuxRuntime by lazy {
        com.orbitai.data.local.runtime.TermuxRuntime(context)
    }
    override val hermesRuntime: com.orbitai.data.local.runtime.HermesRuntime by lazy {
        com.orbitai.data.local.runtime.HermesRuntime(context)
    }

    override val silentUpdater: SilentUpdater by lazy {
        SilentUpdater(context, runtimeManager)
    }

    override val toolCallRecorder: ToolCallRecorder by lazy {
        ToolCallRecorder()
    }

    override val toolRegistry: ToolRegistry by lazy {
        ToolRegistry(
            listOf(
                ExecuteCommandTool(localCommandRunner),
                SudoCommandTool(localCommandRunner)
            )
        )
    }

    override val openCodeRepository: OpenCodeRepository by lazy {
        OpenCodeRepositoryImpl()
    }

    companion object {
        private const val DATABASE_NAME = "omniclaw_database"
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 60L
        private const val WRITE_TIMEOUT_SECONDS = 60L
        private const val POOL_SIZE = 5
        private const val POOL_KEEPALIVE_SECONDS = 30L
    }
}
