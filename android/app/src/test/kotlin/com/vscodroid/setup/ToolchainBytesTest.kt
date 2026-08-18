package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for [toolchainBytesFor], which tells the storage pre-flight how much of
 * `filesDir` the installed toolchains already occupy.
 *
 * One direction is expensive and the other is not, which is why the withdrawn
 * case is first. Counting too much costs a user one round of freeing space they
 * did not strictly need to free. Counting too little credits the extraction
 * space that is not there, so the pre-flight admits a device it should have
 * refused, and the user gets "Setup failed" partway through with no figure to
 * act on, on every retry, because the toolchain stays where it is.
 *
 * A withdrawn toolchain is the likeliest thing to be sitting on a device on the
 * launch that matters: the retirement sweep has not run yet the first time the
 * pre-flight asks.
 */
class ToolchainBytesTest {

    @Test
    fun `a withdrawn toolchain still occupies its space`() {
        assertEquals(179_000_000L, toolchainBytesFor(listOf("go")))
    }

    @Test
    fun `and does so under either name the record may use`() {
        assertEquals(179_000_000L, toolchainBytesFor(listOf("toolchain_go")))
    }

    @Test
    fun `an offered toolchain is read from the registry`() {
        val ruby = ToolchainRegistry.find("ruby")!!.estimatedSize
        assertEquals(ruby, toolchainBytesFor(listOf("ruby")))
    }

    @Test
    fun `withdrawn and offered are counted together`() {
        val java = ToolchainRegistry.find("java")!!.estimatedSize
        assertEquals(179_000_000L + java, toolchainBytesFor(listOf("go", "java")))
    }

    @Test
    fun `a name this build has never heard of contributes nothing`() {
        assertEquals(0L, toolchainBytesFor(listOf("cobol")))
    }

    @Test
    fun `nothing installed is nothing occupied`() {
        assertEquals(0L, toolchainBytesFor(emptyList()))
    }
}
