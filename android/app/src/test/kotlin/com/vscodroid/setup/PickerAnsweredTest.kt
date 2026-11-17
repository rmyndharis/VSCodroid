package com.vscodroid.setup

import com.vscodroid.SourceScan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When the first-run toolchain picker counts as answered.
 *
 * Continue with something ticked starts a queue of downloads that lives in this process, one pack
 * at a time. Marking the picker answered at the tap meant a process that ended in the middle (the
 * person left the app and the system reclaimed it) lost every pack not yet requested, and nothing
 * offered them again: the picker never came back and the cards on the Toolchains screen read Install.
 * So a Continue that chose something answers the picker where the queue ends, which is also where
 * Cancel ends it; Skip, and a Continue that chose nothing new, answer it at once.
 *
 * Read from the source, because the screen cannot be made on the JVM.
 */
class PickerAnsweredTest {

    private val splash by lazy {
        SourceScan.withoutComments(SourceScan.read("src/main/kotlin/com/vscodroid/SplashActivity.kt"))
    }

    private fun count(text: String) = Regex("""\bmarkPickerShown\(\)""").findAll(text).count()

    @Test
    fun `a continue that chose something does not answer the picker before its downloads end`() {
        val onContinue = splash.substringAfter("continueBtn.setOnClickListener {").substringBefore("skipBtn.setOnClickListener")
        val chosen = onContinue.substringAfter("} else {")
        assertTrue(chosen.contains("startDownloads(selected)"), "the branch that starts the downloads moved")
        assertEquals(0, count(chosen), "the picker is answered before the queue of downloads has ended")
    }

    @Test
    fun `a continue that chose nothing new and a skip answer the picker at once`() {
        val onContinue = splash.substringAfter("continueBtn.setOnClickListener {").substringBefore("skipBtn.setOnClickListener")
        val nothing = onContinue.substringAfter("if (selected.isEmpty()) {").substringBefore("} else {")
        assertEquals(1, count(nothing), "a continue with nothing to download no longer answers the picker")
        assertTrue(nothing.indexOf("markPickerShown()") < nothing.indexOf("launchMain()"))
        val onSkip = splash.substringAfter("skipBtn.setOnClickListener {").substringBefore("}")
        assertEquals(1, count(onSkip), "skip no longer answers the picker")
    }

    @Test
    fun `the end of the queue, from the last pack or from cancel, answers the picker`() {
        val end = SourceScan.body(splash, "private fun endDownloadQueue(")
        assertEquals(1, count(end), "the picker is never answered once the downloads are over")
        assertTrue(end.indexOf("markPickerShown()") < end.indexOf("unregisterListener()"))
        val next = SourceScan.body(splash, "private fun downloadNext(")
        assertTrue(next.contains("endDownloadQueue()"), "the last pack no longer ends the queue")
        val cancel = splash.substringAfter("cancelBtn.setOnClickListener {").substringBefore("// Start first download")
        assertTrue(cancel.contains("endDownloadQueue()"), "cancel no longer ends the queue, so it never answers the picker")
    }
}
