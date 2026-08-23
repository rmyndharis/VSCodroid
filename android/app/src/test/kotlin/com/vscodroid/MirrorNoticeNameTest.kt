package com.vscodroid

import android.net.Uri
import com.vscodroid.storage.SafFolderInfo
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What a device-folder notice calls the folder it is about.
 *
 * `SafSyncEngine` reports an incomplete upload with the directory the shortfall
 * was under, and for the pass that puts stranded local files onto the device that
 * directory is the mirror ROOT. Mirror roots are named after a digest of the tree
 * URI, so the Toast read "a1b2c3d4e5f6 is too large to copy out whole": a
 * sentence about files the user may hold the only copy of, naming a folder that
 * appears nowhere on their device and nowhere in the editor.
 *
 * [mirrorDisplayName] is the lookup that answers it, and it is pure so that the
 * rule can be pinned here. The wiring is one line in `MainActivity`, which no
 * plain JVM test can build.
 */
class MirrorNoticeNameTest {

    private val root = "/data/user/0/com.vscodroid/files/saf-mirrors"
    private val sep = File.separator

    private fun folder(hash: String, name: String) = SafFolderInfo(
        uri = mockk<Uri>(relaxed = true),
        displayName = name,
        lastOpened = 0L,
        mirrorPath = "$root$sep$hash",
    )

    private val recent = listOf(
        folder("a1b2c3d4e5f6", "Documents"),
        folder("999888777666", "Downloads"),
    )

    @Test
    fun `a mirror root is named after the device folder it copies`() {
        assertEquals(
            "Documents",
            mirrorDisplayName(recent, File("$root${sep}a1b2c3d4e5f6")),
            "the notice names the digest the mirror directory is called, which the user " +
                "has never seen and cannot go and look at",
        )
    }

    /**
     * And a directory inside one keeps its own name.
     *
     * `SafStorageManager.folderForOpenedPath` answers for anything under a mirror,
     * which is right for deciding what to watch and wrong here: the other caller
     * of this notice reports a shortfall under a directory somebody made in the
     * editor, and renaming it after the device folder above it would report the
     * wrong place. That is what makes this a lookup of its own rather than a reuse
     * of that one.
     */
    @Test
    fun `a directory made in the editor is not renamed after its device folder`() {
        assertEquals(
            "generated",
            mirrorDisplayName(recent, File("$root${sep}a1b2c3d4e5f6${sep}src${sep}generated")),
            "a shortfall inside one directory was reported against the whole device " +
                "folder, sending the user to look in the wrong place",
        )
    }

    /**
     * A mirror the recent list no longer knows falls back to the directory name.
     *
     * The list is pruned of revoked grants before it is handed over, and an entry
     * also goes when the folder falls off the end of MAX_RECENT. The mirror can
     * outlive both, and at that point the app genuinely does not know what the
     * folder was called; a digest is a poor name and an invented one is worse.
     */
    @Test
    fun `a mirror with no recent entry keeps the directory name`() {
        assertEquals(
            "ffeeddccbbaa",
            mirrorDisplayName(recent, File("$root${sep}ffeeddccbbaa")),
        )
        assertEquals(
            "0f0f0f0f0f0f",
            mirrorDisplayName(emptyList(), File("$root${sep}0f0f0f0f0f0f")),
        )
    }
}
