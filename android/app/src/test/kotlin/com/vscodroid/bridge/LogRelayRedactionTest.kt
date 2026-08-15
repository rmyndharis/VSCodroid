package com.vscodroid.bridge

import android.content.Context
import com.vscodroid.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * That the page cannot put the server's connection token into logcat through the
 * bridge's log relay.
 *
 * This is the sink where the token is the LIKELY case rather than a theoretical
 * one. Everywhere else the app redacts, the string was built by its own code and
 * carries a known `tkn=` shape; here the text is whatever the workbench chose to
 * log, and the workbench holds the token. A page logging its own URL is ordinary.
 *
 * The session-token check on the method says the call came from our page. It says
 * nothing about whether what it carries is safe to print — and `warn` and `error`
 * reach `Logger` methods that are not gated on a debuggable build, so they ship.
 *
 * Both arguments are covered. `tag` is page-supplied too, and nothing stops it
 * carrying a URL.
 */
class LogRelayRedactionTest {

    /** Distinctive enough that a match cannot be a coincidence. */
    private val token = "tkn-7b31d0c4-do-not-log-me"

    private val logged = mutableListOf<String>()
    private lateinit var bridge: AndroidBridge

    @BeforeEach
    fun setUp() {
        logged.clear()

        mockkObject(Logger)
        // Both the tag and the message are captured, because both are relayed
        // from the page and either could carry the token.
        every { Logger.d(any(), any()) } answers { logged += "${firstArg<String>()} ${secondArg<String>()}" }
        every { Logger.i(any(), any()) } answers { logged += "${firstArg<String>()} ${secondArg<String>()}" }
        every { Logger.w(any(), any()) } answers { logged += "${firstArg<String>()} ${secondArg<String>()}" }
        every { Logger.w(any(), any(), any()) } answers { logged += "${firstArg<String>()} ${secondArg<String>()}" }
        every { Logger.e(any(), any()) } answers { logged += "${firstArg<String>()} ${secondArg<String>()}" }
        every { Logger.e(any(), any(), any()) } answers { logged += "${firstArg<String>()} ${secondArg<String>()}" }

        val security = mockk<SecurityManager>(relaxed = true)
        // Accepted on purpose. A rejected token returns before the relay and would
        // make this pass by logging nothing at all.
        every { security.validateToken(any()) } returns true

        bridge = AndroidBridge(
            context = mockk<Context>(relaxed = true),
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = { false },
            onMinimize = { },
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a page line carrying the token is relayed without it, at every level`() {
        listOf("debug", "info", "warn", "error", "something-else").forEach { level ->
            bridge.logToNative(
                authToken = "accepted",
                level = level,
                tag = "workbench?tkn=$token",
                message = "GET http://127.0.0.1:13337/x?tkn=$token failed",
            )
        }

        // Control first: the relay has to have run. A rejected token, a renamed
        // method or an unstubbed Logger overload would all produce an empty list,
        // and an empty list satisfies the leak assertion for free.
        assertTrue(
            logged.size == 5,
            "expected one relayed line per level; the relay did not run as expected: $logged",
        )
        val leaks = logged.filter { it.contains(token) }
        assertTrue(
            leaks.isEmpty(),
            "the connection token reached the log through the bridge relay:\n" +
                leaks.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `a rejected session token relays nothing`() {
        // The pre-existing guard, pinned because the test above depends on it
        // being the ONLY reason nothing is logged when it should be.
        val security = mockk<SecurityManager>(relaxed = true)
        every { security.validateToken(any()) } returns false
        val refusing = AndroidBridge(
            context = mockk<Context>(relaxed = true),
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = { false },
            onMinimize = { },
        )

        refusing.logToNative("wrong", "error", "tag", "message")

        assertTrue(logged.isEmpty(), "a rejected token must not reach the log at all: $logged")
    }
}
