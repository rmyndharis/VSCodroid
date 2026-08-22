package com.vscodroid.webview

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Whether a webview resource request is served or refused, as a value.
 *
 * [ResourceInterceptionWiringTest] beside this one runs the real interceptor and
 * proves the *wiring*: that the open folder is composed into the roots and that
 * the resolver is called at all. What it cannot prove is the outcome. Serving and
 * refusing both end in a `WebResourceResponse`, which cannot be constructed under
 * the stub `android.jar`, so that test mocks the constructor and then has only the
 * app's own log line to go on, and code that logged the refusal and served the
 * file anyway satisfied every assertion in it.
 *
 * These assert the decision itself, so "log and serve anyway" is a different value
 * and fails here.
 *
 * The stakes are one-directional and worth stating: over-refusing empties a
 * markdown preview, which is visible and annoying. Under-refusing hands a private
 * key to a page, and the key is on disk inside the app-private tree with nothing
 * else in the request path standing between it and the response.
 */
class ResourceOutcomeTest {

    @TempDir
    lateinit var tmp: File

    private val serverTree get() = File(tmp, "server")
    private val extensionAsset get() = File(serverTree, "extensions/md/media/m.css")
    private val home get() = File(tmp, "home")
    private val sshKey get() = File(home, ".ssh/id_ed25519")

    private fun fixture() {
        listOf(extensionAsset, sshKey).forEach {
            it.parentFile!!.mkdirs()
            it.writeText("fixture")
        }
    }

    private val published get() = listOf(serverTree.canonicalPath)

    @Test
    fun `a file inside a published root is served`() {
        fixture()
        assertEquals(
            ResourceOutcome.Serve(File(extensionAsset.canonicalPath)),
            resourceOutcome(extensionAsset.path, published),
        )
    }

    @Test
    fun `a private key outside every root is refused, not merely logged`() {
        fixture()
        // The key exists and is readable; only the roots stand between it and the
        // page. A version that logs a warning and returns the file reaches Serve
        // here and fails, which the log-watching test could not see.
        assertEquals(
            ResourceOutcome.Refused,
            resourceOutcome(sshKey.path, published),
            "an SSH private key outside every published root must not be served",
        )
    }

    @Test
    fun `a path inside a root that does not exist is missing, not refused`() {
        fixture()
        // Distinguished on purpose: Missing means the roots admitted it, so a
        // refusal reported for an absent file would hide a genuine root problem
        // behind a spelling mistake.
        assertEquals(
            ResourceOutcome.Missing,
            resourceOutcome(File(serverTree, "extensions/md/media/gone.css").path, published),
        )
    }

    @Test
    fun `a subdirectory inside a root is not served as a file`() {
        fixture()
        assertEquals(
            ResourceOutcome.Missing,
            resourceOutcome(File(serverTree, "extensions/md").path, published),
            "a directory has no bytes to serve, and opening one would throw at read",
        )
    }

    @Test
    fun `the root itself is refused rather than treated as content`() {
        fixture()
        // The roots are matched with the separator appended, so a root is not a
        // path *inside* itself. Refused rather than Missing, which is the safer of
        // the two: the root always exists, so any rule that admitted it would have
        // to decide what serving a directory means.
        assertEquals(ResourceOutcome.Refused, resourceOutcome(serverTree.path, published))
    }

    @Test
    fun `traversal out of a root is refused`() {
        fixture()
        val escape = File(serverTree, "extensions/../../home/.ssh/id_ed25519").path
        assertEquals(
            ResourceOutcome.Refused,
            resourceOutcome(escape, published),
            "the path resolves outside the root, whatever it looks like literally",
        )
    }

    @Test
    fun `a sibling whose name merely starts with a root is refused`() {
        // A root's name is also a prefix of its siblings' names, and creating such
        // a sibling is within reach of anything running in the extension host.
        val sibling = File(tmp, "server-evil/secret.txt")
        sibling.parentFile!!.mkdirs()
        sibling.writeText("x")
        fixture()

        assertEquals(ResourceOutcome.Refused, resourceOutcome(sibling.path, published))
    }

    @Test
    fun `no roots at all refuses everything`() {
        fixture()
        assertEquals(ResourceOutcome.Refused, resourceOutcome(extensionAsset.path, emptyList()))
    }
}
