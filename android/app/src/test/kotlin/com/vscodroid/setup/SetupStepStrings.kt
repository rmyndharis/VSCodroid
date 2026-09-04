package com.vscodroid.setup

import android.content.Context
import com.vscodroid.R
import io.mockk.every
import java.io.File

/**
 * Teaches a mocked `Context` the labels first-run setup reports.
 *
 * The nine step names moved out of `FirstRunSetup` and into `strings.xml` when
 * the app was translated, so a mocked context answers `getString` with an empty
 * string and every assertion about which step was reported, or which one
 * failed, silently compares against "". Two tests went red that way and would
 * have gone quietly green again on any wording change.
 *
 * The values are read from the real `values/strings.xml` rather than repeated
 * here, so a test cannot assert text the app no longer ships. Only the English
 * file: a translation is not what these tests are about, and the name-to-id map
 * below is the one thing that cannot be derived, since the ids are generated.
 */
internal object SetupStepStrings {

    private val ids = mapOf(
        "setup_step_directories" to R.string.setup_step_directories,
        "setup_step_server" to R.string.setup_step_server,
        "setup_step_bootstrap" to R.string.setup_step_bootstrap,
        "setup_step_tools" to R.string.setup_step_tools,
        "setup_step_git" to R.string.setup_step_git,
        "setup_step_symlinks" to R.string.setup_step_symlinks,
        "setup_step_extensions" to R.string.setup_step_extensions,
        "setup_step_environment" to R.string.setup_step_environment,
        "setup_step_done" to R.string.setup_step_done,
    )

    /**
     * Paths resolve from the Gradle test working directory, which is the module
     * directory (`android/app`), the same assumption [DisplayLanguageTest]
     * documents.
     */
    private val strings: Map<String, String> by lazy {
        val file = File("src/main/res/values/strings.xml")
        check(file.isFile) { "${file.absolutePath} not found; tests run from android/app" }
        val text = file.readText()
        ids.keys.associateWith { name ->
            val match = Regex("""<string name="$name">(.*?)</string>""").find(text)
                ?: error("values/strings.xml has no $name; the app and this helper disagree")
            // The file writes its ellipsis as the escape Android resources use.
            match.groupValues[1].replace("\\u2026", "…")
        }
    }

    /** The English text of one step, for a test that asserts on what was reported. */
    fun text(name: String): String =
        strings[name] ?: error("$name is not a setup step")

    /** Answers `getString` for all nine, on a mock that otherwise returns "". */
    fun stub(context: Context) {
        for ((name, id) in ids) {
            every { context.getString(id) } returns strings.getValue(name)
        }
    }
}
