package dev.ccbuddy.app.util

import java.security.SecureRandom

object PinGenerator {
    private val random = SecureRandom()

    /** 6-digit PIN, zero-padded, drawn from a CSPRNG (this is an auth secret, not cosmetic). */
    fun generate(length: Int = 6): String {
        val max = Math.pow(10.0, length.toDouble()).toInt()
        val value = random.nextInt(max)
        return value.toString().padStart(length, '0')
    }
}
