package com.vscodroid

import com.vscodroid.util.PortFinder
import io.mockk.every
import io.mockk.mockkObject
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/**
 * That a process-wide mock does not survive the test that installed it.
 *
 * `mockkObject`, `mockkStatic` and `mockkConstructor` replace their target for the
 * whole JVM, and Gradle runs this suite in one JVM (`useJUnitPlatform()`, no
 * `forkEvery`, no `maxParallelForks`), so a mock left standing outlives its class.
 * The damage lands elsewhere: three tests in `CrashReporterTest` once failed
 * because another class had left `Logger` mocked, and the suite passed overall only
 * because some class scheduled in between called `unmockkAll()` for its own reasons.
 *
 * ## Why this file no longer reads the sources
 *
 * It used to walk `src/test/kotlin` and require an `unmockk` call in every file
 * that installs a mock. That measured how well it searched. Deleting the
 * `@AfterEach` annotation from a teardown leaves the call in the file, so the text
 * still matched and the method never ran — the guard passed while the thing it
 * guarded was gone.
 *
 * The cleanup is now mockk's own `io.mockk.junit5.MockKExtension`, which the mockk
 * jar declares in its `META-INF/services` and which calls `unmockkAll()` after
 * every test. All it needed was switching on:
 * `junit.jupiter.extensions.autodetection.enabled = true` in
 * `src/test/resources/junit-platform.properties`. No class has to remember, so
 * none can forget, and there is no annotation whose deletion matters.
 *
 * What is left worth testing is whether the mechanism is actually in force, which
 * is what these two cases do — and unlike a source scan, they fail if that
 * property is removed or set to false. Confirmed by doing exactly that before
 * trusting them.
 *
 * [PortFinder] is the subject only because it is an `object` with a cheap pure
 * method. Nothing here is about ports.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StaticMockCleanupTest {

    private companion object {
        /** A value the real implementation cannot return, so the two are never confused. */
        const val IMPOSSIBLE_PORT = -4242
    }

    @Test
    @Order(1)
    fun `a process-wide mock is installed and deliberately not torn down here`() {
        // No @AfterEach, no unmockkAll, on purpose: this test exists to leave a mock
        // standing so the next one can prove something removed it. Under the old
        // source-scanning guard this very file would have been an offender.
        mockkObject(PortFinder)
        every { PortFinder.isPortAvailable(any()) } returns false

        assertNotEquals(
            IMPOSSIBLE_PORT, PortFinder.findAvailablePort(),
            "sanity: the mock is installed and the object still answers",
        )
    }

    @Test
    @Order(2)
    fun `the mock is gone by the time the next test runs`() {
        // If UnmockkAllExtension is not registered, PortFinder is still mocked here
        // and isPortAvailable answers false for everything — which is exactly the
        // cross-class contamination this guards against, observed within one class
        // so it does not depend on how the runner orders classes.
        val stillMocked = !PortFinder.isPortAvailable(0)
        org.junit.jupiter.api.Assertions.assertFalse(
            stillMocked,
            "PortFinder is still mocked from the previous test, so nothing removed it. " +
                "Check that UnmockkAllExtension is listed in " +
                "src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension " +
                "and that junit.jupiter.extensions.autodetection.enabled is true in " +
                "junit-platform.properties.",
        )
    }
}
