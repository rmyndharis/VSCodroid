package com.vscodroid

import com.vscodroid.setup.ToolchainFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Tests for [reasonToAnnounce], which decides whether a failed pack's reason is
 * worth a Toast or has been said already.
 *
 * The reason itself is the point of the callback, so the rule cannot be "say it
 * once and never again": it is per cause, per run of the download queue. A
 * first-run pick of three toolchains that all fail on a full disk is one fact
 * the user can act on, and Toasts queue rather than replace, so saying it three
 * times holds roughly ten seconds of the screen they moved on to. Two different
 * causes are two facts and both still get through.
 *
 * Same boundary as the other predicates lifted out of SplashActivity: these pin
 * the rule, not the call. [DownloadStateWiringTest] covers the call.
 */
class AnnouncedReasonTest {

    private val alreadySaid = mutableSetOf<ToolchainFailure>()

    @Test
    fun `a first failure is announced`() {
        assertEquals(
            ToolchainFailure.STORAGE,
            reasonToAnnounce(ToolchainFailure.STORAGE, alreadySaid),
        )
    }

    @Test
    fun `the same cause twice is announced once`() {
        // The queue starts the next download the moment one fails, so packs
        // sharing a cause fail back to back with nothing between the messages.
        reasonToAnnounce(ToolchainFailure.STORAGE, alreadySaid)

        assertNull(
            reasonToAnnounce(ToolchainFailure.STORAGE, alreadySaid),
            "a second pack out of space adds nothing the user can act on",
        )
    }

    @Test
    fun `a second cause is still announced`() {
        // The half that makes this a filter rather than a mute. Getting it wrong
        // is worse than the repeat: the user is told to free space and never told
        // the next pack failed for a reason freeing space will not fix.
        reasonToAnnounce(ToolchainFailure.STORAGE, alreadySaid)

        assertEquals(
            ToolchainFailure.NETWORK,
            reasonToAnnounce(ToolchainFailure.NETWORK, alreadySaid),
            "a different cause is a different fact and has not been said",
        )
    }

    @Test
    fun `progress carries no reason and says nothing`() {
        // Every non-failure status arrives here with a null reason, which is most
        // of them: percentage updates land on this line several times a second.
        assertNull(reasonToAnnounce(null, alreadySaid))
    }

    @Test
    fun `a later run of the queue may repeat itself`() {
        // The set is built in startDownloads, so it lives exactly as long as one
        // pass over the picked toolchains. A user who frees space and installs
        // again has to be told why it failed the second time too.
        reasonToAnnounce(ToolchainFailure.STORAGE, alreadySaid)

        assertEquals(
            ToolchainFailure.STORAGE,
            reasonToAnnounce(ToolchainFailure.STORAGE, mutableSetOf()),
        )
    }
}
