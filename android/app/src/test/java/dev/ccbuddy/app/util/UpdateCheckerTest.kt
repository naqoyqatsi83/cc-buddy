package dev.ccbuddy.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun `newer patch version is detected`() {
        assertTrue(UpdateChecker.isNewer("0.3.2", "0.3.1"))
    }

    @Test
    fun `newer minor version is detected`() {
        assertTrue(UpdateChecker.isNewer("0.4.0", "0.3.9"))
    }

    @Test
    fun `newer major version is detected`() {
        assertTrue(UpdateChecker.isNewer("1.0.0", "0.9.9"))
    }

    @Test
    fun `numeric comparison, not lexicographic -- 0_10_0 beats 0_9_0`() {
        assertTrue(UpdateChecker.isNewer("0.10.0", "0.9.0"))
    }

    @Test
    fun `identical versions are not newer`() {
        assertFalse(UpdateChecker.isNewer("0.3.1", "0.3.1"))
    }

    @Test
    fun `older version is not newer`() {
        assertFalse(UpdateChecker.isNewer("0.3.0", "0.3.1"))
    }

    @Test
    fun `shorter version string is padded with zeros, not treated as smaller by length`() {
        assertFalse(UpdateChecker.isNewer("0.3", "0.3.0"))
        assertTrue(UpdateChecker.isNewer("0.3.1", "0.3"))
    }

    @Test
    fun `non-numeric components fall back to zero instead of throwing`() {
        assertFalse(UpdateChecker.isNewer("unknown", "0.3.1"))
    }
}
