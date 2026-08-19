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

    /**
     * A resource request for [path], shaped the way the workbench sends one.
     *
     * [origin] is stubbed explicitly, including when it is null, because the fixture is
     * `relaxed`: an unstubbed `requestHeaders` answers with an empty map, which the
     * origin gate reads as "no Origin" and serves. Every case here would therefore pass
     * whatever the gate did, which is what the cases below exist to avoid.
     */
    private fun requestFor(path: String, origin: String? = null): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "file+.vscode-resource.vscode-cdn.net"
        every { uri.path } returns path
        every { uri.query } returns null
        val request = mockk<WebResourceRequest>(relaxed = true)
        every { request.url } returns uri
        every { request.requestHeaders } returns
            if (origin == null) emptyMap() else mapOf("Origin" to origin)
        return request
    }

    /**
     * A page that is not the workbench cannot read a workspace file.
     *
     * The served file carries `Access-Control-Allow-Origin: *`, so before the gate the
     * answer to "who may read this" was anybody who could reach the interceptor -- and
     * a remote page in the bundled Simple Browser can, with no network involved.
     *
     * Proved by making the file unreadable rather than by the log alone, the same way
     * `a refused resource is never opened` does: if the refusal did not happen, the open
     * would, and the fixture would throw.
     */
    @Test
    fun `a foreign origin is refused before the file is opened`() {
        val unreadable = File(workspace, "secret.txt").apply {
            writeText("x")
            check(setReadable(false, false)) { "could not make the fixture unreadable" }
        }

        VSCodroidWebViewClient.interceptCdnRequest(
            requestFor(unreadable.absolutePath, origin = "https://evil.example"),
            PORT, null, published, sensitive, { workspace.absolutePath },
        )

        assertTrue(
            warnings.any { it.contains("foreign origin") && it.contains("https://evil.example") },
            "the refusal should name the origin it refused. Logged:\n" +
                warnings.joinToString("\n") { "  $it" },
        )
    }

    /**
     * The workbench's own origin still serves, or the gate has closed the app to itself.
     *
     * This case and the one below it assert the ABSENCE of a refusal, so neither goes red
     * if the gate is deleted. That is deliberate and worth saying plainly: only
     * `a foreign origin is refused before the file is opened` detects the gate going
     * missing, measured by disabling it. These two guard the other direction, which is the
     * one that breaks the editor rather than the one that leaks a file.
     */
    @Test
    fun `the workbench origin is served`() {
        val file = File(workspace, "ok.txt").apply { writeText("hello") }

        VSCodroidWebViewClient.interceptCdnRequest(
            requestFor(file.absolutePath, origin = "http://127.0.0.1:$PORT"),
            PORT, null, published, sensitive, { workspace.absolutePath },
        )

        assertTrue(
            warnings.none { it.contains("foreign origin") },
            "the workbench was refused its own file. Logged:\n" +
                warnings.joinToString("\n") { "  $it" },
        )
    }

    /**
     * And a webview document's origin, which is where extension content lives:
     * `webviewContentExternalBaseUrlTemplate` in the shipped bundle resolves to
     * `https://{{uuid}}.vscode-cdn.net/...`.
     */
    @Test
    fun `a webview origin is served`() {
        val file = File(workspace, "asset.css").apply { writeText("body{}") }

        VSCodroidWebViewClient.interceptCdnRequest(
            requestFor(file.absolutePath, origin = "https://0f7c2b1a-uuid.vscode-cdn.net"),
            PORT, null, published, sensitive, { workspace.absolutePath },
        )

        assertTrue(
            warnings.none { it.contains("foreign origin") },
            "an extension webview was refused its own resource. Logged:\n" +
                warnings.joinToString("\n") { "  $it" },
        )
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

    /**
     * That the refusal is a refusal, and not a warning printed on the way to
     * serving the file anyway.
     *
     * Every other case here reads the log, because serving and refusing both end
     * in a `WebResourceResponse` and that class cannot be constructed under the
     * stub `android.jar` — its constructor is mocked, so nothing about it can be
     * asserted. A log line is not evidence of a refusal: code that logged and then
     * served satisfied all four.
     *
     * What separates the two without touching the response is whether the file is
     * opened. The serve branch calls `FileInputStream(file)` with no `try` around
     * it and nothing catching upstream, so making the key unreadable turns any
     * attempt to serve it into a thrown `FileNotFoundException`. Refusing never
     * reaches that line and returns normally.
     *
     * [ResourceOutcomeTest] covers the same decision as a value; this is the half
     * that proves the interceptor obeys it.
     */
    @Test
    fun `a refused resource is never opened`() {
        check(sshKey.setReadable(false, false)) { "could not make the fixture unreadable" }
        check(!sshKey.canRead()) {
            "the fixture is still readable, so this test would pass without proving anything"
        }

        // Throws if the interceptor reaches FileInputStream on it.
        intercept(sshKey.path, openFolder = null)

        assertTrue(
            warnings.any { it.contains(sshKey.path) },
            "the private key was neither refused nor opened, which is a third outcome " +
                "this test does not understand: $warnings",
        )
    }

    private companion object {
        /** Never connected to — every path here is answered from the filesystem. */
        const val PORT = 41234
    }
}
