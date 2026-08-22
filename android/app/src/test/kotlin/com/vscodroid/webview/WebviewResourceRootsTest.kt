package com.vscodroid.webview

import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/** The app-private directory every path below is expressed against. */
private const val FILES = "/data/data/com.vscodroid/files"

/**
 * Keeps both sides of every comparison in the form the resolver works in.
 *
 * None of these paths exist on a build machine, so this normally hands back
 * what it was given; a machine that does have a `/data` link would otherwise
 * leave expectations and results in different forms and fail the whole class
 * for a reason that has nothing to do with the resolver.
 */
private fun canonical(path: String) = File(path).canonicalPath

/**
 * The four directories the app publishes, written out rather than taken from
 * [publishedResourceRoots], so that a change to the published set fails these
 * tests instead of moving with them.
 */
private val ROOTS = listOf(
    "$FILES/server",
    "$FILES/home/.vscodroid/extensions",
    "$FILES/home/projects",
    "$FILES/saf-mirrors",
).map(::canonical)

/** What must stay unreadable no matter which folder the user opens. */
private val SENSITIVE = listOf(
    "$FILES/home/.vscodroid/data/token",
    "$FILES/home/.ssh",
).map(::canonical)

/**
 * What [resolveWebviewResource] will and will not hand to a WebView.
 *
 * The paths here are deliberately synthetic and do not exist on the host, so
 * canonicalisation normalises them without resolving anything, which keeps
 * every assertion below a statement about the resolver rather than about the
 * machine the suite happens to run on. The cases that need a real filesystem
 * live in [WebviewResourceSymlinkTest].
 */
class WebviewResourceResolutionTest {

    /**
     * Catches: publishing the home directory as a root, or restoring a test
     * satisfied by the `/data/data/` prefix. Either serves the unencrypted
     * SSH private key to any content the WebView renders, and that key is
     * generated without a passphrase.
     */
    @Test
    fun `the ssh private key is not a webview resource`() {
        assertNull(resolveWebviewResource("$FILES/home/.ssh/id_ed25519", ROOTS))
    }

    /**
     * Catches: publishing the user-data directory in place of the extensions
     * directory inside it. The token file and the extensions that legitimately
     * are served sit two levels apart under the same parent, so the widening
     * is one path segment and looks harmless.
     */
    @Test
    fun `the connection token is not a webview resource`() {
        assertNull(resolveWebviewResource("$FILES/home/.vscodroid/data/token", ROOTS))
    }

    /**
     * The control the assertions above depend on: a resolver that answered
     * null to everything would satisfy them and prove nothing.
     *
     * Catches: a root list the resolver can never match (a trailing separator
     * left on a root, or a boundary test that demands equality).
     */
    @Test
    fun `an installed extension's own media is served`() {
        val path = "$FILES/home/.vscodroid/extensions/vscodroid.vscodroid-welcome-1.2.1/media/logo.png"
        assertEquals(canonical(path), resolveWebviewResource(path, ROOTS)?.path)
    }

    /**
     * Catches: dropping the server tree from the published set. The built-in
     * extensions live there, so markdown preview, notebook renderers and
     * Simple Browser all lose their own assets without it.
     */
    @Test
    fun `a built-in extension's media is served from the server tree`() {
        val path = "$FILES/server/vscode-reh/extensions/markdown-language-features/media/markdown.css"
        assertEquals(canonical(path), resolveWebviewResource(path, ROOTS)?.path)
    }

    /**
     * Catches: dropping the mirror directory. A folder opened through the
     * system picker is reconciled into a hash-named mirror, and that mirror is
     * the workspace as far as everything above the storage layer can tell.
     */
    @Test
    fun `a file in a mirrored workspace is served`() {
        val path = "$FILES/saf-mirrors/a1b2c3d4e5f6/docs/diagram.png"
        assertEquals(canonical(path), resolveWebviewResource(path, ROOTS)?.path)
    }

    /**
     * Catches: dropping canonicalisation. This path begins with the server
     * root as a string, so a resolver comparing the request as written serves
     * the SSH key while every other test here still passes.
     */
    @Test
    fun `a dot-dot escape out of a published root is refused`() {
        assertNull(resolveWebviewResource("$FILES/server/../home/.ssh/id_ed25519", ROOTS))
    }

