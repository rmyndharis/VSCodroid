package com.vscodroid

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthBootstrapTest {
    @Test
    fun `the verdict is the explicit completion for this document, and nothing else answers`() {
        assertEquals(true, authBootstrapVerdict("[7,true]", 7L))
        assertEquals(false, authBootstrapVerdict("[7,false]", 7L))
        for (marker in listOf(null, "null", "[]", "[7]", "[6,true]", "[6,false]", "[7,true,true]", "[7,1]", "[7.5,true]", "[\"7\",true]", "not json")) {
            assertNull(authBootstrapVerdict(marker, 7L), marker)
        }
    }

    /**
     * A page whose sign-in registration failed is still the workbench, and its key row, touch
     * fixes and relay are not what that failure protects. Only a page that did not answer for
     * this document is left without them, and only a working registration marks it ready for a
     * callback.
     */
    @Test
    fun `a failed registration keeps the page injections and a missing answer does not`() {
        val source = SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt"))
        val body = SourceScan.body(source, "private fun injectBridgeToken(")
        val unanswered = body.indexOf("if (verdict == null) return@evaluateJavascript")
        val firstInjection = body.indexOf("setupModifierInterceptor()")
        val ready = body.indexOf("if (verdict) {")
        assertTrue(unanswered in 0 until firstInjection, "a page that did not answer must stop before any injection")
        assertTrue(
            firstInjection < ready && body.indexOf("workbenchLoaded = true") > ready,
            "workbenchLoaded may only be set for a verdict of true, after every injection",
        )
        assertTrue(
            body.indexOf("if (verdict != true) client.completeAuthBootstrap(targetView, generation, false)") in 0 until unanswered,
            "a failed or unanswered bootstrap must still be reported to the waiting sign-ins",
        )
    }
}
