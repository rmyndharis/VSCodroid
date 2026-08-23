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
import java.net.URLDecoder

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
 * - the log line is the app's own account of a refusal, and it is deliberately
 *   the one that carries the path, because it is what a reader has when a
 *   preview comes up blank. It is at `Logger.d` rather than `Logger.w`, so on a
 *   user's build it is not written at all: the value in it is a page's to
 *   compose and a page's to repeat.
 *
 * `WebResourceResponse` cannot be constructed under the stub `android.jar`
 * (every branch here would die in its constructor), so the constructor is
 * mocked purely to let the function run to its end. Nothing is asserted about
 * it.
 */
class ResourceInterceptionWiringTest {

    @TempDir
    lateinit var tmp: File

    /**
     * Every line the interceptor logged, at whichever level it chose.
     *
     * Both levels, because the refusals moved to `Logger.d`: the value they carry
     * is a path or an origin a page chose, and repeating one per request at a
     * level that ships was an unbounded write into logcat. What the cases below
     * read is WHICH line arrived, and that is unchanged by the level it arrived
     * at; the open failure still names the open and a refusal still names the
     * roots, which is what tells the two apart.
     */
    private val logged = mutableListOf<String>()

    /**
     * The lines that say something was NOT served.
     *
     * The serve line is at the same level as the refusals now, and it names the
     * path too, so a case asking "was anything refused" has to drop it or it
     * answers "yes" for every file that loaded correctly. Dropped by what it says
     * rather than by level, because level is exactly what stopped discriminating.
     */
    private val refusals get() = logged.filterNot { it.startsWith("Resource served") }

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
        logged.clear()

        listOf(extensionAsset, sshKey, workspaceFile, homeFile).forEach {
            it.parentFile!!.mkdirs()
            it.writeText("fixture")
        }

        mockkObject(Logger)
        every { Logger.w(any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.w(any(), any(), any()) } answers { logged += secondArg<String>() }
        every { Logger.d(any(), any()) } answers { logged += secondArg<String>() }
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
    private fun requestFor(
        path: String, origin: String? = null, query: String? = null
    ): WebResourceRequest {
        val uri = mockk<Uri>(relaxed = true)
        every { uri.host } returns "file+.vscode-resource.vscode-cdn.net"
        every { uri.path } returns path
        // Both accessors, answering what the platform answers: `getEncodedQuery`
        // is the address as written and `getQuery` is it decoded. Stubbing one
        // string to both is what let the earlier cases here pass against a gate
        // reading the decoded spelling, which a page can fold a second `&` into.
        every { uri.encodedQuery } returns query
        every { uri.query } returns query?.let { URLDecoder.decode(it, "UTF-8") }
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
     * The fixture is unreadable so that letting it through cannot look like a refusal:
     * a gate that stopped refusing would reach the open, and the open failure logs a
     * line of its own that names the open rather than the origin, so the assertion
     * below still tells the two apart.
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
            logged.any { it.contains("foreign origin") && it.contains("https://evil.example") },
            "the refusal should name the origin it refused. Logged:\n" +
                logged.joinToString("\n") { "  $it" },
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
            logged.none { it.contains("foreign origin") },
            "the workbench was refused its own file. Logged:\n" +
                logged.joinToString("\n") { "  $it" },
        )
    }

    /**
     * And a webview document's origin, which is where extension content lives.
     * `branding/product.json` lists `webviewContentExternalBaseUrlTemplate` under
     * `remove`, so `webviewExternalEndpoint` in the shipped `workbench.js` falls
     * back to its own hardcoded `https://{{uuid}}.vscode-cdn.net/...` template.
     */
    @Test
    fun `a webview origin is served`() {
        val file = File(workspace, "asset.css").apply { writeText("body{}") }

        VSCodroidWebViewClient.interceptCdnRequest(
            requestFor(file.absolutePath, origin = "https://0f7c2b1a-uuid.vscode-cdn.net"),
            PORT, null, published, sensitive, { workspace.absolutePath },
        )

        assertTrue(
            logged.none { it.contains("foreign origin") },
            "an extension webview was refused its own resource. Logged:\n" +
                logged.joinToString("\n") { "  $it" },
        )
    }