    /**
     * Catches: `startsWith(root)` written without the separator. A directory
     * whose name merely begins with a published root's name is a different
     * directory, and creating one is something an extension can do.
     */
    @Test
    fun `a sibling directory sharing a name prefix is refused`() {
        assertNull(resolveWebviewResource("$FILES/server-backup/leak.css", ROOTS))
        assertNull(resolveWebviewResource("$FILES/home/.vscodroid/extensions-old/leak.css", ROOTS))
        assertNull(resolveWebviewResource("$FILES/saf-mirrors-tmp/leak.css", ROOTS))
    }

    /**
     * Catches: an `isEmpty()` short-circuit, or an inverted any/none. A client
     * that was handed no roots must serve nothing at all rather than fall back
     * to serving everything.
     */
    @Test
    fun `an empty published set serves nothing`() {
        assertNull(resolveWebviewResource("$FILES/server/vscode-reh/out/server-main.js", emptyList()))
    }

    /**
     * Catches: a boundary test written as `canonical == root || startsWith`.
     * A root is a directory; naming one is not a request for a resource, and
     * admitting it costs a reader the certainty that everything served is
     * strictly inside.
     */
    @Test
    fun `a published root itself is not a resource`() {
        assertNull(resolveWebviewResource("$FILES/server", ROOTS))
    }

    /**
     * Catches: resolving against the request as written rather than against
     * the canonical path that was checked. The two differ for any path
     * containing `.`, `..` or a duplicated separator, and returning the
     * unchecked one reopens the gap the check just closed.
     */
    @Test
    fun `the resolved file is the canonical path, not the requested one`() {
        val requested = "$FILES/server/./vscode-reh/../vscode-reh/out/server-main.js"
        assertEquals(
            canonical("$FILES/server/vscode-reh/out/server-main.js"),
            resolveWebviewResource(requested, ROOTS)?.path
        )
    }
}

/**
 * The one case that cannot be stated with synthetic paths.
 *
 * A workspace is a published root and a workspace can be a checked-out
 * repository, so its contents are attacker-supplied in the ordinary case. Git
 * carries symbolic links, which means a link inside a published root can name
 * a target outside every published root.
 */
class WebviewResourceSymlinkTest {

    @TempDir
    lateinit var tmp: File

    /**
     * Catches: swapping canonicalisation for a lexical normalisation such as
     * `java.nio.file.Path.normalize()`, which resolves `..` and would pass
     * every test in [WebviewResourceResolutionTest] while following a link
     * straight out of the workspace.
     */
    @Test
    fun `a symlink pointing out of a published root is refused`() {
        val workspace = File(tmp, "projects").apply { mkdirs() }
        val sshDir = File(tmp, "home/.ssh").apply { mkdirs() }
        val key = File(sshDir, "id_ed25519").apply { writeText("PRIVATE KEY") }

        val roots = listOf(workspace.canonicalPath)

        // Control first: an ordinary file in the same workspace is served, so
        // the refusal below cannot be a fixture that resolves nothing.
        val ordinary = File(workspace, "diagram.png").apply { writeText("png") }
        assertNotNull(resolveWebviewResource(ordinary.path, roots))

        val link = File(workspace, "diagram-2.png")
        Files.createSymbolicLink(link.toPath(), key.toPath())

        assertNull(resolveWebviewResource(link.path, roots))
    }
}

/**
 * That the set [publishedResourceRoots] builds is the set the resolver was
 * reasoned about with.
 *
 * [WebviewResourceResolutionTest] spells its roots out by hand on purpose, so
 * on its own it says nothing about which directories the app actually
 * publishes. These compose the two: they take the real roots and ask the real
 * resolver what it does with them.
 */
class PublishedResourceRootsTest {

    @TempDir
    lateinit var filesDir: File

    @TempDir
    lateinit var externalDir: File

