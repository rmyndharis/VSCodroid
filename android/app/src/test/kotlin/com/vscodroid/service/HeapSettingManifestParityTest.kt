package com.vscodroid.service

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The setting the workbench offers and the setting Kotlin honours must be one setting.
 *
 * This exists because the failure it prevents is completely silent. The key is
 * declared twice: once as [HEAP_SETTING_KEY], which is what
 * `ProcessManager.heapOverrideFromSettings` searches the user's `settings.json`
 * for, and once in the bundled process-monitor extension's
 * `contributes.configuration`, which is the only reason the setting appears in the
 * workbench Settings UI at all and the only reason the editor writes it to the file
 * Kotlin reads. Rename or retype either side and the setting still shows up, still
 * accepts a value, still writes it to disk, and does nothing. Nothing fails to
 * compile, no test would go red, and no log line would say so. This project already
 * carries one of those: `AndroidBridge.openToolchainSettings` is a command with no
 * sender, and it took a long time to notice.
 *
 * The manifest is read from the assets tree rather than from an installed copy on a
 * device, which is the right window: what an extension contributes is decided by
 * what the APK ships, and a device only ever gets a copy of this file.
 *
 * WHAT THIS DOES NOT CHECK, stated so a green is not read for more than it is.
 *
 *  - It does not check that the workbench honours `scope: machine` by writing to
 *    `Machine/settings.json`. That is the server's behaviour, settled by the
 *    remote environment payload naming `machineSettingsResource` as `settingsPath`,
 *    and no unit test here can reach it.
 *  - It does not check that a user who never opens Settings can find the key. The
 *    manifest makes it searchable; whether that is discoverable enough is a
 *    product question, not one a test answers.
 *  - `maximum` is documentation. The enforcement is
 *    `ProcessManager.heapOverrideMaxMb`, because a user can edit the JSON by hand
 *    and the workbench does not stop them. This asserts the two agree so the UI
 *    does not offer a number the clamp will quietly take away, and that is all it
 *    means.
 */
class HeapSettingManifestParityTest {

    /**
     * The bundled extension's manifest, found by prefix rather than by version.
     *
     * The version in the directory name moves whenever this extension changes, and
     * a test pinned to it would fail on the bump rather than on the drift it exists
     * to catch. Resolved relative to the Gradle test working directory, which is the
     * module directory (`android/app`).
     */
    private fun manifest(): JSONObject {
        val extensions = File("src/main/assets/extensions")
        assertTrue(
            extensions.isDirectory,
            "extensions not found at ${extensions.absolutePath}; this test resolves paths " +
                "relative to the Gradle test working directory, which is the module dir",
        )
        val dirs = extensions.listFiles()
            .orEmpty()
            .filter { it.isDirectory && it.name.startsWith("vscodroid.vscodroid-process-monitor-") }
        assertEquals(
            1, dirs.size,
            "expected exactly one bundled process-monitor directory, found: ${dirs.map { it.name }}",
        )
        return JSONObject(File(dirs.single(), "package.json").readText())
    }

    private fun heapProperty(): JSONObject {
        val properties = manifest()
            .getJSONObject("contributes")
            .getJSONObject("configuration")
            .getJSONObject("properties")
        assertTrue(
            properties.has(HEAP_SETTING_KEY),
            "the manifest contributes ${properties.keys().asSequence().toList()}, which does " +
                "not include the key Kotlin reads ($HEAP_SETTING_KEY); a setting named on " +
                "only one side is a setting that appears in the UI and does nothing",
        )
        return properties.getJSONObject(HEAP_SETTING_KEY)
    }

    @Test
    fun `the manifest contributes exactly the key Kotlin reads`() {
        heapProperty()
    }

    @Test
    fun `the key is machine-scoped, or the editor writes it somewhere Kotlin never looks`() {
        // Not cosmetic. A machine-scoped setting in a remote window is written to
        // the remote machine settings file, which is what Environment names and
        // what heapOverrideFromSettings reads. Any other scope and the value goes
        // to the web client's own user data, which lives in the WebView's
        // IndexedDB, where no Kotlin can reach it. This project has already made
        // the neighbouring mistake of writing to User/settings.json.
        assertEquals("machine", heapProperty().getString("scope"))
    }

    @Test
    fun `the bounds the UI offers are the bounds the clamp enforces`() {
        // The direction that matters is the upper one: a UI offering more than the
        // clamp allows teaches the user that the app ignores what they typed.
        assertEquals(HEAP_OVERRIDE_ABS_MAX_MB, heapProperty().getInt("maximum"))
        assertEquals(HEAP_CEILING_MIN_MB, heapProperty().getInt("minimum"))
    }

    @Test
    fun `an unset value is what the manifest defaults to`() {
        // The derived arm must be what an untouched install runs, so the default
        // has to be absent rather than a number. A numeric default here would put
        // the key into every user's settings.json the first time the editor wrote
        // that file, turning the override on for people who never asked for it.
        assertTrue(heapProperty().isNull("default"), "the default must be null, not a number")
    }
}
