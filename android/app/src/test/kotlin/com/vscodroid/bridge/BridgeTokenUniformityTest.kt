package com.vscodroid.bridge

import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import com.vscodroid.storage.SafStorageManager
import com.vscodroid.util.CrashReporter
import com.vscodroid.util.Logger
import io.mockk.Called
import io.mockk.Runs
import io.mockk.clearMocks
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Every method exposed to the page through `@JavascriptInterface` must ask the session
 * token whether the caller is allowed, and must obey the answer. Those are two separate
 * properties and each has its own test below, because a method can do the first without
 * the second.
 *
 * Both enumerate the annotated methods by reflection rather than by a written list, so a
 * method added later is covered too: the rule holds for the class, not for the set that
 * existed when this was written.
 */
class BridgeTokenUniformityTest {

    /**
     * A bridge whose every collaborator is a mock, so "did this method act?" can be asked
     * as "did anything outside the bridge get touched?". Built fresh per method — `wasNot
     * Called` is a statement about a mock's whole life, so a shared one would carry the
     * previous method's calls into the next one's verdict.
     */
    private class Fixture {
        val security: SecurityManager = mockk(relaxed = true)
        val context: Context = mockk(relaxed = true)
        val clipboard: ClipboardBridge = mockk(relaxed = true)
        val onBackPressed: () -> Boolean = mockk(relaxed = true)
        val onMinimize: () -> Unit = mockk(relaxed = true)
        val onOpenFolderPicker: () -> Unit = mockk(relaxed = true)
        val onOpenRecentFolder: (Uri) -> Unit = mockk(relaxed = true)
        val onShowAbout: () -> Unit = mockk(relaxed = true)
        val safManager: SafStorageManager = mockk(relaxed = true)

        val bridge = AndroidBridge(
            context = context,
            security = security,
            clipboard = clipboard,
            onBackPressed = onBackPressed,
            onMinimize = onMinimize,
            onOpenFolderPicker = onOpenFolderPicker,
            onOpenRecentFolder = onOpenRecentFolder,
            onShowAbout = onShowAbout,
            safManager = safManager,
        )

        /** Everything the bridge can reach except the validator it is allowed to consult. */
        val collaborators = listOf(
            context, clipboard, onBackPressed, onMinimize,
            onOpenFolderPicker, onOpenRecentFolder, onShowAbout, safManager,
        )

        init {
            every { security.validateToken(any()) } returns false
        }
    }

    private lateinit var security: SecurityManager
    private lateinit var bridge: AndroidBridge

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
        every { Logger.d(any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs

        // Three methods do their work through these singletons rather than through anything
        // injected, so a bridge that ran on past a rejected token would touch neither the
        // context nor a collaborator and would look identical to one that refused. They are
        // stubbed so the real implementations cannot run, but the detection below is not
        // stub-shaped: `wasNot Called` catches any call, including ones added later.
        mockkObject(CrashReporter)
        every { CrashReporter.getLastCrash() } returns null
        every { CrashReporter.clearCrashLogs() } just Runs

        security = mockk(relaxed = true)
        every { security.validateToken(any()) } returns false

        bridge = AndroidBridge(
            context = mockk<Context>(relaxed = true),
            security = security,
            clipboard = mockk(relaxed = true),
            onBackPressed = { false },
            onMinimize = { },
        )
    }

    /** A value of the right type for each parameter; the call only has to reach the check. */
    private fun argFor(type: Class<*>): Any? = when (type) {
        String::class.java -> "not-the-session-token"
        Int::class.javaPrimitiveType, Int::class.java -> 0
        Long::class.javaPrimitiveType, Long::class.java -> 0L
        Boolean::class.javaPrimitiveType, Boolean::class.java -> false
        else -> null
    }

    private fun bridgeMethods(): List<Method> = AndroidBridge::class.java.declaredMethods
        .filter { it.isAnnotationPresent(JavascriptInterface::class.java) }
        .sortedBy { it.name }

    private fun Method.invokeQuietly(target: AndroidBridge) {
        try {
            invoke(target, *parameterTypes.map { argFor(it) }.toTypedArray())
        } catch (_: Throwable) {
            // Throwing on a rejected token is still a refusal; the verify decides.
        }
    }

    /**
     * Checking the signature instead would not do it. A first parameter of type String says
     * nothing about whether it is a token, and two methods here take an unrelated String
     * first — a shape check passes them while they consult nothing.
     */
    @Test
    fun `every bridge method consults the session token`() {
        val methods = bridgeMethods()
        check(methods.isNotEmpty()) {
            "No @JavascriptInterface methods found — this test would otherwise pass by " +
                "enumerating nothing"
        }

        val silent = mutableListOf<String>()
        for (m in methods) {
            clearMocks(security, answers = false)
            every { security.validateToken(any()) } returns false
            m.invokeQuietly(bridge)
            try {
                verify(atLeast = 1) { security.validateToken(any()) }
            } catch (_: Throwable) {
                silent.add(m.name)
            }
        }

        assertEquals(
            emptyList<String>(), silent,
            "these bridge methods never consult the session token, so they are exposed on " +
                "different terms from the other ${methods.size - silent.size}"
        )
    }

    /**
     * Asking is not obeying, and the test above cannot tell them apart: it verifies the call
     * happened, which stays true if the method calls the validator and drops the answer on
     * the floor. That mutation — `security.validateToken(t)` with the `if` and `return`
     * removed — was measured against the whole suite and no test went red.
     *
     * So this one asks for the effect instead: with a token the validator rejects, nothing
     * outside the bridge may be touched. It is the same shape as the bug class this project
     * keeps hitting — the predicate is pinned, the wiring is not — except here the wiring is
     * the guard itself.
     */
    @Test
    fun `a rejected token stops every bridge method before it acts`() {
        val methods = bridgeMethods()
        check(methods.isNotEmpty()) {
            "No @JavascriptInterface methods found — this test would otherwise pass by " +
                "enumerating nothing"
        }

        val acted = mutableListOf<String>()
        for (m in methods) {
            val f = Fixture()
            // Singletons outlive the fixture, so their call history has to be reset per
            // method or the first method that logs would condemn every method after it.
            clearMocks(Logger, CrashReporter, answers = false)
            val args = m.parameterTypes.map { argFor(it) }.toTypedArray()
            val reasons = mutableListOf<String>()

            // Returning early cannot throw, so anything thrown is the method having run on
            // past the check. This is the signal that catches methods whose work goes
            // through a static object rather than an injected collaborator — swallowing it
            // is what let eleven of them through an earlier version of this test.
            try {
                m.invoke(f.bridge, *args)
            } catch (e: InvocationTargetException) {
                reasons += "threw ${e.targetException.javaClass.simpleName}"
            }

            try {
                (f.collaborators + listOf(Logger, CrashReporter))
                    .forEach { c -> verify { c wasNot Called } }
            } catch (_: Throwable) {
                reasons += "touched a collaborator"
            }

            // The check itself is the only thing the validator may be used for.
            try {
                verify(exactly = 1) { f.security.validateToken(any()) }
                confirmVerified(f.security)
            } catch (_: Throwable) {
                reasons += "used SecurityManager for more than the check"
            }

            if (reasons.isNotEmpty()) acted.add("${m.name} (${reasons.joinToString("; ")})")
        }

        assertEquals(
            emptyList<String>(), acted,
            "these bridge methods reached past a rejected token, so they consult the " +
                "validator without obeying it"
        )
    }
}
