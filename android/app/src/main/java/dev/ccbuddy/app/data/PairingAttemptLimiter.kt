package dev.ccbuddy.app.data

/**
 * Rate-limits pairing attempts per source address. The PIN itself (6
 * digits, single-use, short TTL) is the primary defense, but with no
 * limiting a script on the same LAN could still brute-force the ~2-minute
 * TTL window at LAN speed. After [MAX_ATTEMPTS] wrong PINs from the same
 * address within [WINDOW_MILLIS], further attempts from it are locked out
 * for [LOCKOUT_MILLIS] regardless of whether the PIN they send is correct
 * -- keyed by remote address, not global, so one noisy/malicious source
 * can't lock out a legitimate pairing attempt from elsewhere.
 */
class PairingAttemptLimiter {
    private data class Record(var count: Int, var windowStart: Long, var lockedUntil: Long)

    private val records = mutableMapOf<String, Record>()

    @Synchronized
    fun isLocked(address: String): Boolean {
        val record = records[address] ?: return false
        return System.currentTimeMillis() < record.lockedUntil
    }

    @Synchronized
    fun recordFailure(address: String) {
        val now = System.currentTimeMillis()
        val record = records.getOrPut(address) { Record(0, now, 0) }
        if (now - record.windowStart > WINDOW_MILLIS) {
            record.count = 0
            record.windowStart = now
        }
        record.count++
        if (record.count >= MAX_ATTEMPTS) {
            record.lockedUntil = now + LOCKOUT_MILLIS
        }
    }

    /** A successful pairing clears this address's history -- the lockout
     * is meant to slow down guessing, not to punish a device that just
     * happened to mistype a PIN once before getting it right. */
    @Synchronized
    fun recordSuccess(address: String) {
        records.remove(address)
    }

    companion object {
        private const val MAX_ATTEMPTS = 5
        private const val WINDOW_MILLIS = 60_000L
        private const val LOCKOUT_MILLIS = 30_000L
    }
}
