package com.vscodroid.service

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That a worker host is asked to shut down and then waited for, rather than cut
 * off after a fixed pause.
 *
 * The Pty Host and the Extension Host run as `worker_threads` Workers instead of
 * forked processes, which is what keeps them off Android's 32-process budget. A
 * Worker has no signal, so the adaptor asks for shutdown in-band and the host runs
 * its normal teardown. The Pty Host writes a terminal's remaining output during
 * that teardown, so what happens between the ask and the thread stopping decides
 * whether the last thing a user's command printed reaches them.
 *
 * Measured on Node 22.17.1 against a worker whose teardown takes 500 ms: the fixed
 * 200 ms pause terminated it after one of five output chunks; waiting for the exit
 * event delivered all five and finished in 525 ms; and a worker that never exits
 * is still stopped, at the stated ceiling, so the wait bounds a hang rather than
 * removing the bound.
 *
 * This reads patch text, which is the weaker kind of test, and it is here because
 * nothing else would notice. The change lives in a diff applied to the VS Code
 * source before the server is built, so no Kotlin compiles against it and the
 * suite cannot run it. `patches/fingerprints.txt` proves the patch as a whole
 * reached the packaged tree by looking for `__vsc_disconnect`, which a revert of
 * the wait would leave exactly where it is. What this buys is that a rebase onto a
 * new VS Code version cannot quietly drop the wait; what it does not buy is any
 * evidence that the patch applies, compiles, or behaves.
 */
class WorkerHostShutdownPatchTest {

    private val patch = File("../../patches/0003-ptyhost-as-worker-thread.patch")

    private fun source(): String {
        assertTrue(patch.isFile) {
            "Could not read ${patch.absolutePath}. If the patch was renamed, point this " +
                "test at it rather than deleting it: nothing else guards what it checks."
        }
        return patch.readText()
    }

    @Test
    fun `the shutdown waits for the worker to exit`() {
        val js = source()
        assertTrue(js.contains("once('exit'")) {
            "The adaptor no longer waits for the worker's exit event. Without it the " +
                "thread is stopped on a timer, and a Pty Host still writing a terminal's " +
                "last output loses whatever had not been flushed."
        }
        assertTrue(js.contains("clearTimeout(deadline)")) {
            "The exit no longer cancels the deadline, so the terminate() fires anyway " +
                "against a thread that has already gone."
        }
        assertTrue(!Regex("""terminate\(\);\s*\},\s*\d""").containsMatchIn(js)) {
            "The shutdown terminates the worker after a fixed number of milliseconds. " +
                "That is a deadline rather than a shutdown: the module is stopped whether " +
                "or not its teardown finished."
        }
    }

    @Test
    fun `the wait is bounded by a ceiling that is named`() {
        val js = source()
        assertTrue(Regex("""const WORKER_EXIT_GRACE_MS = [\d_]+;""").containsMatchIn(js)) {
            "The ceiling on the wait is gone or is no longer a named constant. A Worker " +
                "keeps its host process alive, so a module that never exits would hold the " +
                "whole server open, and a bound nobody can find is a bound nobody can weigh."
        }
        assertTrue(js.contains("setTimeout(() => { this._worker.terminate(); }, WORKER_EXIT_GRACE_MS)")) {
            "Nothing stops a worker that never answers the disconnect. The wait has to " +
                "end somewhere, and that somewhere is this timer."
        }
    }
}
