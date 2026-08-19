package dev.ccbuddy.app.util

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(val latestVersion: String, val releaseUrl: String)

/**
 * This app has no in-place auto-update mechanism (sideloaded APK, no Play
 * Store) -- without this, the only way to learn a newer release exists is
 * noticing it yourself on the GitHub repo. A lightweight, best-effort
 * check against the public releases API instead. Never worth blocking
 * startup or surfacing an error over -- no network, GitHub down/rate-
 * limited, unexpected response shape all just mean "silently skip it",
 * same as if the check were never run.
 */
object UpdateChecker {
    private const val API_URL = "https://api.github.com/repos/naqoyqatsi83/cc-buddy/releases/latest"
    private const val TIMEOUT_MS = 5000
    private const val FALLBACK_RELEASE_URL = "https://github.com/naqoyqatsi83/cc-buddy/releases/latest"

    suspend fun checkForUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val connection = URL(API_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(body)
                val latestVersion = json.getString("tag_name").removePrefix("v")
                val releaseUrl = json.optString("html_url", FALLBACK_RELEASE_URL)
                if (isNewer(latestVersion, currentVersion)) UpdateInfo(latestVersion, releaseUrl) else null
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Numeric dot-separated comparison (so "0.10.0" correctly beats
     * "0.9.0", unlike a plain string compare) -- a missing or non-numeric
     * component is treated as 0 rather than throwing, tolerating a tag
     * name that doesn't match this project's own vX.Y.Z convention (e.g.
     * a hand-created GitHub release) instead of crashing the check.
     */
    internal fun isNewer(candidate: String, current: String): Boolean {
        val c = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val cur = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(c.size, cur.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = cur.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
