package com.vscodroid.webview

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * `WebviewResourceResolutionTest` and `WorkspaceRootTest` pin the decisions.
 * This pins the *calls to them*: `interceptResourceRequest` could go back to
 * `File(path)` with every one of those green, which is the same shape of gap
 * `InitialSyncWiringTest` exists to close.
 *
 * These run [VSCodroidWebViewClient.interceptCdnRequest] itself, against real
 * files in a real directory, so `exists` and `isFile` are answered by the
 * filesystem rather than by a stub. Two things stand in for observation, and
 * neither is a mock recording an interaction for its own sake:
 *
 * - a non-null return means the request reached a branch that builds a
 *   response, which rules out the fixture missing the host test and the whole
 *   thing returning null before it ever looked at a path;
 * - the warning is the app's own account of a refusal, and it is deliberately
 *   the line that carries the path, because it is what a reader has when a
 *   preview comes up blank.
 *
 * `WebResourceResponse` cannot be constructed under the stub `android.jar` —
 * every branch here would die in its constructor — so the constructor is
 * mocked purely to let the function run to its end. Nothing is asserted about
 * it.
 */
class ResourceInterceptionWiringTest {

    @TempDir
    lateinit var tmp: File

    private val warnings = mutableListOf<String>()

    private val serverTree get() = File(tmp, "server")
    private val extensionAsset get() = File(serverTree, "extensions/md/media/m.css")
    private val home get() = File(tmp, "home")
    private val sshKey get() = File(home, ".ssh/id_ed25519")
    private val workspace get() = File(home, "projects/app")
    private val workspaceFile get() = File(workspace, "diagram.png")
    private val homeFile get() = File(home, "notes.md")

    private val published get() = listOf(serverTree.canonicalPath)
    private val sensitive get() = listOf(File(home, ".ssh").canonicalPath)

    @BeforeEach
    fun setUp() {
        warnings.clear()

        listOf(extensionAsset, sshKey, workspaceFile, homeFile).forEach {
            it.parentFile!!.mkdirs()
            it.writeText("fixture")
        }

        mockkObject(Logger)
        every { Logger.w(any(), any()) } answers { warnings += secondArg<String>() }
        every { Logger.w(any(), any(), any()) } answers { warnings += secondArg<String>() }
        every { Logger.d(any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs

        mockkConstructor(WebResourceResponse::class)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    /** A resource request for [path], shaped the way the workbench sends one. */
    private fun requestFor(path: String): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "file+.vscode-resource.vscode-cdn.net"
        every { uri.path } returns path
        every { uri.query } returns null
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        return request
    }

    private fun intercept(path: String, openFolder: String?) = assertNotNull(
        VSCodroidWebViewClient.interceptCdnRequest(
            requestFor(path), PORT, null, published, sensitive, { openFolder }
        ),
        "the request never reached a branch that builds a response, so nothing below was exercised"
    )

    /**
     * Catches: replacing the resolver call in `interceptResourceRequest` with
     * `File(path)`. The key exists on disk and its directory is inside the
     * app-private tree the removed prefix test admitted, so nothing else in the
     * function would stop it.
     */
    @Test
    fun `the interceptor refuses a path outside every root before opening it`() {
        // Control first. A refusal below proves nothing if the fixture also
        // refuses the resource that is supposed to work.
        intercept(extensionAsset.path, openFolder = null)
        assertTrue(warnings.isEmpty(), "a legitimate extension resource was refused: $warnings")

        intercept(sshKey.path, openFolder = null)
        assertTrue(
            warnings.any { it.contains(sshKey.path) },
            "nothing refused the private key, and it exists on disk, so it was served"
        )
    }

    /**
     * Catches: forwarding the static roots straight to the resolver and never
     * composing the open folder in. The workspace is not among the published
     * roots, so its files are reachable only through that composition.
     */
    @Test
    fun `the interceptor serves a file from the open workspace`() {
        intercept(workspaceFile.path, openFolder = workspace.path)

        assertTrue(
            warnings.isEmpty(),
            "a file in the folder the user has open was refused: $warnings"
        )
    }

    /**
     * Catches: composing the open folder in unconditionally. Opening the home
     * directory has to cost the workspace its resources rather than publish the
     * `.ssh` directory it holds.
     */
    @Test
    fun `a workspace holding a sensitive location is not composed in`() {
        intercept(homeFile.path, openFolder = home.path)

        assertTrue(
            warnings.any { it.contains(homeFile.path) },
            "the home directory was published as a resource root"
        )
    }

    /**
     * The other half, and the more valuable one: a workspace being refused must
     * not take the published roots down with it. If it did, opening the wrong
     * folder would empty the workbench of every extension resource — a far
     * larger failure than the one being prevented.
     *
     * Asserted against the path this test is about rather than against an empty
     * list, because it cannot be empty: the refusal of the workspace is
     * reported on every resource request for as long as that folder stays open,
     * so a successful serve arrives with that warning already sitting there.
     * `isEmpty` reads the same and asks a different question.
     */
    @Test
    fun `a refused workspace leaves the published roots serving`() {
        intercept(extensionAsset.path, openFolder = home.path)

        assertTrue(
            warnings.none { it.contains(extensionAsset.path) },
            "a refused workspace cost the published roots their resources: $warnings"
        )
    }

    private companion object {
        /** Never connected to — every path here is answered from the filesystem. */
        const val PORT = 41234
    }
}
