package com.vscodroid

import android.net.Uri
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File as JavaFile

/**
 * That a multi-root workspace survives the round trip through the URL.
 *
 * The workbench opens a `.code-workspace` by navigating its own WebView to
 * `?workspace=<file>`, which is a client-side contract the shipped bundle
 * already implements in full. The Kotlin shell only ever spoke `?folder=`, in
 * both directions, and the cost of that was not cosmetic:
 *
 *  - `folderFromUrl` answered null for a workspace URL, so the page-loaded
 *    callback ran neither of its consumers. `adoptWorkbenchFolder` is one of
 *    them, and it is what starts the write-back watcher for a device folder the
 *    workbench reached on its own. A workspace opened out of a mirror was
 *    therefore served read-write with nothing syncing it, and every edit stayed
 *    in the mirror and never reached the user's own files. Measured on an
 *    emulator: the same edit reaches the device folder when it is opened as a
 *    folder and does not when it is opened as a workspace.
 *  - The same null fell through to the remembered folder and then to the default
 *    projects directory, so the workspace was not reopened on the next launch.
 *
 * The two are one defect: nothing could name a workspace, so nothing could
 * remember one or recognise one. These pin the naming, which is what the rest
 * hangs off. `File` and `Uri` are unavailable in a plain JVM test, which is why
 * the decision is a function over strings the way [rememberedFolderToReopen] is.
 */
class WorkspaceUrlRoundTripTest {

    private val token = "0123456789abcdef"
    private val workspace = "/data/user/0/com.vscodroid/files/saf-mirrors/92f67f007ab2/proj.code-workspace"
    private val folder = "/data/user/0/com.vscodroid/files/projects/site"

