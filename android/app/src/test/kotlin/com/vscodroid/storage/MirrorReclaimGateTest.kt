package com.vscodroid.storage

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests for [SafStorageManager.mayReclaim], the question the launch-time
 * reclamation pass asks before deleting a mirror whose permission has lapsed.
 *
 * Reclaiming is normally safe, and that is worth saying first: a mirror is a
 * copy, the device folder is the original, and a lapsed grant means this app can
 * never sync that folder again, so the copy is unreachable disk. The exception
 * is the app's own record that a write never reached the original. A write-back
 * that gave up after two failures, or one refused with a SecurityException,
 * leaves the mirror holding the only version of that file. A withdrawn
 * permission produces exactly that refusal, and is also what puts the mirror in
 * front of this pass, so the two arrive together rather than independently.
 *
 * The pass runs on a detached thread at launch with no screen, so the only
 * outcome it can produce is irreversible. That is why the default here is to
 * keep rather than to ask.
 */
class MirrorReclaimGateTest {

    private val root = "/data/user/0/com.vscodroid/files/saf-mirrors"
    private val sep = File.separator

    @Test
    fun `a mirror with nothing stranded under it is reclaimable`() {
        assertTrue(SafStorageManager.mayReclaim("abc123", emptySet(), root))
    }

    @Test
    fun `a mirror holding a write that never reached the device is kept`() {
        val stranded = setOf("$root${sep}abc123${sep}src${sep}main.kt")

        assertFalse(
            SafStorageManager.mayReclaim("abc123", stranded, root),
            "deleting this is not reclaiming a copy, it is deleting the only copy",
        )
    }

    @Test
    fun `another mirror's stranded write does not protect this one`() {
        val stranded = setOf("$root${sep}other9${sep}notes.md")

        assertTrue(
            SafStorageManager.mayReclaim("abc123", stranded, root),
            "unreachable disk was kept because an unrelated folder had a stranded write",
        )
    }

    /**
     * The separator is load-bearing rather than tidiness. Mirror names are a
     * hash prefix, so one being a prefix of another is ordinary, and a bare
     * `startsWith` would let `abc123def` protect `abc123` for ever.
     */
    @Test
    fun `a mirror whose name is a prefix of another is judged on its own`() {
        val stranded = setOf("$root${sep}abc123def${sep}notes.md")

        assertTrue(
            SafStorageManager.mayReclaim("abc123", stranded, root),
            "a longer mirror name protected a shorter one that shares its prefix",
        )
    }

    @Test
    fun `one stranded write is enough, because the pass deletes whole trees`() {
        val stranded = setOf(
            "$root${sep}abc123${sep}deep${sep}nested${sep}only-copy.bin",
        )

        assertFalse(SafStorageManager.mayReclaim("abc123", stranded, root))
    }
}
