package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [deviceFolderTarget], the path a synced device folder is navigated to.
 *
 * The named path, `saf-mirrors/by-name/<hash>/<name>`, is what makes the Explorer and
 * the title show the folder's name rather than its copy's hash. The workbench keeps open
 * editors, unsaved changes and terminals per path, though, so every case here is about
 * the one cost a wrong answer has: a folder moved between its two spellings comes up
 * without what the user left in it, and nothing says where that went.
 */
class DeviceFolderTargetTest {

    private val root = "/data/user/0/com.vscodroid/files/saf-mirrors"
    private val hash = "8e440ff38c8e"
    private val copy = "$root/$hash"
    private val named = "$root/by-name/$hash/recipes"

    private fun target(
        target: String = copy,
        namedPath: String? = named,
        byName: Boolean = true,
        openNow: String? = null,
    ) = deviceFolderTarget(target, copy, namedPath, byName, openNow)

    @Test
    fun `a copy that has no state under its hash opens by its name`() {
        assertEquals(named, target())
        assertEquals(
            "$named/app.code-workspace", target(target = "$copy/app.code-workspace"),
            "a workspace file in the folder kept the hash, and its folders are named after it",
        )
    }

    /**
     * A copy made before named paths, opened by its hash, may hold unsaved changes
     * there. It stays on its hash, where the bundled extension offers the move once it
     * can see nothing would be left behind.
     */
    @Test
    fun `a copy that may have state under its hash stays there`() {
        assertEquals(copy, target(byName = false))
        assertEquals("$copy/app.code-workspace", target(target = "$copy/app.code-workspace", byName = false))
    }

    @Test
    fun `with no named path the hash is used`() {
        assertEquals(copy, target(namedPath = null))
    }

    /** Picking the open folder again pulls fresh content, and must keep its editors. */
    @Test
    fun `the folder on screen keeps the spelling it is open under`() {
        assertEquals(copy, target(openNow = copy))
        assertEquals(copy, target(openNow = "$copy/src"), "a subfolder of the copy is the copy")
        assertEquals(
            "$root/by-name/$hash/old", target(openNow = "$root/by-name/$hash/old/src"),
            "a folder renamed on the device moved off the name it is open under",
        )
        assertEquals("$root/by-name/$hash/old", target(openNow = "$root/by-name/$hash/old", byName = false))
    }

    @Test
    fun `another folder on screen changes nothing`() {
        listOf(
            "$root/0f0f0f0f0f0f",
            "$root/by-name/0f0f0f0f0f0f/other",
            "${copy}0",
            "/data/user/0/com.vscodroid/files/home/projects/site",
        ).forEach {
            assertEquals(named, target(openNow = it), "$it was taken for this folder")
            assertEquals(copy, target(openNow = it, byName = false))
        }
    }

    // -- The wiring, read off the source because an Activity cannot be built here --

    private val source by lazy { SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt") }

    private fun body(declaration: String) =
        SourceScan.withoutComments(SourceScan.body(source, declaration))

    @Test
    fun `a synced folder is navigated to through this decision`() {
        val open = body("private fun openSafFolder(")
        listOf(
            "deviceFolderTarget(",
            "namedPath = safManager.namedPathFor(mirrorDir, displayName)?.path",
            "byName = copyIsNew || safManager.wasShownByName(mirrorDir)",
            "openNow = openWorkspaceFolder",
            "!safManager.getMirrorDir(uri).exists()",
            "navigateToFolder(serverPort, target)",
            "rememberWorkspaceFolder(target)",
        ).forEach {
            assertTrue(open.contains(it)) {
                "openSafFolder no longer carries `$it`, so a picked folder either keeps " +
                    "the hash in the Explorer or is moved off the path its editors are under"
            }
        }
        assertTrue(open.indexOf("getMirrorDir(uri).exists()") < open.indexOf("safManager.syncToLocal(")) {
            "whether the copy is new is asked after the sync has made it, so every copy reads as old"
        }
    }

    @Test
    fun `every page on a copy is noted`() {
        assertTrue(body("private fun adoptWorkbenchFolder(").contains("safManager.noteOpened(folderPath, it)")) {
            "a page on a copy is not noted, so a page on the hash has no named path to be " +
                "moved to and a page by name is never recorded as one"
        }
    }
}
