package com.vscodroid.webview

import com.vscodroid.rememberedFolderToReopen
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * That an Activity rebuilt over a server that never stopped comes back to the
 * workspace the user had open.
 *
 * The open folder had exactly one record and it lived in the WebView: the field
 * the resource interceptor reads dies with the Activity, and the URL it is
 * derived from dies with the WebView. The server does not. It is started as well
 * as bound, so it keeps serving the workspace while the Activity is destroyed and
 * built again, and the replacement WebView carries only the `data:` placeholder,
 * for which `folderFromUrl` answers null by design. The chain then fell through
 * to the default projects directory, and the user was silently moved out of their
 * own workspace against a server that still had it open.
 *
 * The precedence is the delicate part and is pinned in the source rather than
 * argued. "The WebView URL is the only truthful record of the open workspace" is
 * a rule about reading the current folder during a session, and it still holds:
 * the remembered folder is written *from* that same URL and is read only where
 * there is no URL to read at all. Preferring it over the URL would pin the
 * WebView to a stale folder and break the rule outright, which is what the
 * ordering case exists to catch.
 *
 * The decision itself is a rule over one string, so it is tested as one. `File`,
 * `Uri` and a `Context` are not available in a plain JVM test, which is the same
 * reason `shouldRestorePreviousWatcher` is a function rather than a branch.
 */
class WorkspaceMemoryTest {

    private val mirrorsRoot = "/data/user/0/com.vscodroid/files/saf-mirrors"

    @Test
    fun `nothing remembered reopens nothing`() {
        assertNull(
            rememberedFolderToReopen(null, mirrorsRoot, { true }, { true }),
            "an install that has never opened a folder has to fall through to the " +
                "default, which is what it does today",
        )
    }

    /**
     * Nothing but an absolute path is ever reopened.
     *
     * The record holds three states now, and one of them means "the user closed
     * the folder". Which value that is does not matter here; that no non-path
     * value is ever opened does. The empty string is the case worth naming,
     * because it is the obvious sentinel and it is a trap: `File("").exists()`
     * answers **true** and resolves to the process working directory, so a
     * reader that only asks `exists` opens that directory and publishes it as a
     * served resource root. Measured on the JDK this suite runs on, which is
     * where the sentinel was changed from "" to something that cannot be a path.
     *
     * The real `exists` is passed on purpose: a stubbed one would make both
     * cases pass whatever the guard does.
     */
    @Test
    fun `nothing but an absolute path is reopened`() {
        val real = { it: String -> java.io.File(it).exists() }

        assertNull(
            rememberedFolderToReopen("", mirrorsRoot, exists = real, { true }),
            "the empty string resolves to the working directory and would be opened " +
                "as a folder, and then served as a resource root",
        )
        assertNull(
            rememberedFolderToReopen("vscodroid:closed", mirrorsRoot, exists = real, { true }),
            "the closed-folder sentinel must never name something to open. An older " +
                "build reading a preferences file this one wrote takes this route",
        )
        assertNull(
            rememberedFolderToReopen("build", mirrorsRoot, exists = real, { true }),
            "a relative name that happens to exist beside the process is not the " +
                "folder anyone opened. This one exists in the module directory the " +
                "tests run from, which is what makes it worth asserting",
        )
    }

    @Test
    fun `a folder that is gone is not reopened`() {
        assertNull(
            rememberedFolderToReopen(
                "/data/user/0/com.vscodroid/files/projects/site",
                mirrorsRoot,
                exists = { false },
                mirrorIsGranted = { true },
            ),
            "a deleted folder or unmounted storage would pin the WebView to a dead " +
                "path, which is the same reason folderFromUrl stats what it reads",
        )
    }

    @Test
    fun `an ordinary project folder needs no grant`() {
        val path = "/data/user/0/com.vscodroid/files/projects/site"

        assertEquals(
            path,
            rememberedFolderToReopen(
                path,
                mirrorsRoot,
                exists = { true },
                mirrorIsGranted = { false },
            ),
            "a folder that is not a device-folder copy needs no SAF grant, so asking " +
                "for one would refuse every ordinary project the app itself owns. That " +
                "asymmetry is the whole reason the mirror test is asked separately",
        )
    }

    @Test
    fun `a device folder copy is reopened only while its grant stands`() {
        val mirror = "$mirrorsRoot/abc123def456/work"

        assertEquals(
            mirror,
            rememberedFolderToReopen(mirror, mirrorsRoot, { true }, { true }),
        )
        assertNull(
            rememberedFolderToReopen(mirror, mirrorsRoot, { true }, { false }),
            "a mirror whose grant has lapsed is worse than useless: nothing syncs it, " +
                "the launch reclaim pass is entitled to delete it out from under the " +
                "editor, and the user goes on editing believing their work is reaching " +
                "the device",
        )
    }

