package com.orbitai.verification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orbitai.core.logging.FileLogger
import com.orbitai.core.storage.StorageSetup
import com.orbitai.data.local.runtime.TermuxRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Headless end-to-end verification of the two terminal/storage fixes:
 *  - TermuxRuntime.executeInTermux must actually run PRoot commands
 *    (regression: LD_LIBRARY_PATH used to point at the in-rootfs path,
 *     which does not exist pre-chroot -> every command failed).
 *  - StorageSetup.createStorageSymlinks must expose /storage/emulated/0
 *    as ~/storage/downloads inside the rootfs so the AI can write outside home.
 *
 * Run: ./gradlew :app:connectedOpenclaudeDebugAndroidTest
 * Output is logged under the "Orbit AI" logcat tag (grep VerifyTest).
 */
@RunWith(AndroidJUnit4::class)
class RuntimeVerificationTest {

    @Test
    fun verifyTermuxAndStorage() = runBlocking {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        FileLogger.i("VerifyTest", "start")

        val rt = TermuxRuntime(ctx)

        // 1) Install rootfs (extract bootstrap + node/git/python)
        FileLogger.i("VerifyTest", "installing rootfs...")
        val installed = rt.install { p, s -> FileLogger.i("VerifyTest", "install progress", "${(p * 100).toInt()}% $s") }
        FileLogger.i("VerifyTest", "install result", "ok=$installed isInstalled=${rt.isInstalled}")

        // 2) Run a real PRoot command
        val echo = rt.executeInTermux("echo HELLO_TERMUX_PROOT; pwd; ls /storage/emulated/0", "")
        FileLogger.i(
            "VerifyTest",
            "exec echo",
            "exit=${echo.exitCode} out=${echo.output.take(300)}"
        )

        // 3) Create storage symlinks (headless termux-setup-storage)
        StorageSetup.createStorageSymlinks(rt.homeDir)

        // 4) Verify ~/storage/downloads resolves to real shared storage
        val lsStorage = rt.executeInTermux("ls -la ~/storage/ 2>&1; echo '---'; ls ~/storage/downloads 2>&1 | head", "")
        FileLogger.i(
            "VerifyTest",
            "storage symlinks",
            "exit=${lsStorage.exitCode} out=${lsStorage.output.take(500)}"
        )

        // 5) Write a file to /storage/emulated/0/Download from inside rootfs
        val write = rt.executeInTermux(
            "echo 'orbit-write-test' > ~/storage/downloads/orbit_test_md.md && echo WROTE_OK || echo WROTE_FAIL",
            ""
        )
        FileLogger.i(
            "VerifyTest",
            "storage write",
            "exit=${write.exitCode} out=${write.output.take(200)}"
        )

        FileLogger.i("VerifyTest", "done")
    }
}
