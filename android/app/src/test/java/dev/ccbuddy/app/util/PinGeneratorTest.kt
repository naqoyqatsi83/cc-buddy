package dev.ccbuddy.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinGeneratorTest {
    @Test
    fun `default length is 6 digits`() {
        assertEquals(6, PinGenerator.generate().length)
    }

    @Test
    fun `is always zero-padded to the requested length`() {
        // A CSPRNG-drawn value under 100000 would print as fewer than 6
        // digits without padding -- run enough draws that this is
        // exercised in practice, not just theoretically possible.
        repeat(2000) {
            val pin = PinGenerator.generate()
            assertEquals(6, pin.length)
        }
    }

    @Test
    fun `contains only digits`() {
        repeat(200) {
            val pin = PinGenerator.generate()
            assertTrue("PIN '$pin' contained a non-digit", pin.all { it.isDigit() })
        }
    }

    @Test
    fun `respects a custom length`() {
        val pin = PinGenerator.generate(length = 4)
        assertEquals(4, pin.length)
    }

    @Test
    fun `draws are not all identical`() {
        val pins = (1..50).map { PinGenerator.generate() }.toSet()
        assertTrue("expected some variation across 50 draws, got all identical", pins.size > 1)
    }
}
