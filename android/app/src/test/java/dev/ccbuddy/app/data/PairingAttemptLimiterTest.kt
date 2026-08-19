package dev.ccbuddy.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drives a fake clock instead of sleeping in real time for the 60s window
 * / 30s lockout -- see the injectable `now` param on PairingAttemptLimiter.
 */
class PairingAttemptLimiterTest {
    private var fakeNow = 0L
    private val limiter = PairingAttemptLimiter { fakeNow }

    @Test
    fun `not locked before any failures`() {
        assertFalse(limiter.isLocked("1.2.3.4"))
    }

    @Test
    fun `not locked after fewer than MAX_ATTEMPTS failures`() {
        repeat(4) { limiter.recordFailure("1.2.3.4") }
        assertFalse(limiter.isLocked("1.2.3.4"))
    }

    @Test
    fun `locked after MAX_ATTEMPTS failures within the window`() {
        repeat(5) { limiter.recordFailure("1.2.3.4") }
        assertTrue(limiter.isLocked("1.2.3.4"))
    }

    @Test
    fun `lockout expires after LOCKOUT_MILLIS`() {
        repeat(5) { limiter.recordFailure("1.2.3.4") }
        assertTrue(limiter.isLocked("1.2.3.4"))

        fakeNow += 30_000L
        assertFalse(limiter.isLocked("1.2.3.4"))
    }

    @Test
    fun `failure count resets once the window elapses without hitting the threshold`() {
        repeat(4) { limiter.recordFailure("1.2.3.4") }
        fakeNow += 60_001L // past WINDOW_MILLIS since the first failure
        limiter.recordFailure("1.2.3.4") // would be the 5th if the window hadn't reset

        assertFalse(limiter.isLocked("1.2.3.4"))
    }

    @Test
    fun `recordSuccess clears history so a later mistyped PIN isn't punished immediately`() {
        repeat(4) { limiter.recordFailure("1.2.3.4") }
        limiter.recordSuccess("1.2.3.4")
        limiter.recordFailure("1.2.3.4") // would be the 5th of the original streak

        assertFalse(limiter.isLocked("1.2.3.4"))
    }

    @Test
    fun `lockout is keyed per address, not global`() {
        repeat(5) { limiter.recordFailure("1.2.3.4") }

        assertTrue(limiter.isLocked("1.2.3.4"))
        assertFalse(limiter.isLocked("5.6.7.8"))
    }
}
