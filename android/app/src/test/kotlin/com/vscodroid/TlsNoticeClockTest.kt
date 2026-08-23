package com.vscodroid

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The clock the certificate notice measures its interval against.
 *
 * `tlsFailureToAnnounce` and `handoffFailureToAnnounce` are one rule with two
 * records, and past their cap that rule is a rate: one more notice per
 * `NOTICE_INTERVAL_MS`. The hand-off channel takes its reading from a default
 * inside the function and its own documentation says why it must be monotonic.
 * The certificate channel takes it from this Activity, because the reading it
 * compares against is a field here, so the same choice has to be made again at
 * the call site and nothing in the type system says so. It was made wrongly:
 * `System.currentTimeMillis()` steps backwards on an NTP correction or when the
 * user changes the device time, and a backward step larger than the interval
 * makes the subtraction negative, which silences the channel until the clock
 * catches up. What is silenced is the only thing that tells a developer their
 * https dev server was refused for its certificate rather than not running.
 *
 * Read from the source, which is the weaker layer in this suite: the subject is
 * an argument inside a lambda handed to a WebViewClient by an Activity, and no
 * plain JVM test can build one. The rule itself is pure and pinned by
 * `TlsFailureNoticeTest`; this buys only that the call site feeds it the right
 * clock.
 */
class TlsNoticeClockTest {

    private val source = SourceScan.read("src/main/kotlin/com/vscodroid/MainActivity.kt")

    @Test
    fun `the certificate notice measures its interval on the monotonic clock`() {
        val notice = SourceScan.withoutComments(SourceScan.body(source, "onTlsFailure = {"))

        assertTrue(notice.contains("tlsFailureToAnnounce(")) {
            "the certificate notice no longer goes through the throttle, so this case is " +
                "measuring nothing and every refused certificate is its own toast"
        }
        assertTrue(!notice.contains("currentTimeMillis")) {
            "the interval is measured against the wall clock, which steps backwards on an " +
                "NTP correction and on a user changing the device time; a step larger than " +
                "NOTICE_INTERVAL_MS silences the channel until it catches up: $notice"
        }
        assertTrue(notice.contains("SystemClock.elapsedRealtime()")) {
            "no reading is taken at the call site at all, so whatever is passed as `now` " +
                "comes from somewhere this case cannot see: $notice"
        }
    }
}
