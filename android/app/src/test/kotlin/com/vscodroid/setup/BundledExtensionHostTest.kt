package com.vscodroid.setup

import org.json.JSONObject
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
 * Nothing at build or install time notices the mismatch: the extension loads, its
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
            "No bundled extensions found under ${extensionsDir.absolutePath}; the test is " +
                "looking in the wrong place, which would let it pass by finding nothing"
        }

        return extensions.map { dir ->
            DynamicTest.dynamicTest(dir.name) {
                val source = File(dir, "extension.js").takeIf { it.isFile }?.readText().orEmpty()
                if (!source.contains("BroadcastChannel")) return@dynamicTest

                // Parsed, not pattern-matched. The question is whether the manifest
                // declares a browser entry point at the top level, and only a parser
                // answers that: `^\s*"browser"\s*:` under MULTILINE matches a line
                // starting at any nesting depth, so a `browser` key buried inside
                // some other object satisfied it while the extension still loaded on
                // the Node host -- the exact failure this test exists to catch. It
                // was wrong in the other direction too, since a manifest written on
                // one line has no line start to anchor to and would be failed for
                // its formatting.
                //
                // The comment that justified the regex said org.json throws "not
                // mocked" here. That was true of the android.jar stub and stopped
                // being true when build.gradle.kts added the real org.json to the
                // test classpath; measured, JSONObject parses on this classpath.
                val manifest = JSONObject(File(dir, "package.json").readText())
                assertTrue(
                    manifest.optString("browser").isNotBlank(),
                    "${dir.name} talks to the bridge relay over BroadcastChannel, so it has to run " +
                        "in the web extension host, but its manifest declares no browser entry " +
                        "point. Loaded on the Node host, every bridge call times out."
                )
            }
        }
    }
}
