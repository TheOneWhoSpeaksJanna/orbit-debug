package com.orbitai.core.logging

import kotlinx.coroutines.CoroutineExceptionHandler
import android.util.Log

/**
 * Centralized coroutine exception handler for ViewModels.
 *
 * Without this, an uncaught exception in a viewModelScope.launch { }
 * silently kills the coroutine — no log line, no crash, no clue why
 * the UI stopped responding. This handler logs every uncaught exception
 * with the full stack trace so it shows up in the log file.
 *
 * Usage in any ViewModel:
 *   private val exceptionHandler = CoroutineExceptionHandlerFactory.create("MyViewModel")
 *   viewModelScope.launch(exceptionHandler) { ... }
 *
 * Or apply it to the entire ViewModel scope by overriding viewModelScope
 * (more advanced — see MainViewModel for an example).
 */
object CoroutineExceptionHandlerFactory {

    fun create(tag: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            FileLogger.e(tag, "Unhandled coroutine exception", throwable, "thread=${Thread.currentThread().name}")
        }
    }
}
