package com.vscodroid.bridge

import android.content.Context
import android.net.Uri
import com.vscodroid.storage.SafStorageManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * That reopening a device folder from the workbench's recent list does not put the
 * user's directory into logcat.
 *
 * The same subject as `SafFolderPathLoggingTest`, one layer up: that class watches the
 * manager, and this one watches the bridge method the editor calls. A SAF tree URI
 * spells the folder out (`.../tree/primary%3ADocuments%2F<folder>`), `Logger.i` is not
 * gated on a debuggable build, and this is the ordinary success path, so the folder
 * name shipped on every reopen and travelled in every device bug report.
 *
 * Asserted at `android.util.Log` rather than at `Logger`, for the reason that class
 * gives: a line can leak through its message or through a throwable it hands over, and
 * only the sink sees both.
 *
 * Negative controls, both measured. Restoring the line to
 * `Logger.i(tag, "Opening recent SAF folder: ${'$'}{redactToken(uri.toString())}")` reddens
 * BOTH cases, and that is the point of the pair rather than a redundancy: the first
 * because `redactToken` replaces `tkn=` and nothing else, so a tree URI passes through
 * it exactly as it arrived, and the second because a line naming the URI names no
 * mirror. Dropping the interpolation instead, so the line reads "Opening recent device
 * folder" and identifies nothing, reddens only the second, which is the half that
 * refuses a fix that deletes the record along with the secret.
 */
class RecentFolderLoggingTest {

    @TempDir
    lateinit var filesDir: File

    private val treeUri =
        "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FClientProject"

    /** The part of that URI a reader could turn back into a place on the device. */
    private val folderName = "ClientProject"

    /** Every string the app handed the log, message and throwable alike. */
    private val emitted = mutableListOf<String>()

    private val security = SecurityManager()
    private lateinit var context: Context
    private lateinit var safManager: SafStorageManager

    @BeforeEach
    fun setUp() {
        emitted.clear()

        mockkStatic(android.util.Log::class)
        every { android.util.Log.i(any(), any()) } answers { note(args); 0 }
        every { android.util.Log.d(any(), any()) } answers { note(args); 0 }
        every { android.util.Log.w(any(), any<String>()) } answers { note(args); 0 }
        every { android.util.Log.w(any(), any<String>(), any<Throwable>()) } answers { note(args); 0 }
        every { android.util.Log.e(any(), any()) } answers { note(args); 0 }
        every { android.util.Log.e(any(), any<String>(), any<Throwable>()) } answers { note(args); 0 }

        val folderUri = mockk<Uri>(relaxed = true)
        every { folderUri.toString() } returns treeUri
        mockkStatic(Uri::class)
        every { Uri.parse(treeUri) } returns folderUri

        context = mockk(relaxed = true)
        every { context.filesDir } returns filesDir
        // The manager unwraps to the application context, so a mock that answers a
        // different object for it would hand the manager a different filesDir.
        every { context.applicationContext } returns context
        safManager = SafStorageManager(context)
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun note(args: List<Any?>) {
        args.forEach { arg ->
            emitted += when (arg) {
                is Throwable -> arg.toString() + (arg.message ?: "")
                else -> arg.toString()
            }
        }
    }

    /** A bridge with the SAF wiring, which is what `MainActivity` builds. */
    private fun bridge(): AndroidBridge = AndroidBridge(
        context = context,
        security = security,
        clipboard = mockk(relaxed = true),
        onBackPressed = mockk(relaxed = true),
        onMinimize = mockk(relaxed = true),
        safManager = safManager,
    )

    @Test
    fun `opening a recent folder does not log the user's directory`() {
        bridge().openRecentFolder(security.getSessionToken(), treeUri)

        assertTrue(
            emitted.isNotEmpty(),
            "nothing reached the log at all, so this run proves nothing about what does",
        )
        val leaked = emitted.filter { it.contains(folderName) || it.contains("tree/primary") }
        assertTrue(leaked.isEmpty(), "the user's device folder reached release logcat: $leaked")
    }

    /**
     * The control. Redaction that leaves nothing behind is deletion, and a bug report
     * still has to be able to line this line up with the folder it is about. The
     * mirror's own name is what does that, and it is what every other line about the
     * folder already uses.
     */
    @Test
    fun `the line still identifies the folder by its mirror name`() {
        val hash = safManager.getMirrorDir(Uri.parse(treeUri)).name

        bridge().openRecentFolder(security.getSessionToken(), treeUri)

        assertTrue(
            emitted.any { it.contains(hash) },
            "no line named the folder at all, so the redaction removed the record rather " +
                "than the secret: $emitted",
        )
    }
}
