package com.vscodroid.service

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * That a spawn nobody could perform still says so in `server.log`.
 *
 * Every other line in that file comes from a process: the start summary is
 * appended after the spawn returns, and `startOutputReader` drains a pipe that
 * exists only once there is a child on the other end of it. So the one failure
 * where no process was ever created was also the one failure that wrote nothing
 * at all, and an empty `server.log` read exactly like a server that had started
 * fine and had nothing to say. That is the pair a reader most needs to tell
 * apart, and it is the pair the gave-up page now puts on screen.
 *
 * Read out of the source because the seam is not reachable: `startServer` needs a
 * `Context`, a real `ProcessBuilder` and a device path, and the branch under test
 * is its catch. What can be checked is that the catch still writes, which is the
 * part that would be dropped silently by anyone tidying the block.
 */
class SpawnFailureLoggedTest {

    @Test
    fun `the refused spawn is written to the server log, not only to logcat`() {
        val startServer = SourceScan.withoutComments(
            SourceScan.body(
                SourceScan.read("src/main/kotlin/com/vscodroid/service/ProcessManager.kt"),
                "fun startServer(",
            )
        )

        val logged = startServer.indexOf("Logger.e(tag, \"Failed to start server\"")
        assertTrue(logged >= 0) {
            "startServer no longer logs a refused spawn; this case is measuring nothing"
        }

        // After the logcat line, which is where the catch is. Searching the whole
        // body would also match the start summary that is appended on success,
        // and that one is written whether or not this branch exists.
        assertTrue(startServer.indexOf("serverLog.append", logged) > logged) {
            "a refused spawn reaches logcat and nothing else. Release builds keep " +
                "Logger.e, but the bug report and the gave-up page both read " +
                "server.log, so the failure that produces no process would produce " +
                "no evidence either."
        }
    }
}
