package com.vscodroid.setup

import com.vscodroid.BuildConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AbiSelectionTest {
    @Test
    fun `Node architecture follows the APK ABI`() {
        assertEquals("arm64", nodeArchForAbi("arm64-v8a"))
        assertEquals("x64", nodeArchForAbi("x86_64"))
        assertEquals(
            if (BuildConfig.VSCODROID_ABI == "x86_64") "x64" else "arm64",
            nodeArchForAbi(BuildConfig.VSCODROID_ABI),
        )
    }

    @Test
    fun `optional toolchain URLs select the matching release asset`() {
        val rubyUrl = ToolchainRegistry.find("toolchain_ruby")!!.downloadUrl!!
        assertEquals(rubyUrl, toolchainUrlForAbi(rubyUrl, "toolchain_ruby", "arm64-v8a"))
        assertEquals(
            rubyUrl.removeSuffix(".zip") + "-x86_64.zip",
            toolchainUrlForAbi(rubyUrl, "toolchain_ruby", "x86_64"),
        )
    }

    @Test
    fun `optional toolchain manifests reject a different ABI`() {
        assertTrue(toolchainManifestMatchesAbi("toolchain_ruby", "x86_64", "x86_64"))
        assertFalse(toolchainManifestMatchesAbi("toolchain_ruby", "arm64-v8a", "x86_64"))
        assertFalse(toolchainManifestMatchesAbi("toolchain_java", "x86_64", "arm64-v8a"))
        assertTrue(toolchainManifestMatchesAbi("toolchain_java", "", "arm64-v8a"))
    }

    @Test
    fun `wheelhouse page follows the APK ABI`() {
        assertTrue(wheelhouseUrl("3.14", "arm64-v8a").endsWith("/wheels.html"))
        assertTrue(wheelhouseUrl("3.14", "x86_64").endsWith("/wheels-x86_64.html"))
        assertTrue(pipConfigContent("3.14", "x86_64").contains("wheels-x86_64.html"))
    }
}
