package com.vscodroid.webview

import com.vscodroid.SourceScan
import com.vscodroid.webview.VSCodroidWebViewClient.Companion.SECRET_KEY_PATH
import com.vscodroid.webview.VSCodroidWebViewClient.Companion.secretKeyRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which requests for the secret storage key are answered.
 *
 * The two directions cost different things. Refusing the workbench is data
 * loss, not an error message: the page retries, gives up, starts with no
 * secrets, and the next sign-in overwrites everything stored before it with that
 * one session. Answering another frame hands it a key, which is useless without
 * the workbench's localStorage but is still not its to have. So a request is
 * refused only on evidence that it came from somewhere else, and an absent
 * header is not that evidence.
 */
class SecretKeyRequestTest {

    private val port = 41234
    private val ours = "http://127.0.0.1:$port"

    @Test
    fun `the workbench's key request is answered`() {
        assertEquals(SecretKeyRequest.ANSWER, secretKeyRequest(SECRET_KEY_PATH, "POST", null, null, port))
        assertEquals(SecretKeyRequest.ANSWER, secretKeyRequest(SECRET_KEY_PATH, "POST", ours, "same-origin", port))
        assertEquals(
            SecretKeyRequest.ANSWER,
            secretKeyRequest(SECRET_KEY_PATH, "POST", "http://localhost:$port", null, port),
        )
    }

    @Test
    fun `another path is not a key request`() {
        assertEquals(SecretKeyRequest.NOT_ONE, secretKeyRequest("/", "POST", ours, null, port))
        assertEquals(
            SecretKeyRequest.NOT_ONE,
            secretKeyRequest("$SECRET_KEY_PATH/x", "POST", ours, null, port),
        )
        assertEquals(SecretKeyRequest.NOT_ONE, secretKeyRequest(null, "POST", ours, null, port))
    }

    @Test
    fun `a read is refused`() {
        // The workbench only ever posts. A GET is a navigation or a subresource,
        // which a frame can make without script.
        assertEquals(SecretKeyRequest.REFUSE, secretKeyRequest(SECRET_KEY_PATH, "GET", ours, null, port))
    }

    @Test
    fun `another origin is refused`() {
        assertEquals(
            SecretKeyRequest.REFUSE,
            secretKeyRequest(SECRET_KEY_PATH, "POST", "https://0f7c-uuid.vscode-cdn.net", null, port),
        )
        assertEquals(
            SecretKeyRequest.REFUSE,
            secretKeyRequest(SECRET_KEY_PATH, "POST", "http://127.0.0.1:5173", null, port),
        )
        assertEquals(SecretKeyRequest.REFUSE, secretKeyRequest(SECRET_KEY_PATH, "POST", "null", null, port))
        assertEquals(SecretKeyRequest.REFUSE, secretKeyRequest(SECRET_KEY_PATH, "POST", null, "cross-site", port))
    }

    /**
     * The two call sites the decision depends on, which nothing above observes.
     *
     * Each failure compiles and keeps every other case green. Without the key
     * argument the client's default throws and every key request is a 503;
     * without the cookie the workbench never asks; with the arm below the
     * bundle arm's early return, the POST reaches Node and gets 405. All three
     * put secrets back in memory with nothing on screen to say so.
     */
    @Test
    fun `the activity switches the storage on and the client answers first`() {
        val activity = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))
        assertTrue(
            activity.contains("secretStorageKey ="),
            "MainActivity no longer hands VSCodroidWebViewClient its key; the default throws",
        )
        val load = SourceScan.body(activity, "private fun loadVSCode(")
        val cookie = load.indexOf("applySecretStorage()")
        assertTrue(
            cookie >= 0 && cookie < load.indexOf("loadUrl("),
            "loadVSCode must set the secret storage cookie before it navigates",
        )
        val client = SourceScan.withoutComments(
            SourceScan.read("src/main/kotlin/com/vscodroid/webview/VSCodroidWebViewClient.kt")
        )
        val intercept = SourceScan.body(client, "override fun shouldInterceptRequest(view: WebView")
        val decided = intercept.indexOf("secretKeyRequest(")
        assertTrue(
            decided >= 0 && decided < intercept.indexOf("nlsBundleRequested("),
            "the key arm must run before the bundle arm, whose early return hands the POST to Node",
        )
    }

    @Test
    fun `the cookie names the path the arm answers`() {
        assertEquals(
            "vscode-secret-key-path=$SECRET_KEY_PATH; path=/",
            VSCodroidWebViewClient.secretStorageCookie(),
        )
    }
}
