package com.orbitai.data.local.runner

import com.orbitai.core.logging.FileLogger
import com.orbitai.data.local.runtime.OrbitAiRuntimeManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

private const val TAG = "LocalCommandRunner"
private const val ERROR_EXIT_CODE = -1

private const val SHIZUKU_NOT_RUNNING = "Shizuku is not running or unavailable."
private const val SHIZUKU_API_CHANGED = "Shizuku API changed \u2014 newProcess method not found. Please update the app."
private const val SHIZUKU_PERMISSION_DENIED = "Shizuku permission denied. Grant permission in the Shizuku app."
private const val ERROR_COMMAND_PREFIX = "Error executing local command: "
private const val ERROR_PRIVILEGED_PREFIX = "Error executing privileged command via Shizuku: "

data class CommandResult(val output: String, val exitCode: Int, val command: String)

class LocalCommandRunner(
    private val runtimeManager: OrbitAiRuntimeManager
) {

    /**
     * Returns the shell command array to use.
     *
     * CRITICAL Android 10+ CONTEXT:
     * On Android 10+ (API 29+), the kernel enforces W^X (writable XOR executable)
     * on app-private storage. This means:
     *  - Real BINARIES (like busybox, node) CAN be exec'd from /data/data/<pkg>/files/
     *  - Shell SCRIPTS CANNOT be exec'd — the kernel blocks execve() on script files
     *    in app_data_file SELinux context, even if they have +x permission
     *
     * So we always use `busybox sh -c <cmd>` (or system `sh -c <cmd>` as fallback).
     * This works because:
     *  - busybox is a real binary → can be exec'd
     *  - `sh -c` reads the command string, doesn't try to exec a script file
     *  - When the command does `exec node`, the shell looks up `node` in PATH;
     *    if PATH includes packages/nodejs/usr/bin/, it finds the real node BINARY
     *    (not a wrapper script) and exec's it successfully
     */
    private fun shellCommand(command: String): List<String> {
        val busyboxPath = runtimeManager.busyBoxPath()
        if (busyboxPath != null) {
            return listOf(busyboxPath, "sh", "-c", command)
        }
        // Fallback: use the system shell. On most devices /system/bin/sh exists
        // and can run ordinary commands (ls, cat, echo, id, am, pm, ...).
        // Without this fallback the privileged (Shizuku) path fails with
        // "BusyBox unavailable -> exit 127" on flavors that don't bundle
        // libbusybox.so (e.g. openclaude). Busybox is only needed for its
        // extended applet set; plain command execution works with the OS shell.
        val sysSh = "${android.system.Os.getenv("ANDROID_ROOT") ?: "/system"}/bin/sh"
        return listOf(sysSh, "-c", command)
    }

    /**
     * Build the ProcessBuilder with the correct environment.
     *
     * PATH includes:
     *  - orbit_runtime/bin/ (busybox + wrapper scripts for `sh <wrapper>` invocation)
     *  - packages/[star]/usr/bin/ (actual binaries: node, npm, git, python3, etc.)
     *  - system PATH
     *
     * LD_LIBRARY_PATH includes:
     *  - packages/[star]/usr/lib/ (shared libs for node, git, python, etc.)
     *
     * Without LD_LIBRARY_PATH, node/git/python fail with "linker: ... not found"
     * because they can't find libnode.so, libpcre2-8.so, etc.
     */
    private fun setupProcessBuilder(command: String): ProcessBuilder {
        val processBuilder = ProcessBuilder(shellCommand(command))
        processBuilder.directory(runtimeManager.runtimeDir)
        val env = processBuilder.environment()

        // Build a comprehensive PATH that includes all package bin directories
        // so that `exec node`, `git --version`, etc. find the REAL binaries
        // (not wrapper scripts that can't be exec'd on Android 10+).
        val fullPath = runtimeManager.buildPath()
        env["PATH"] = fullPath

        // LD_LIBRARY_PATH is critical for shared-lib-dependent binaries.
        val ldPath = runtimeManager.buildLdLibraryPath()
        if (ldPath.isNotBlank()) {
            val existingLd = env["LD_LIBRARY_PATH"] ?: ""
            env["LD_LIBRARY_PATH"] = if (existingLd.isNotBlank()) "$ldPath:$existingLd" else ldPath
        }

        // HOME and TMPDIR help node/npm/git behave correctly
        env["HOME"] = runtimeManager.runtimeDir.absolutePath
        env["TMPDIR"] = runtimeManager.tmpDir.absolutePath

        FileLogger.d(TAG, "Process env", "PATH=${fullPath.take(2000)} LD_LIBRARY_PATH=${ldPath.take(2000)}")
        return processBuilder
    }

    suspend fun executeCommandStreamed(command: String, onOutput: (String) -> Unit): CommandResult =
        withContext(Dispatchers.IO) {
            FileLogger.i(TAG, "Command exec start (streamed)", "cmd=${command.take(2000)}")
            try {
                val process = setupProcessBuilder(command).start()
                val outputBuilder = StringBuilder()

                val stdInThread = Thread {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        generateSequence { reader.readLine() }.forEach { line ->
                            outputBuilder.appendLine(line)
                            onOutput(line)
                        }
                    }
                }

                val stdErrThread = Thread {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        generateSequence { reader.readLine() }.forEach { line ->
                            outputBuilder.appendLine(line)
                            onOutput(line)
                        }
                    }
                }

                stdInThread.start()
                stdErrThread.start()

                process.waitFor()
                stdInThread.join()
                stdErrThread.join()

                val exit = process.exitValue()
                if (exit != 0) {
                    FileLogger.w(TAG, "Command exec failed", "exit=$exit cmd=${command.take(2000)}")
                    FileLogger.w(TAG, "Command output", "output=${outputBuilder.toString().take(2000)}")
                }
                CommandResult(outputBuilder.toString().trim(), exit, command)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Command exec exception", e, "cmd=${command.take(2000)} reason=${e.message}")
                val errorMsg = "$ERROR_COMMAND_PREFIX${e.message}"
                onOutput(errorMsg)
                CommandResult(errorMsg, ERROR_EXIT_CODE, command)
            }
        }

    suspend fun executeCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        FileLogger.i(TAG, "Command exec start", "cmd=${command.take(2000)}")
        try {
            val process = setupProcessBuilder(command).start()
            val output = buildString {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    generateSequence { reader.readLine() }.forEach { line ->
                        appendLine(line)
                    }
                }
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    generateSequence { reader.readLine() }.forEach { line ->
                        appendLine(line)
                    }
                }
            }
            process.waitFor()
            val exit = process.exitValue()
            if (exit != 0) {
                FileLogger.w(TAG, "Command exec failed", "exit=$exit cmd=${command.take(2000)}")
                FileLogger.w(TAG, "Command output", "output=${output.take(2000)}")
            } else {
                FileLogger.d(TAG, "Command exec success", "exit=0 cmd=${command.take(2000)}")
            }
            CommandResult(output.trim(), exit, command)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Command exec exception", e, "cmd=${command.take(2000)} reason=${e.message}")
            CommandResult("$ERROR_COMMAND_PREFIX${e.message}", ERROR_EXIT_CODE, command)
        }
    }

    suspend fun executePrivilegedCommand(command: String): CommandResult = withContext(Dispatchers.IO) {
        FileLogger.i(TAG, "Command exec start (privileged)", "cmd=${command.take(2000)}")
        if (!Shizuku.pingBinder()) {
            FileLogger.w(TAG, "Shizuku not running", "action=rejected")
            return@withContext CommandResult(SHIZUKU_NOT_RUNNING, ERROR_EXIT_CODE, command)
        }
        try {
            val newProcessMethod = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            newProcessMethod.isAccessible = true
            val processObj = newProcessMethod.invoke(
                null, shellCommand(command).toTypedArray(), null, null
            )
            // Safe-cast — Shizuku API is invoked via reflection and could
            // return null or a wrapper type on version mismatch. Without
            // this guard, a ClassCastException would be caught as a generic
            // Exception and the user would get a useless error message.
            val process = processObj as? Process ?: run {
                FileLogger.e(TAG, "Shizuku newProcess returned non-Process",
                    "type=${processObj?.javaClass?.name}")
                return@withContext CommandResult(
                    "Shizuku API mismatch: newProcess returned ${processObj?.javaClass?.name}",
                    ERROR_EXIT_CODE,
                    command
                )
            }

            val output = buildString {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    generateSequence { reader.readLine() }.forEach { line ->
                        appendLine(line)
                    }
                }
                BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                    generateSequence { reader.readLine() }.forEach { line ->
                        appendLine(line)
                    }
                }
            }
            process.waitFor()
            val exit = process.exitValue()
            FileLogger.d(TAG, "Privileged exec result", "exit=$exit cmd=${command.take(2000)}")
            CommandResult(output.trim(), exit, command)
        } catch (e: NoSuchMethodException) {
            FileLogger.e(TAG, "Shizuku API error", e, "reason=${e.message}")
            CommandResult(SHIZUKU_API_CHANGED, ERROR_EXIT_CODE, command)
        } catch (e: SecurityException) {
            FileLogger.e(TAG, "Shizuku permission denied", e, "reason=${e.message}")
            CommandResult(SHIZUKU_PERMISSION_DENIED, ERROR_EXIT_CODE, command)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Privileged exec exception", e, "reason=${e.message}")
            CommandResult("$ERROR_PRIVILEGED_PREFIX${e.message}", ERROR_EXIT_CODE, command)
        }
    }
}