    @BeforeEach
    fun setUp() {
        // Uri is a stub under the unit-test android.jar and throws otherwise.
        // Identity encoding is faithful enough for these paths, which carry no
        // character the encoder would touch.
        mockkStatic(Uri::class)
        every { Uri.encode(any()) } answers { firstArg<String>() }
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a workspace file is named by the workspace parameter`() {
        assertTrue(
            workbenchUrl(13337, workspace, token, isFile = { true }).contains("?workspace="),
            "the shipped workbench reads `workspace` and `folder` as different things: " +
                "`folder` builds a folderUri from whatever path it is handed with no " +
                "file test, so a .code-workspace sent as `folder` opens as a single-root " +
                "folder whose root is a file, not as the workspace it is",
        )
    }

    @Test
    fun `an ordinary folder is still named by the folder parameter`() {
        assertTrue(
            workbenchUrl(13337, folder, token).contains("?folder="),
            "every navigation that is not a workspace has to be untouched by this, and " +
                "it is the overwhelmingly common one",
        )
    }

    @Test
    fun `a directory named like a workspace is navigated to as a folder`() {
        assertTrue(
            workbenchUrl(13337, "$mirror/odd.code-workspace", token, isFile = { false })
                .contains("?folder="),
            "the workbench answers a workspace it cannot read with an empty window and " +
                "no message, and a directory is one of those. `folderOpenTarget` already " +
                "excludes it where the picker chooses a target; the URL builder is where " +
                "the choice is actually made, and it decided on the name alone. Nothing " +
                "corrected it afterwards either: `folderFromUrl` answers null for a " +
                "`workspace` URL naming a directory, so the folder was neither reopened " +
                "nor forgotten",
        )
    }

    @Test
    fun `the connection token still rides along on a workspace URL`() {
        assertTrue(
            workbenchUrl(13337, workspace, token, isFile = { true }).contains("&tkn=$token"),
            "the server answers a bare Forbidden without it, and a workspace URL is " +
                "reached by the same navigation as a folder one",
        )
    }

    @Test
    fun `a folder parameter naming something that is not a directory is refused`() {
        assertNull(
            workbenchTarget(
                folder = workspace,
                workspace = null,
                isDirectory = { false },
                isFile = { true },
            ),
            "the directory test on `folder` is what keeps a dead path from pinning the " +
                "WebView, and it predates this change. A workspace file arriving as " +
                "`folder` is a URL nothing here builds any more, so it stays refused",
        )
    }

    @Test
    fun `a workspace parameter naming an existing file is taken`() {
        assertEquals(
            workspace,
            workbenchTarget(
                folder = null,
                workspace = workspace,
                isDirectory = { false },
                isFile = { true },
            ),
            "this is the answer the page-loaded callback needs in order to run at all: " +
                "without it adoptWorkbenchFolder never sees the mirror the workspace " +
                "lives in and no watcher is started for it",
        )
    }

    @Test
    fun `a workspace file that is gone is not taken`() {
        assertNull(
            workbenchTarget(
                folder = null,
                workspace = workspace,
                isDirectory = { false },
                isFile = { false },
            ),
            "the same reason the folder branch stats what it reads: a workspace deleted " +
                "while the app was away would otherwise be remembered and reopened onto " +
                "a path that is not there",
        )
    }

    @Test
    fun `the folder parameter wins when a URL somehow carries both`() {
        assertEquals(
            folder,
            workbenchTarget(
                folder = folder,
                workspace = workspace,
                isDirectory = { true },
                isFile = { true },
            ),
            "not a URL anything builds, and the ordering is stated rather than left to " +
                "whichever branch happens to be written first",
        )
    }

    @Test
    fun `the directory in force for a workspace is the one holding it`() {
        assertEquals(
            "/data/user/0/com.vscodroid/files/saf-mirrors/92f67f007ab2",
            workspaceDirectoryInForce(workspace, isFile = { true }),
            "the resource interceptor publishes this as a root, and a root that is a " +
                "single file matches only itself: every resource in the workspace would " +
                "be refused for a workspace held outside the statically published trees",
        )
    }

    /**
     * The one case that exercises the DEFAULT predicates against a real filesystem.
     *
     * Every other case here passes its own `isFile`, which is what makes them
     * readable, and is also a hole: the defaults on `workbenchUrl` and
     * `workspaceDirectoryInForce` are what production actually runs, and nothing
     * else reaches them. Weakening one to `{ true }` restores the defect this file
     * exists to refuse, with every other case in the suite still green.
     *
     * A temporary directory rather than a fixture path, because the whole point is
     * that the real `File.isFile` answers, and it answers false for every
     * `/data/user/0/...` path on a JVM host.
     */
    @Test
    fun `the default file test reads the real filesystem`(@TempDir dir: JavaFile) {
        val workspaceFile = JavaFile(dir, "real.code-workspace").apply { writeText("{}") }
        val lookalikeDirectory = JavaFile(dir, "odd.code-workspace").apply { mkdir() }

        assertTrue(
            workbenchUrl(13337, workspaceFile.path, token).contains("?workspace="),
            "a real workspace file must still be named by the workspace parameter when " +
                "nobody passes a predicate, which is how production calls it",
        )
        assertTrue(
            workbenchUrl(13337, lookalikeDirectory.path, token).contains("?folder="),
            "and a real directory spelled the same way must not be, which is the whole " +
                "defect: the editor answers a workspace it cannot read with an empty " +
                "window and no message",
        )
        assertEquals(
            dir.path,
            workspaceDirectoryInForce(workspaceFile.path),
            "the published root for a real workspace file is the directory holding it",
        )
        assertEquals(
            lookalikeDirectory.path,
            workspaceDirectoryInForce(lookalikeDirectory.path),
            "and for a real directory it is the directory itself, not its parent, which " +
                "is what kept every sibling of the opened folder readable",
        )
    }

    /**
     * Where the reduction happens, which no pure function can pin.
     *
     * `workspaceDirectoryInForce` needs a `stat`, and the resource interceptor
     * runs once per request, so the reduction was moved to the one navigation-time
     * writer and both suppliers hand the result. Nothing else notices if that
     * moves back: the interceptor would still be handed a path, the roots would
     * still be a list, and every case above would stay green while a workspace
     * session paid a `stat` per request and a momentarily absent file silently
     * unpublished the root.
     *
     * Read from the source because the writer is an Activity method and the
     * suppliers are lambdas closing over its fields, neither of which a plain JVM
     * test can drive. See [SourceScan] for the ceiling that reading carries.
     */
    @Test
    fun `the workspace root is reduced once at navigation, not per request`() {
        val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")
        val writer = SourceScan.withoutComments(
            SourceScan.body(source, "private fun rememberWorkspaceFolder("),
        )

        assertTrue(writer.contains("openWorkspaceRoot = workspaceDirectoryInForce(")) {
            "rememberWorkspaceFolder no longer derives the published root, so either " +
                "nothing does and a workspace file publishes only itself, or the " +
                "interceptor went back to reducing per request"
        }

        val body = SourceScan.withoutComments(source)
        assertTrue(body.contains("openFolder = { openWorkspaceRoot }")) {
            "the webview client is handed the unreduced folder again"
        }
        assertTrue(body.contains("{ self.get()?.openWorkspaceRoot }")) {
            "the service worker is handed the unreduced folder again, and it is the " +
                "entry point that does not go through the client"
        }
    }

    @Test
    fun `the directory in force for a directory named like a workspace is itself`() {
        assertEquals(
            "$mirror/odd.code-workspace",
            workspaceDirectoryInForce("$mirror/odd.code-workspace", isFile = { false }),
            "reducing to the parent is right for a workspace FILE, whose siblings are " +
                "the workspace's own content. For a directory it published a root one " +
                "level too high, covering every sibling of the folder the user opened",
        )
    }

    @Test
    fun `the directory in force for an ordinary folder is itself`() {
        assertEquals(
            folder,
            workspaceDirectoryInForce(folder),
            "the common path has to pass through unchanged",
        )
    }

    /**
     * A closed folder is the third thing the workbench can be showing, and the
     * folder chain cannot name it: `folderFromUrl` answers a path or nothing, and
     * "no folder, deliberately" is not a path. So a renderer crash over a closed
     * folder fell through to the remembered folder and put the user back in the
     * workspace they had just closed.
     *
     * Restoring the URL rather than rebuilding one is what makes this expressible
     * at all, and it is the same reasoning `handleResumeFromBackground` already
     * uses when it calls `reload()` instead of rebuilding.
     */
    @Test
    fun `a closed folder is restored as a closed folder`() {
        assertEquals(
            "http://127.0.0.1:13337/?ew=true",
            emptyWindowUrl("http://127.0.0.1:13337/?ew=true", 13337),
        )
    }

    @Test
    fun `an ordinary workbench URL is not treated as a closed folder`() {
        assertNull(
            emptyWindowUrl("http://127.0.0.1:13337/?folder=/x/y", 13337),
            "the folder chain owns this one, and it rebuilds the URL with a fresh " +
                "token rather than reloading a stripped one",
        )
    }

    @Test
    fun `a workspace URL is not treated as a closed folder`() {
        assertNull(
            emptyWindowUrl("http://127.0.0.1:13337/?workspace=/x/y.code-workspace", 13337),
        )
    }

    @Test
    fun `a page on another port is refused`() {
        assertNull(
            emptyWindowUrl("http://127.0.0.1:9999/?ew=true", 13337),
            "the port is the whole of what makes a URL ours, and the placeholder and " +
                "error pages this is asked about are neither",
        )
    }

    @Test
    fun `the data placeholder is refused`() {
        assertNull(
            emptyWindowUrl("data:text/html,<html></html>", 13337),
            "the WebView holds this before the first navigation and after a crash, " +
                "which is exactly when this is asked",
        )
    }

    private val mirror = "/data/user/0/com.vscodroid/files/saf-mirrors/92f67f007ab2"

    /**
     * The picker grants a directory, because `ACTION_OPEN_DOCUMENT_TREE` returns
     * nothing else, so a workspace can only ever be reached through the folder
     * holding it. Nothing connected the two: the folder opened as a folder, and
     * the workspace inside it was reachable only by knowing to find the file in
     * the explorer and press a button on it. Desktop VS Code offers this itself,
     * and the browser workbench does not carry that code, so the shell is the
     * only side that can.
     */
    @Test
    fun `a granted folder holding one workspace opens as that workspace`() {
        assertEquals(
            "$mirror/proj.code-workspace",
            folderOpenTarget(
                mirror,
                names = listOf("src", "proj.code-workspace", "README.md"),
                isFile = { true },
            ),
            "this is the whole of what the issue asks for: a workspace opened from " +
                "the Android picker, which can only grant the folder around it",
        )
    }

    @Test
    fun `a granted folder with no workspace opens as a folder`() {
        assertEquals(
            mirror,
            folderOpenTarget(mirror, names = listOf("src", "README.md"), isFile = { true }),
            "the overwhelmingly common case, and it must not change",
        )
    }

    @Test
    fun `a granted folder holding two workspaces opens as a folder`() {
        assertEquals(
            mirror,
            folderOpenTarget(
                mirror,
                names = listOf("a.code-workspace", "b.code-workspace"),
                isFile = { true },
            ),
            "picking one of them would be a guess, and the wrong guess is worse than " +
                "the folder the user actually chose: the explorer still shows both, so " +
                "opening either is one tap away",
        )
    }

    @Test
    fun `a directory named like a workspace is not opened as one`() {
        assertEquals(
            mirror,
            folderOpenTarget(
                mirror,
                names = listOf("odd.code-workspace"),
                isFile = { false },
            ),
            "a `.code-workspace` that is a directory would navigate the workbench to a " +
                "workspace URL it cannot resolve, and the workbench answers an " +
                "unreadable workspace with an empty window and no message",
        )
    }
}