    private fun contextWith(external: File?): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns filesDir
        every { context.getExternalFilesDir(null) } returns external
        return context
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * Catches: adding the home directory or the user-data directory to the
     * published set. Both are one-segment widenings, both look like they
     * merely cover more of the app's own storage, and either puts a
     * passphrase-less private key or the server's connection token back within
     * reach of rendered content.
     */
    @Test
    fun `neither the ssh key nor the connection token falls inside a published root`() {
        val roots = publishedResourceRoots(contextWith(externalDir))

        assertNull(resolveWebviewResource(File(filesDir, "home/.ssh/id_ed25519").path, roots))
        assertNull(resolveWebviewResource(File(filesDir, "home/.vscodroid/data/token").path, roots))
    }

    /**
     * The control for the refusals above, and separately a check that roots are
     * canonicalised.
     *
     * That second half bites here rather than hypothetically: a JUnit temporary
     * directory on this host is reached through a link (`/var/folders/…` for
     * `/private/var/folders/…`), so a root left as written matches no request
     * at all, and every assertion below fails. What that failure would look
     * like in the app is a workbench with no extension resources in it.
     */
    @Test
    fun `extension, server tree and workspace resources are served`() {
        val roots = publishedResourceRoots(contextWith(externalDir))

        assertNotNull(
            resolveWebviewResource(
                File(filesDir, "home/.vscodroid/extensions/pub.ext-1.0.0/media/main.css").path, roots
            ),
            "an installed extension's own media"
        )
        assertNotNull(
            resolveWebviewResource(
                File(filesDir, "server/vscode-reh/extensions/markdown-language-features/media/m.css").path,
                roots
            ),
            "a built-in extension's media"
        )
        assertNotNull(
            resolveWebviewResource(File(filesDir, "saf-mirrors/a1b2c3d4e5f6/README.png").path, roots),
            "a mirrored workspace"
        )
        assertNotNull(
            resolveWebviewResource(File(externalDir, "projects/app/docs/diagram.png").path, roots),
            "the default workspace"
        )
    }

    /**
     * Catches: covering the internal fallback by publishing its parent. When
     * external storage is unavailable the projects directory moves to
     * `<filesDir>/home/projects`, and the shortest root that covers it is
     * `<filesDir>/home`, the directory holding `.ssh`.
     */
    @Test
    fun `the internal projects fallback does not publish the home directory`() {
        val roots = publishedResourceRoots(contextWith(null))

        assertNotNull(
            resolveWebviewResource(File(filesDir, "home/projects/app/docs/diagram.png").path, roots),
            "the fallback workspace still has to work"
        )
        assertNull(resolveWebviewResource(File(filesDir, "home/.ssh/id_ed25519").path, roots))
        assertNull(resolveWebviewResource(File(filesDir, "home/.bashrc").path, roots))
    }

    /**
     * Catches: a sensitive set that drifts from the paths the rest of the app
     * actually writes to. Both entries are taken from Environment's getters, so
     * this fails if one is repointed out from under them or dropped.
     */
    @Test
    fun `the sensitive set names the connection token and the ssh directory`() {
        val sensitive = sensitiveLocations(contextWith(externalDir))

        assertEquals(
            listOf(
                File(filesDir, "home/.vscodroid/data/token").canonicalPath,
                File(filesDir, "home/.ssh").canonicalPath,
            ),
            sensitive
        )
    }
}

/**
 * Whether the open folder may be published alongside the static roots.
 *
 * The workspace has to be a root for a markdown preview to show an image next
 * to the file it previews, and it cannot be a static one because the user picks
 * it. These pin where the line falls.
 */
class WorkspaceRootTest {

    /**
     * The control for every refusal below. Catches an acceptance rule that
     * refuses everything, which would satisfy them all and publish nothing.
     */
    @Test
    fun `an ordinary workspace is published`() {
        assertEquals(
            canonical("$FILES/home/projects/app"),
            workspaceRootOrNull("$FILES/home/projects/app", SENSITIVE)
        )
    }

    /**
     * Catches: dropping the containment test. Opening the home directory is an
     * ordinary thing to do from a bundled terminal, and it is the single move
     * that would hand out the passphrase-less private key.
     */
    @Test
    fun `the home directory is refused because it holds the ssh directory`() {
        assertNull(workspaceRootOrNull("$FILES/home", SENSITIVE))
    }

