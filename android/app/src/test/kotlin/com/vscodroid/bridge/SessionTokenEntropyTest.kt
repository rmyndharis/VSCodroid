package com.vscodroid.bridge

import com.vscodroid.util.Logger
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The session token gates every `@JavascriptInterface` method on the bridge. The
 * existing tests assert its *shape*: non-null, 64 lowercase hex characters, stable
 * within an instance, different between two instances.
 *
 * None of those is unpredictability. Replacing `SecureRandom().nextBytes()` with a
 * `System.nanoTime()` write leaves every one of them green — the value is still 64 hex
 * characters, still stable per instance, and still different between two instances
 * built microseconds apart — while making the token guessable from the launch time.
 *
 * These tests assert the property that matters instead.
 */
class SessionTokenEntropyTest {

    @BeforeEach
    fun silenceLogger() {
        mockkObject(Logger)
        every { Logger.w(any(), any(), any()) } just Runs
        every { Logger.i(any(), any()) } just Runs
    }

    private fun tokens(n: Int): List<String> = List(n) { SecurityManager().getSessionToken() }

    @Test
    fun `every byte position varies across a large sample`() {
        // The nanoTime mutation leaves bytes 8..31 permanently zero. A clock-derived
        // token also freezes its high-order bytes, since they only change over hours.
        val sample = tokens(200)
        val constantPositions = (0 until 64).filter { i ->
            sample.map { it[i] }.distinct().size == 1
        }
        assertEquals(
            emptyList<Int>(), constantPositions,
            "hex positions $constantPositions never varied across 200 tokens, so those " +
                "bits carry no entropy"
        )
    }

    @Test
    fun `a large sample contains no repeats`() {
        val sample = tokens(500)
        assertEquals(sample.size, sample.distinct().size, "two tokens collided")
    }

    @Test
    fun `consecutive tokens do not share a long prefix`() {
        // Anything derived from a clock produces neighbours that agree for most of
        // their length. Random 256-bit values agree on the first byte 1 time in 256.
        val sample = tokens(50)
        val longestShared = sample.zipWithNext().maxOf { (a, b) ->
            a.commonPrefixWith(b).length
        }
        assertTrue(
            longestShared < 8,
            "consecutive tokens shared $longestShared leading hex characters, which is " +
                "what a counter or a clock looks like"
        )
    }

    @Test
    fun `the token still has the shape the bridge expects`() {
        val token = SecurityManager().getSessionToken()
        assertTrue(token.matches(Regex("[0-9a-f]{64}")), "expected 64 lowercase hex characters, got '$token'")
    }
}