    @Test
    fun `a sibling of the mirrors root is not mistaken for a mirror`() {
        val sibling = "${mirrorsRoot}-old/x"

        assertEquals(
            sibling,
            rememberedFolderToReopen(sibling, mirrorsRoot, { true }, { false }),
            "the separator rule is SafStorageManager.mirrorNameFor's and is load-bearing " +
                "here for the same reason it is there: a bare startsWith names the wrong " +
                "folder, and here that costs an ordinary project folder being refused",
        )
    }

    private val source = File("src/main/kotlin/com/vscodroid/MainActivity.kt")

    /** The body of a `private fun name(` declaration, to its closing brace. */
    private fun body(name: String): String {
        val text = source.readText()
        val start = text.indexOf("private fun $name(")
        assertTrue(start >= 0) {
            "$name is gone from MainActivity.kt, so this test is measuring nothing. " +
                "If it moved or was renamed, point this at the new site rather than " +
                "deleting it."
        }
        val open = text.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < text.length) {
            if (text[i] == '{') depth += 1
            if (text[i] == '}') {
                depth -= 1
                if (depth == 0) return text.substring(open, i + 1)
            }
            i += 1
        }
        throw AssertionError("Could not find the end of $name in MainActivity.kt")
    }

    /** Comments removed, so prose about the rule cannot satisfy a search for it. */
    private fun withoutComments(text: String): String {
        var inBlock = false
        return text.lines().joinToString("\n") { raw ->
            var line = raw
            if (inBlock) {
                val close = line.indexOf("*/")
                if (close < 0) return@joinToString ""
                inBlock = false
                line = line.substring(close + 2)
            }
            while (line.trimStart().startsWith("/*")) {
                val open = line.indexOf("/*")
                val close = line.indexOf("*/", open + 2)
                if (close < 0) {
                    inBlock = true
                    return@joinToString line.substring(0, open)
                }
                line = line.substring(0, open) + line.substring(close + 2)
            }
            val marker = line.indexOf("//")
            if (marker >= 0) line.substring(0, marker) else line
        }
    }

    @Test
    fun `the URL still outranks the remembered folder`() {
        val load = withoutComments(body("loadVSCode"))

        val fromUrl = load.indexOf("folderFromUrl(")
        val remembered = load.indexOf("rememberedWorkspaceFolder()")
        val default = load.indexOf("ensureProjectsDir()")

        assertTrue(fromUrl >= 0 && remembered >= 0 && default >= 0) {
            "loadVSCode no longer resolves the folder through all three sources, so the " +
                "ordering below is measuring nothing. Found:\n$load"
        }
        assertTrue(fromUrl < remembered) {
            "the remembered folder is read before the URL, which pins the WebView to a " +
                "stale folder: the workbench switches folders by navigating itself, so " +
                "while there is a page its URL is the only truthful record of what is " +
                "open. The remembered one answers only where there is no URL to ask."
        }
        assertTrue(remembered < default) {
            "the default projects directory is reached before the remembered folder, so " +
                "the remembered one can never be used and an Activity rebuilt over a " +
                "live server still moves the user out of their workspace."
        }

        // The fourth source, and the only one that answers "nothing, on purpose".
        // It sits between the other two for a reason each way: a folder still on
        // screen outranks a close the user has since navigated away from, and a
        // close outranks a folder that was remembered before it. Read the other
        // way round, a relaunch after Close Workspace reopens the folder that was
        // just closed, which is the whole of what this branch exists to stop.
        val closed = load.indexOf("workspaceWasClosed()")
        assertTrue(closed >= 0) {
            "loadVSCode no longer asks whether the user closed the folder, so closing " +
                "it survives only as long as the WebView does. Found:\n$load"
        }
        assertTrue(fromUrl < closed && closed < remembered) {
            "the closed-folder test is out of order. Ahead of the URL it would override " +
                "a folder that is open on screen; behind the remembered folder it can " +
                "never be reached, and the closed folder is reopened on every launch."
        }
    }

    @Test
    fun `every site that records the open folder records it for the next launch`() {
        val assignments = withoutComments(source.readText())
            .lines()
            .filter { Regex("""^\s*openWorkspaceFolder = """).containsMatchIn(it) }

        assertEquals(1, assignments.size) {
            "the open folder is assigned in more than one place, so a site that set the " +
                "field without the preference would drift from it and the workspace " +
                "would be forgotten for whichever navigation went through that site. " +
                "Found:\n" + assignments.joinToString("\n") { "  ${it.trim()}" }
        }
        assertTrue(withoutComments(body("rememberWorkspaceFolder")).contains(
            assignments.single().trim()
        )) {
            "the one assignment is not the one inside rememberWorkspaceFolder, so the " +
                "two records are set apart from each other again."
        }
    }
}