    /**
     * Catches: testing containment in one direction only. `.ssh` contains no
     * sensitive location (it *is* one), so a rule asking only "does the
     * candidate hold something sensitive" publishes it.
     */
    @Test
    fun `the ssh directory itself is refused`() {
        assertNull(workspaceRootOrNull("$FILES/home/.ssh", SENSITIVE))
    }

    /**
     * Catches: the same one-directional rule as above, for the case where the
     * candidate sits below the sensitive directory rather than equalling it.
     */
    @Test
    fun `a directory inside the ssh directory is refused`() {
        assertNull(workspaceRootOrNull("$FILES/home/.ssh/keys", SENSITIVE))
    }

    /**
     * Catches: listing only the ssh directory as sensitive. The token is a
     * file, so it is reached by containment from its parent and never by the
     * candidate sitting inside it.
     */
    @Test
    fun `a folder holding the connection token is refused`() {
        assertNull(workspaceRootOrNull("$FILES/home/.vscodroid/data", SENSITIVE))
    }

    /**
     * Catches: a boundary test written without the separator, this time in the
     * refusing direction. A folder whose name merely begins with `.ssh` is an
     * ordinary folder, and refusing it would break a real workspace.
     */
    @Test
    fun `a sibling sharing a name prefix with a sensitive directory is published`() {
        assertEquals(
            canonical("$FILES/home/.ssh-backup"),
            workspaceRootOrNull("$FILES/home/.ssh-backup", SENSITIVE)
        )
    }

    /**
     * Catches: judging the candidate as written. The workspace path arrives
     * from a URL query parameter, so it is not guaranteed to be normalised.
     */
    @Test
    fun `a candidate reached through dot-dot is judged by where it lands`() {
        assertNull(workspaceRootOrNull("$FILES/home/projects/../.ssh", SENSITIVE))
    }

    /**
     * Catches: treating a missing folder as permission to publish something.
     * The supplier answers null before the first navigation completes.
     */
    @Test
    fun `no open folder publishes nothing`() {
        assertNull(workspaceRootOrNull(null, SENSITIVE))
    }
}

/**
 * The composition both entry points go through.
 *
 * [WorkspaceRootTest] pins the rule and [WebviewResourceResolutionTest] pins
 * the resolver; neither says the two are joined, or that a refused workspace
 * leaves the static roots working.
 */
class ResourceRootsInForceTest {

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /**
     * Catches: composing the workspace in but never consulting it, or adding it
     * ahead of the static roots in a way that drops them.
     */
    @Test
    fun `an accepted workspace joins the published roots`() {
        val roots = resourceRootsInForce(ROOTS, SENSITIVE, "$FILES/home/projects/app")

        assertTrue(roots.containsAll(ROOTS), "the static roots must survive")
        assertNotNull(
            resolveWebviewResource("$FILES/home/projects/app/docs/diagram.png", roots),
            "a file in the open workspace"
        )
        assertNotNull(
            resolveWebviewResource("$FILES/server/vscode-reh/extensions/md/media/m.css", roots),
            "a built-in extension's media"
        )
    }

    /**
     * The one that matters most: the whole chain, from the folder the user
     * opened to the bytes that would be served.
     *
     * Catches: any acceptance rule that publishes the home directory. Opening
     * `~` must cost the workspace its resources, not hand over the key.
     */
    @Test
    fun `opening the home directory does not make the ssh key readable`() {
        val roots = resourceRootsInForce(ROOTS, SENSITIVE, "$FILES/home")

        assertNull(resolveWebviewResource("$FILES/home/.ssh/id_ed25519", roots))
        assertNull(resolveWebviewResource("$FILES/home/notes.md", roots))
        assertNotNull(
            resolveWebviewResource("$FILES/server/vscode-reh/extensions/md/media/m.css", roots),
            "a refused workspace must not cost the static roots their resources"
        )
    }

    /**
     * Catches: appending a null workspace as an empty string, which would make
     * `"$root/"` equal `"/"` and publish the whole filesystem.
     */
    @Test
    fun `no open folder leaves the published roots exactly as they were`() {
        assertEquals(ROOTS, resourceRootsInForce(ROOTS, SENSITIVE, null))
        assertNull(resolveWebviewResource("/etc/hosts", resourceRootsInForce(ROOTS, SENSITIVE, null)))
    }
}
