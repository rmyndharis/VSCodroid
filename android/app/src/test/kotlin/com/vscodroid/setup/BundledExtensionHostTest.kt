package com.vscodroid.setup

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import java.io.File

/**
 * The bundled extensions talk to Android through a BroadcastChannel that
 * [com.vscodroid.MainActivity.injectBridgeRelay] opens in the WebView page. Only an
 * extension running in the **web** extension host shares that page's realm; one declared
 * with `main` runs in the Node extension host on the server, where `BroadcastChannel`
 * resolves to the unrelated class from `node:worker_threads`.
 *
 * Nothing at build or install time notices the mismatch — the extension loads, its
 * commands appear in the palette, and every call silently times out after five seconds.
 * This test is what notices.
 */
class BundledExtensionHostTest {

    private val extensionsDir = File("src/main/assets/extensions")

    private fun ourExtensions(): List<File> =
        extensionsDir.listFiles { f -> f.isDirectory && f.name.startsWith("vscodroid.vscodroid-") }
            ?.sortedBy { it.name }
            ?: emptyList()

    @TestFactory
    fun `an extension that uses the bridge relay declares a browser entry point`(): List<DynamicTest> {
        val extensions = ourExtensions()
        check(extensions.isNotEmpty()) {
            "No bundled extensions found under ${extensionsDir.absolutePath} — the test is " +
                "looking in the wrong place, which would let it pass by finding nothing"
        }

        return extensions.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val source = File(dir, "extension.js").takeIf { it.isFile }?.readText().orEmpty()
                if (!source.contains("BroadcastChannel")) return@dynamicTest

                // Not org.json: on this project's unit-test classpath every method on
                // JSONObject throws "not mocked", so parsing here would fail for a reason
                // that has nothing to do with the manifest.
                val manifest = File(dir, "package.json").readText()
                assertTrue(
                    Regex("""^\s*"browser"\s*:""", RegexOption.MULTILINE).containsMatchIn(manifest),
                    "${dir.name} talks to the bridge relay over BroadcastChannel, so it has to run " +
                        "in the web extension host — but its manifest declares no browser entry " +
                        "point. Loaded on the Node host, every bridge call times out."
                )
            }
        }
    }
}
