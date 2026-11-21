package com.vscodroid

import com.vscodroid.service.NodeService
import com.vscodroid.service.StartupNotice
import com.vscodroid.service.mockEditorGateway
import com.vscodroid.util.AppNotices
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * "The server is taking longer than usual to start" is a dialog from Android 12, and nothing took it
 * down when the server did start: it stayed over the working editor until it was tapped.
 *
 * It is taken back by its topic, not its text: a screen made again for a language change carries the
 * old language's text, which the new language's string does not match. And it is taken back where a
 * screen binds to a server already ready, which is how a server that came up while no screen was bound
 * reaches the dialog the screen before carried over. Run on an activity and a service whose framework
 * halves are mocked.
 */
class SlowStartNoticeTest {
    private lateinit var activity: MainActivity
    private lateinit var service: NodeService
    private val events = mutableListOf<String>()
    private var ready: ((Int, Boolean) -> Unit)? = null
    private var error: ((String) -> Unit)? = null
    private val slow = NodeService.TOPIC_SLOW_START

    private fun setField(target: Any, type: Class<*>, name: String, value: Any?) =
        type.getDeclaredField(name).apply { isAccessible = true }.set(target, value)

    private fun bind() {
        every { activity["setupServiceCallbacks"]() } answers { callOriginal() }
        MainActivity::class.java.getDeclaredMethod("setupServiceCallbacks").apply { isAccessible = true }.invoke(activity)
    }

    @BeforeEach
    fun setUp() {
        mockEditorGateway().bind(13337)
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.e(any(), any(), any()) } just Runs
        mockkObject(AppNotices)
        every { AppNotices.retractTopic(any(), any()) } answers { events += "retract ${secondArg<String>()}" }
        every { AppNotices.showNotice(any(), any<CharSequence>(), any(), any(), any(), any()) } answers {
            events += "notice ${secondArg<CharSequence>()} topic=${arg<String?>(5)}"
        }
        activity = mockk(relaxed = true)
        service = mockk(relaxed = true)
        setField(activity, MainActivity::class.java, "tag", "MainActivity")
        setField(activity, MainActivity::class.java, "nodeService", service)
        every { activity.runOnUiThread(any()) } answers { firstArg<Runnable>().run() }
        every { activity["loadVSCode"](any<Int>(), isNull<String>(), isNull<String>()) } answers { events += "load" }
        every { activity["showServerGaveUpWithLog"]() } answers { events += "gave-up" }
        every { service.onServerReady = any() } answers { ready = firstArg() }
        every { service.onServerError = any() } answers { error = firstArg() }
        every { service.getPort() } returns 13337
        every { service.isServerReady() } returns false
        every { service.lastStartupNotice() } returns null
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `a server that comes up takes the slow start notice back by its topic, then loads`() {
        bind()
        ready!!(13337, false)

        assertEquals(listOf("retract $slow", "load"), events)
    }

    @Test
    fun `a screen that binds to a server already up takes back the notice the screen before carried`() {
        every { service.isServerReady() } returns true
        bind()

        assertEquals(listOf("retract $slow", "load"), events)
    }

    @Test
    fun `a notice raised to a bound screen carries the topic it is taken back by`() {
        every { service.lastStartupNotice() } returns null
        bind()
        every { service.lastStartupNotice() } returns StartupNotice("Starting slowly", terminal = false, topic = slow)
        error!!("Starting slowly")
        error!!("Something else")

        assertEquals(listOf("notice Starting slowly topic=$slow", "notice Something else topic=null"), events)
    }

    @Test
    fun `a notice read when a screen binds carries its topic`() {
        every { service.lastStartupNotice() } returns StartupNotice("Starting slowly", terminal = false, topic = slow)
        bind()

        assertEquals(listOf("notice Starting slowly topic=$slow"), events)
    }

    @Test
    fun `a screen that binds after the server gave up shows the page and no notice under it`() {
        every { service.lastStartupNotice() } returns StartupNotice("Server stopped", terminal = true)
        bind()

        assertEquals(listOf("gave-up"), events)
    }

    /**
     * A terminal notice is followed at once by the gave-up page, which takes a notice down as it comes up:
     * raised as well, it was a dialog that flashed wherever the text is too long for a toast.
     */
    @Test
    fun `the service records a terminal notice without raising it, and raises any other`() {
        val node = mockk<NodeService>(relaxed = true)
        every { node["reportStartupNotice"](any<String>(), any<Boolean>(), any<String>()) } answers { callOriginal() }
        every { node.lastStartupNotice() } answers { callOriginal() }
        every { node.onServerError } returns { message: String -> events += "raised $message" }
        val report = NodeService::class.java.getDeclaredMethod(
            "reportStartupNotice", String::class.java, Boolean::class.javaPrimitiveType, String::class.java,
        ).apply { isAccessible = true }

        report.invoke(node, "Server stopped", true, null)
        assertEquals(StartupNotice("Server stopped", terminal = true), node.lastStartupNotice())
        report.invoke(node, "Starting slowly", false, slow)
        assertEquals(StartupNotice("Starting slowly", terminal = false, topic = slow), node.lastStartupNotice())

        assertEquals(listOf("raised Starting slowly"), events)
    }
}
