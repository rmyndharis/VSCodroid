package com.vscodroid.setup

import android.content.Context
import android.content.pm.ApplicationInfo
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * That every process the server starts is told where the Android build of the
 * zeromq addon lives.
 *
 * The Jupyter extension from Open VSX carries zeromq with prebuilds for glibc and
 * musl only. Its loader replaces the package's directory with
 * `process.env.ZEROMQ_PREBUILD` when that is set, so this one variable is what
 * decides whether a notebook kernel is reached directly or the extension falls
 * back to a Jupyter server, which cannot be installed or started here. Measured on
 * an API 33 emulator: without the addon the extension's own log reads "Exception
 * while attempting zmq : No native build was found for platform=android" and the
 * cell answers with a prompt to pip install jupyter and notebook.
 */
class ZeromqPrebuildEnvTest {

    @TempDir
    lateinit var filesDir: File

    private lateinit var context: Context

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        mockkStatic(AssetPackManagerFactory::class)
        every { AssetPackManagerFactory.getInstance(any()) } returns mockk(relaxed = true)

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.cacheDir } returns File(filesDir, "cache")
        every { context.applicationInfo } returns ApplicationInfo().apply {
            nativeLibraryDir = "/data/app/~~hash==/com.vscodroid-hash==/lib/arm64"
        }
        every { context.getExternalFilesDir(null) } returns File(filesDir, "external")

        File(filesDir, "home/.vscodroid").mkdirs()
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * The directory, not the file: the loader appends `build/Release` itself, and
     * the path is the one `scripts/build-native-addons.sh` writes to under
     * `assets/usr`, which first-run setup extracts into `filesDir/usr`.
     *
     * Asserted with nothing on disk, because the row is deliberately unconditional.
     * A missing addon fails in the extension exactly as it would with the variable
     * unset, but the error then names this directory, which is the clue to where
     * the file should have been.
     */
    @Test
    fun `the zeromq prebuild directory is set whether or not the addon is on disk`() {
        val env = Environment.buildProcessEnvironment(context, 1234)

        assertEquals(
            "${filesDir.absolutePath}/usr/lib/node-addons/zeromq",
            env["ZEROMQ_PREBUILD"],
            "ZEROMQ_PREBUILD is not the extracted addon directory, so the Jupyter extension " +
                "finds no build it can load and offers a pip install that cannot succeed",
        )
    }
}
