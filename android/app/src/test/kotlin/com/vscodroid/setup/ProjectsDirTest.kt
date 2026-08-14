package com.vscodroid.setup

import android.content.Context
import com.vscodroid.util.Environment
import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The projects directory is the only one this app creates that anything outside
 * it can delete: it lives in app-external storage, visible in every file
 * manager. It was created once per version, so once it was gone it stayed gone
 * through every relaunch until the app was updated or its data cleared.
 */
class ProjectsDirTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var setup: FirstRunSetup
    private lateinit var projects: File

    @BeforeEach
    fun setUp() {
        mockkObject(Logger)
        every { Logger.i(any(), any()) } just Runs
        every { Logger.w(any(), any()) } just Runs

        projects = File(tempDir, "projects")
        mockkObject(Environment)
        every { Environment.getProjectsDir(any()) } returns projects.absolutePath

        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir
        every { context.cacheDir } returns tempDir
        setup = FirstRunSetup(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    @Test
    fun `recreates the directory after it is deleted`() {
        assertTrue(projects.mkdirs())
        assertTrue(projects.delete(), "the fixture must actually remove it")
        assertFalse(projects.exists(), "precondition: the directory is gone")

        setup.ensureProjectsDir()

        assertTrue(projects.isDirectory, "a deleted projects directory must come back")
    }

    @Test
    fun `leaves an existing directory alone and still answers with it`() {
        assertTrue(projects.mkdirs())
        val marker = File(projects, "keep.txt").apply { writeText("user work") }

        val path = setup.ensureProjectsDir()

        assertEquals(projects.absolutePath, path)
        assertTrue(marker.exists(), "repairing must not disturb what is already there")
    }

    @Test
    fun `a plain file at the path is reported rather than passed over`() {
        // Neither exists() nor isDirectory() can turn a file into a directory,
        // so the difference between them is not the outcome -- it is whether
        // anyone is told. exists() answers yes here and returns in silence,
        // handing the workbench a path it cannot open with nothing in the log.
        // So what is asserted is the warning, not the state.
        projects.parentFile?.mkdirs()
        projects.writeText("not a directory")

        setup.ensureProjectsDir()

        assertFalse(projects.isDirectory, "a file cannot become a directory")
        verify(atLeast = 1) { Logger.w(any(), match { it.contains("Could not recreate") }) }
    }
}
