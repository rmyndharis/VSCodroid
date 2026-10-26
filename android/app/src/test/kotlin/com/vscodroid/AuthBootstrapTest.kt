package com.vscodroid

import com.vscodroid.setup.finishTestProcess
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

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
        val result = finishTestProcess(ProcessBuilder("node", "scripts/test-auth-bootstrap.js")
            .directory(File("../..")).redirectErrorStream(true).start(), timeoutMillis = 20_000)
        assertEquals(0, result.first, result.second)
    }
}