    /**
     * A document served from the resource authority may not ask for the next file.
     *
     * A navigation carries no `Origin` header, so a frame pointed at
     * `https://file+.vscode-resource.vscode-cdn.net/<path>` is served whatever is
     * there, and an `.html` under a published root arrives as `text/html` and
     * runs. A published root is the open workspace, the projects tree or a SAF
     * mirror, which is to say a checked-out repository in the ordinary case. While
     * this origin was accepted, that document could `fetch` every other file under
     * every root back through this same arm. Nothing legitimate asks from there:
     * webview documents come from the `{{uuid}}.vscode-cdn.net` template and the
     * resource authority only ever answers subresources.
     *
     * The name says `fetch` because that is the whole of what this closes. The
     * same document can still frame another path on its own origin and read that
     * one back, since a navigation arrives here with no origin to refuse; the
     * comment on the gate itself states that residual rather than leaving it to
     * be discovered.
     */
    @Test
    fun `a document at the resource authority cannot fetch another resource`() {
        val file = File(workspace, "notes.txt").apply { writeText("secret") }

        VSCodroidWebViewClient.interceptCdnRequest(
            requestFor(
                file.absolutePath,
                origin = "https://file+.vscode-resource.vscode-cdn.net",
            ),
            PORT, null, published, sensitive, { workspace.absolutePath },
        )

        assertTrue(
            logged.any { it.contains("foreign origin") },
            "a document this arm served was trusted to read the rest of the workspace " +
                "through it. Logged:\n" + logged.joinToString("\n") { "  $it" },
        )
    }

    /**
     * A frame cannot name a parent origin of its own and be handed a webview host.
     *
     * The label under `.vscode-cdn.net` is chosen by whoever writes the address,
     * and `pre/index.html` starts as soon as its hostname is the hash of
     * `{parentOrigin, salt}` -- both of which the caller picks. That gives a
     * rendered page script running at an origin ending `.vscode-cdn.net`, which
     * the resource gate accepts, and from there every file under every published
     * root is readable. Requiring the parent to be the workbench closes it: the
     * page then posts its message port to `http://127.0.0.1:<port>` as the target
     * origin, which a frame at any other origin never receives.
     */
    @Test
    fun `a request naming a foreign parent origin is refused`() {
        val file = File(workspace, "asset.js").apply { writeText("x") }

        VSCodroidWebViewClient.interceptCdnRequest(
            requestFor(file.absolutePath, query = "parentOrigin=https%3A%2F%2Fevil.example&id=1"),
            PORT, null, published, sensitive, { workspace.absolutePath },
        )

        assertTrue(
            logged.any { it.contains("foreign parent origin") },
            "a page named a parent origin of its own and was answered. Logged:\n" +
                logged.joinToString("\n") { "  $it" },
        )
        assertTrue(
            refusals.isNotEmpty() && logged.none { it.startsWith("Resource served") },
            "the file was served anyway: " + logged.joinToString("\n") { "  $it" },
        )
    }

    /**
     * A page cannot smuggle an approved parent origin past the gate.
     *
     * `%26` is a `&` once decoded, so the single parameter below becomes two in
     * `Uri.getQuery()` and the first of the two names the workbench. What
     * `pre/index.html` reads is the address as written, through
     * `URLSearchParams`, which gives it the second one; a gate reading the
     * decoded spelling therefore approves a value the page never sent and hands
     * that page a running webview host.
     */
    @Test
    fun `a parent origin smuggled through a decoded separator is refused`() {
        val file = File(workspace, "asset3.js").apply { writeText("x") }

        VSCodroidWebViewClient.interceptCdnRequest(
            requestFor(
                file.absolutePath,
                query = "id=1%26parentOrigin%3Dhttp%3A%2F%2F127.0.0.1%3A$PORT" +
                    "&parentOrigin=https%3A%2F%2Fevil.example",
            ),
            PORT, null, published, sensitive, { workspace.absolutePath },
        )

        assertTrue(
            logged.any { it.contains("foreign parent origin") },
            "the gate read a parameter the page manufactured by escaping a separator, " +
                "and the page is handed the origin it actually asked for. Logged:\n" +
                logged.joinToString("\n") { "  $it" },
        )
    }

