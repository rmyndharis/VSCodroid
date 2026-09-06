package com.vscodroid.setup

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Waits for the launch pass a manager submitted, not for the one effect a case
 * asserts on.
 *
 * [ToolchainManager.repairInstalledToolchains] submits a single block to the
 * manager's `ioExecutor` and returns. That block ends with
 * `regenerateDerivedFiles`, which writes the env file, the exec table and the
 * trampoline symlinks into `filesDir`, and every case that calls the pass puts
 * `filesDir` in a JUnit `@TempDir`. Waiting for an earlier step therefore leaves
 * those writes running after the test method returns, racing JUnit's deletion of
 * the directory they are inside. The failure is a
 * `TempDirDeletionStrategy$DeletionException` on a case whose own assertions all
 * passed, it fails the whole task, and it is rare enough to read as noise: it
 * appeared once on a CI runner having passed on the same commit's pull request
 * and on two local runs of the full suite.
 *
 * Measured rather than argued. With the effect poll spinning instead of sleeping
 * 20 ms, so the chmod that `ToolchainInterruptedInstallTest` waits on is seen at
 * the earliest instant it exists, `toolchain-env.sh` was still absent at the end
 * of the case on three runs out of three. The sleep is what usually hides this on
 * a workstation; a loaded runner is what does not.
 *
 * A barrier rather than a longer wait: `ioExecutor` is one thread over a FIFO
 * queue ([toolchainIoExecutor]), so a task submitted after the pass runs only
 * once the pass has returned. That is exact and needs no number. The timeout is
 * only so a pass that deadlocks fails as a test rather than hanging the suite.
 *
 * Reflection because `ioExecutor` is private and should stay so: nothing in
 * production has any business reaching it, and the alternative is a seam that
 * exists for tests alone. The two callers share this one copy rather than
 * carrying a copy each, which is the shape that lets one of them quietly stop
 * matching.
 */
internal fun awaitLaunchPass(manager: ToolchainManager) {
    val executor = ToolchainManager::class.java
        .getDeclaredField("ioExecutor")
        .apply { isAccessible = true }
        .get(manager) as ExecutorService
    executor.submit { }.get(30, TimeUnit.SECONDS)
}
