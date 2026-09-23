package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What has to be true of an editor launched from outside the launcher.
 *
 * `MainActivity` carries an exported, BROWSABLE VIEW filter for
 * `vscodroid://callback`, which is how a sign-in finished in the system browser
 * gets back in. That filter is also a way for anything on the device, and any
 * page the user taps a link on, to start the editor directly, past
 * `SplashActivity`. On an install whose setup has never run there is nothing
 * behind it: the service spawns the bundled Node binary and it dies on a
 * library that was never extracted, five times over, and the user is told the
 * server crashed repeatedly. The same entry skips the repairs `SplashActivity`
 * runs on every launch, so a session reached that way runs on dangling
 * `usr/bin` symlinks and on settings paths naming the previous install's native
 * library directory.
 *
 * Source and manifest reading, which is the weaker layer in this suite. It is
 * what is available: the decision is a lifecycle branch in an Activity, and no
 * plain JVM test can build one.
 */
class EditorEntryTest {

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt")
    private val manifest = File("src/main/AndroidManifest.xml")

    /** Comments dropped, so prose about the rule cannot satisfy a search for it. */
    private fun code(): List<String> {
        check(source.isFile) {
            "MainActivity.kt not found at ${source.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        return source.readLines().filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
    }

    private fun activities(): Map<String, Element> {
        check(manifest.isFile) {
            "AndroidManifest.xml not found at ${manifest.absolutePath}; this test would " +
                "otherwise pass by looking at nothing"
        }
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val nodes = document.getElementsByTagName("activity")
        val found = (0 until nodes.length)
            .map { nodes.item(it) as Element }
            .associateBy { it.getAttribute("android:name") }
        assertTrue(found.keys.containsAll(listOf(".SplashActivity", ".MainActivity"))) {
            "the manifest parse did not find the two activities, so it is reading " +
                "nothing. It found: ${found.keys}"
        }
        return found
    }

    @Test
    fun `an editor started without setup is sent back through it`() {
        val lines = code()
        val handoff = lines.indexOfFirst { it.contains("handOffToSetup()") }
        val service = lines.indexOfFirst { it.contains("startAndBindService()") }

        assertTrue(handoff >= 0) {
            "nothing checks whether setup has run before this activity starts the " +
                "server. Reached through the exported callback filter on a fresh " +
                "install, the spawn fails on a tree that was never extracted and the " +
                "user is told the server crashed repeatedly."
        }
        assertTrue(service >= 0) { "the service start is gone; this test is measuring nothing" }
        assertTrue(handoff < service) {
            "the check has to come before the server is started, or the failing spawns " +
                "happen anyway"
        }
    }

    /**
     * The one launch repair this route runs itself, and where it sits.
     *
     * The machine settings name `usr/lib/libtermux-exec.so` in `LD_PRELOAD` for
     * every terminal, and the linker aborts every exec whose preload it cannot
     * map, the shell included. Measured on API 33 and 36 emulators, 2026-09-23:
     * with that file deleted, a direct start of this activity and a launcher
     * tap that merely resumed it both left it absent, and every terminal the
     * panel opened died with CANNOT LINK EXECUTABLE until a cold launch through
     * `SplashActivity` put it back. So this route restores it too, after the
     * hand-off (a fresh install has no tree to repair into and is leaving
     * anyway) and before the service, whose first act recreates the terminals
     * that need it, inside a guard of its own so a full disk costs the repair
     * and not the launch.
     */
    @Test
    fun `the exec preload is restored on this route, between the hand-off and the server start`() {
        val lines = code()
        val handoff = lines.indexOfFirst { it.contains("handOffToSetup()") }
        val repair = lines.indexOfFirst { it.contains("ensureExecPreload()") }
        val service = lines.indexOfFirst { it.contains("startAndBindService()") }

        assertTrue(repair >= 0) {
            "ensureExecPreload() is not called from MainActivity, so an editor reached " +
                "without SplashActivity runs on whatever the last launch left under " +
                "usr/lib, and a missing interceptor kills every terminal it opens"
        }
        assertTrue(handoff >= 0 && service >= 0) { "the hand-off or the service start is gone; this test is measuring nothing" }
        assertTrue(repair in (handoff + 1) until service) {
            "the repair has to run after the hand-off and before the service starts, " +
                "or the terminals the service recreates are opened against the missing file"
        }
        assertEquals("try {", lines[repair - 1].trim()) {
            "the repair is not guarded on its own, so a full disk turning the write into " +
                "an exception crashes the editor before it draws"
        }
    }

    @Test
    fun `the hand-off asks the same question first-run setup answers`() {
        // The control for the case above, and the half more likely to rot: a
        // hand-off gated on anything other than the record setup writes would
        // either never fire or fire on every launch.
        val body = code().dropWhile { !it.contains("private fun handOffToSetup(") }
        assertTrue(body.isNotEmpty()) { "handOffToSetup is gone" }
        val decision = body.take(12)

        assertTrue(decision.any { it.contains("isFirstRun()") }) {
            "the hand-off must read FirstRunSetup.isFirstRun(), which is what " +
                "markSetupComplete() writes; found: ${decision.joinToString("\n")}"
        }
        assertTrue(decision.any { it.contains("SplashActivity::class.java") }) {
            "the hand-off must reach SplashActivity, which is the only thing that " +
                "runs setup"
        }
        assertTrue(decision.any { it.contains("intent?.data") }) {
            "the incoming intent has to travel with the hand-off, or the sign-in the " +
                "callback filter exists for is dropped on the way through setup"
        }
    }

    @Test
    fun `the callback filter stays on the editor`() {
        // The other control. Moving the filter to SplashActivity would satisfy
        // the two cases above by removing the entry point they guard, and it
        // would cost more than it saved: a callback arriving while the editor is
        // running has to reach onNewIntent on the live page, and routing every
        // one through the splash screen runs its launch repairs with a device
        // folder open. SafStorageManager.reclaimRevokedMirrors is placed there
        // precisely because nothing else guarantees no folder is open.
        val main = activities().getValue(".MainActivity")
        val filters = main.getElementsByTagName("intent-filter")
        val schemes = (0 until filters.length)
            .map { filters.item(it) as Element }
            .flatMap { filter ->
                val data = filter.getElementsByTagName("data")
                (0 until data.length).map { data.item(it) as Element }
            }
            .map { "${it.getAttribute("android:scheme")}://${it.getAttribute("android:host")}" }

        assertTrue(schemes.contains("vscodroid://callback")) {
            "MainActivity no longer answers the sign-in callback; the relay that gets " +
                "an OAuth result out of the system browser and into the workbench has " +
                "no way in. Found: $schemes"
        }
    }

    @Test
    fun `the splash screen is not finished the moment it stops being visible`() {
        // android:noHistory finishes an activity whenever it is STOPPED, which
        // the screen timing out and the user pressing Home both do, and first-run
        // extraction takes minutes with nothing holding the screen awake.
        // Finishing destroys the activity and cancels the lifecycleScope runSetup
        // is launched in. The extraction survives, because runSetupLocked's body
        // is blocking with no suspension point, so markSetupComplete runs and
        // isFirstRun goes false; what is lost is the continuation after it, which
        // is the only thing that ever offers the toolchain picker, and it is only
        // offered inside the isFirstRun branch, so it never comes back. The
        // second cost needs no timing argument: a finished activity leaves the
        // process holding no component at all, in the middle of writing out some
        // 810 MB, and there is no skip-if-present branch to make the next attempt
        // cheaper.
        val splash = activities().getValue(".SplashActivity")

        assertEquals(
            "", splash.getAttribute("android:noHistory"),
            "SplashActivity declares android:noHistory again. Nothing needs it: " +
                "launchMain() calls finish() on every route that reaches MainActivity, " +
                "and the one screen that does not reach it is a failed setup, which is " +
                "better kept than silently dismissed.",
        )
    }
}
