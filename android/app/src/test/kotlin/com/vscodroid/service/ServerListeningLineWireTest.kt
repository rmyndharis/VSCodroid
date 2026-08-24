package com.vscodroid.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That the line readiness waits for is the line the packaged editor server prints,
 * and that it can reach the process reading it.
 *
 * `ProcessManager.probeReadiness` refuses to call a spawned start ready until the
 * output reader has seen [SERVER_LISTENING_LINE], which `server-main.js` prints
 * from its listen callback. The text is upstream's, not ours, so a VS Code bump can
 * change it, and the Kotlin side would then wait for a sentence nobody prints:
 * every spawned start alive and bound, the poll giving up, the slow-start notice
 * shown, and the editor never loaded. `HeldPortReadinessTest` cannot see that,
 * because its child prints whatever the fixture tells it to.
 *
 * The packaged tree is fetched rather than tracked, so the first case is skipped
 * where it is absent, which is the CI unit-test job. It runs wherever the tree has
 * been fetched, which is every local build and every device run. `assets/server.js`
 * is tracked, so the second case always runs.
 */
class ServerListeningLineWireTest {

    private val serverMain = File("src/main/assets/vscode-reh/out/server-main.js")
    private val bootstrap = File("src/main/assets/server.js")

    @Test
    fun `the packaged editor server prints the line readiness waits for`() {
        assumeTrue(serverMain.isFile) {
            "${serverMain.path} has not been fetched here, so the line cannot be checked " +
                "against it; run scripts/fetch-vscode-oss.sh and rerun this case"
        }
        assertTrue(serverMain.readText().contains(SERVER_LISTENING_LINE)) {
            "server-main.js no longer prints \"$SERVER_LISTENING_LINE\", so no spawned " +
                "start can ever become ready: ProcessManager waits for that line before " +
                "it trusts the port. Update SERVER_LISTENING_LINE to what the listen " +
                "callback prints now."
        }
    }

    @Test
    fun `the bootstrap forks the editor server with its stdio inherited`() {
        // The line is printed by the forked child, and it reaches ProcessManager's
        // output reader only because the child's stdout is the bootstrap's, which
        // is the pipe the reader holds. A fork with piped or ignored stdio would
        // keep the child's output inside server.js, and readiness would wait for a
        // line that is being printed into nowhere.
        assertTrue(bootstrap.isFile) { "${bootstrap.path} is missing; the wire has no other guard" }
        assertTrue(Regex("""stdio:\s*['"]inherit['"]""").containsMatchIn(bootstrap.readText())) {
            "assets/server.js no longer forks the editor server with stdio: 'inherit', " +
                "so its listening line never reaches ProcessManager.startOutputReader"
        }
    }
}