    /**
     * The control, without which the case above passes on a gate that refuses
     * everything carrying a query at all.
     */
    @Test
    fun `the workbench's own parent origin is served`() {
        val file = File(workspace, "asset2.js").apply { writeText("x") }

        VSCodroidWebViewClient.interceptCdnRequest(
            requestFor(
                file.absolutePath,
                query = "parentOrigin=http%3A%2F%2F127.0.0.1%3A$PORT&id=1",
            ),
            PORT, null, published, sensitive, { workspace.absolutePath },
        )

        assertTrue(
            logged.none { it.contains("foreign parent origin") },
            "the workbench was refused its own webview. Logged:\n" +
                logged.joinToString("\n") { "  $it" },
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
        assertTrue(refusals.isEmpty(), "a legitimate extension resource was refused: $refusals")

        intercept(sshKey.path, openFolder = null)
        assertTrue(
            // The refusal text and not the path alone. The serve line names the
            // path too, so a resolver replaced by `File(path)` would serve the key
            // and satisfy an assertion that only looked for the path in the log,
            // which is the one thing this case exists to catch.
            logged.any { it.contains("outside the published roots") && it.contains(sshKey.path) },
            "nothing refused the private key, and it exists on disk, so it was served: $logged"
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
            refusals.isEmpty(),
            "a file in the folder the user has open was refused: $refusals"
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
            // Anchored on the refusal text for the reason the private-key case
            // gives, and this is the half that matters more: `notes.md` exists and
            // is readable, so composing the home directory in unconditionally
            // serves it and the serve line carries its path. Only the refusal text
            // tells the two apart. The workspace warning cannot stand in either:
            // it names the folder, not this file.
            logged.any { it.contains("outside the published roots") && it.contains(homeFile.path) },
            "the home directory was published as a resource root: $logged"
        )
    }

    /**
     * The other half, and the more valuable one: a workspace being refused must
     * not take the published roots down with it. If it did, opening the wrong
     * folder would empty the workbench of every extension resource, a far
     * larger failure than the one being prevented.
     *
     * Asserted against the path this test is about rather than against an empty
     * list, because it need not be empty: the refusal of the workspace is
     * reported once for the folder, and this is the first request naming it, so a
     * successful serve arrives with that warning already sitting there. `isEmpty`
     * reads the same and asks a different question.
     */
    @Test
    fun `a refused workspace leaves the published roots serving`() {
        intercept(extensionAsset.path, openFolder = home.path)

        assertTrue(
            refusals.none { it.contains(extensionAsset.path) },
            "a refused workspace cost the published roots their resources: $refusals"
        )
    }

    /**
     * That the refusal is a refusal, and not a warning printed on the way to
     * serving the file anyway.
     *
     * Every other case here reads the log, because serving and refusing both end
     * in a `WebResourceResponse` and that class cannot be constructed under the
     * stub `android.jar`: its constructor is mocked, so nothing about it can be
     * asserted. A log line is not evidence of a refusal: code that logged and then
     * served satisfied all four.
     *
     * What separates the two without touching the response is whether the file is
     * opened, and the key is made unreadable so that an attempt to open it cannot
     * pass unnoticed. The instrument used to be the crash itself: the serve branch
     * called `FileInputStream(file)` with no `try` around it, so serving an
     * unreadable file threw `FileNotFoundException` out of the whole callback.
     * That throw was a defect of its own and is now caught, which is why the two
     * outcomes are told apart by WHICH warning arrives rather than by whether one
     * arrives at all: a refusal names the roots, an open failure names the open.
     * Both quote the path, so the path alone no longer discriminates.
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

        intercept(sshKey.path, openFolder = null)

        assertTrue(
            logged.any { it.contains("outside the published roots") && it.contains(sshKey.path) },
            "the private key was not refused: $logged",
        )
        assertTrue(
            logged.none { it.contains("could not be opened") },
            "the interceptor tried to open the private key and only failed because the " +
                "fixture is unreadable: $logged",
        )
    }

    /**
     * A file that resolves inside a root, exists, and still cannot be opened.
     *
     * `resourceOutcome` answers `Serve` from `exists()` and `isFile`, and the open
     * is a second syscall: a watch build or a `git checkout` can unlink the file in
     * the gap, and a file with no read permission fails there every time with no
     * race at all. Nothing on the path from either entry point catches, so the
     * `FileNotFoundException` left `shouldInterceptRequest` for the WebView's own
     * callback, where `CrashReporter` recorded the process death rather than
     * preventing it. The population is every webview resource request.
     *
     * NEGATIVE CONTROL: remove the `try` around `FileInputStream(file)` in
     * `interceptResourceRequest` and this case goes red with the exception it
     * describes.
     */
    @Test
    fun `a resource that cannot be opened is answered rather than thrown`() {
        val locked = File(serverTree, "extensions/md/media/locked.css").apply {
            writeText("body{}")
            check(setReadable(false, false)) { "could not make the fixture unreadable" }
        }
        check(!locked.canRead()) {
            "the fixture is still readable, so this test would pass without proving anything"
        }

        intercept(locked.path, openFolder = null)

        assertTrue(
            logged.any { it.contains("could not be opened") && it.contains(locked.path) },
            "the open failure was neither reported nor recognisable in the log: $logged",
        )
    }

    private companion object {
        /** Never connected to: every path here is answered from the filesystem. */
        const val PORT = 41234
    }
}
