package com.vscodroid.setup

import android.content.DialogInterface
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import com.vscodroid.R
import com.vscodroid.ToolchainActivity
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.spyk
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.lang.reflect.Modifier
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

class ToolchainRetryOptionsTest {
    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `retry for an incomplete installed pack offers removal before starting another install`() {
        val manager = mockk<ToolchainManager>(relaxed = true)
        every { manager.getInstalledToolchains() } returns listOf("java")
        every { manager.getIncompleteToolchains() } returns listOf("toolchain_java")
        val screen = spyk(mockk<ToolchainActivity>(relaxed = true), recordPrivateCalls = true)
        every { screen.theme } returns mockk(relaxed = true)
        val adapter = mockk<ToolchainPickerAdapter>(relaxed = true)
        every { adapter.labelFor(any(), any()) } answers { secondArg() }
        every { screen.getString(R.string.progress_retry) } returns "Retry"
        every { screen.getString(R.string.toolchain_remove) } returns "Remove"
        fun field(name: String, value: Any) {
            ToolchainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.set(screen, value)
        }
        field("toolchainManager", manager)
        field("adapter", adapter)
        field("outstanding", mutableSetOf<String>())
        field("destroyed", AtomicBoolean(false))
        mockkConstructor(ContextThemeWrapper::class)
        every { anyConstructed<ContextThemeWrapper>().getSystemService(any()) } returns mockk<LayoutInflater>()
        mockkConstructor(AlertDialog.Builder::class)
        every { anyConstructed<AlertDialog.Builder>().setTitle(any<CharSequence>()) } answers { self as AlertDialog.Builder }
        var choices: List<String> = emptyList()
        var selected: DialogInterface.OnClickListener? = null
        every { anyConstructed<AlertDialog.Builder>().setItems(any<Array<CharSequence>>(), any()) } answers {
            choices = firstArg<Array<CharSequence>>().map { it.toString() }
            selected = secondArg()
            self as AlertDialog.Builder
        }
        every { anyConstructed<AlertDialog.Builder>().setNegativeButton(any<Int>(), any()) } answers { self as AlertDialog.Builder }
        var dismissed: DialogInterface.OnDismissListener? = null
        every { anyConstructed<AlertDialog.Builder>().setOnDismissListener(any()) } answers {
            dismissed = firstArg()
            self as AlertDialog.Builder
        }
        val dialog = mockk<AlertDialog>(relaxed = true)
        every { anyConstructed<AlertDialog.Builder>().show() } returns dialog
        every { screen invoke "showRemoveConfirmation" withArguments listOf("toolchain_java") } returns Unit

        val action = ToolchainActivity::class.java.declaredMethods.single {
            Modifier.isStatic(it.modifiers) && it.parameterTypes.toList() ==
                listOf(ToolchainActivity::class.java, String::class.java, ToolchainAction::class.java)
        }.apply { isAccessible = true }
        action.invoke(null, screen, "toolchain_java", ToolchainAction.RETRY)

        assertEquals(listOf("Retry", "Remove"), choices)
        verify(exactly = 0) { manager.install(any()) }
        selected!!.onClick(mockk(), 1)
        verify(exactly = 1) { screen invoke "showRemoveConfirmation" withArguments listOf("toolchain_java") }
        verify(exactly = 0) { manager.install(any()) }
        selected!!.onClick(mockk(), 0)
        verify(exactly = 1) { manager.install("toolchain_java") }
        @Suppress("UNCHECKED_CAST")
        val outstanding = ToolchainActivity::class.java.getDeclaredField("outstanding")
            .apply { isAccessible = true }.get(screen) as Set<String>
        assertTrue("toolchain_java" in outstanding)

        choices = emptyList()
        every { manager.getInstalledToolchains() } returns emptyList()
        action.invoke(null, screen, "toolchain_java", ToolchainAction.RETRY)
        assertTrue(choices.isEmpty(), "an unowned failed installation should retry directly")
        verify(exactly = 2) { manager.install("toolchain_java") }
        every { manager.getInstalledToolchains() } returns listOf("java")
        every { manager.getIncompleteToolchains() } returns emptyList()
        action.invoke(null, screen, "toolchain_java", ToolchainAction.RETRY)
        assertTrue(choices.isEmpty(), "a settled installation should not offer a repair choice")
        verify(exactly = 3) { manager.install("toolchain_java") }

        val destroy = ToolchainActivity::class.java.getDeclaredMethod("onDestroy").apply { isAccessible = true }
        try {
            destroy.invoke(screen)
        } catch (e: InvocationTargetException) {
            val cause = e.cause ?: throw e
            assertTrue(
                cause is RuntimeException &&
                    cause.message?.startsWith("Method onDestroy in android.app.Activity not mocked.") == true,
                "unexpected teardown failure: $cause",
            )
        }
        verify(exactly = 1) { dialog.dismiss() }
        dismissed!!.onDismiss(dialog)
        assertEquals(null, ToolchainActivity::class.java.getDeclaredField("retryDialog")
            .apply { isAccessible = true }.get(screen))
    }
}
