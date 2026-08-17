package dev.ccbuddy.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * SharedPreferences-backed; two small scalar settings that only change from
 * deliberate taps on the Settings screen don't need DataStore's async setup.
 */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("buddy_settings", Context.MODE_PRIVATE)

    private val _fontSizeOverride = MutableStateFlow(
        prefs.getInt(KEY_FONT_SIZE, 0).takeIf { it > 0 }
    )
    val fontSizeOverride: StateFlow<Int?> = _fontSizeOverride

    private val _compactMode = MutableStateFlow(prefs.getBoolean(KEY_COMPACT, false))
    val compactMode: StateFlow<Boolean> = _compactMode

    // Off by default -- latency is only useful when you're actually
    // wondering why a session feels laggy; showing it unconditionally on
    // every paired-session row would just be noise most of the time.
    private val _showConnectionDetails = MutableStateFlow(prefs.getBoolean(KEY_CONNECTION_DETAILS, false))
    val showConnectionDetails: StateFlow<Boolean> = _showConnectionDetails

    fun setFontSizeOverride(px: Int?) {
        prefs.edit().putInt(KEY_FONT_SIZE, px ?: 0).apply()
        _fontSizeOverride.value = px
    }

    fun setCompactMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPACT, enabled).apply()
        _compactMode.value = enabled
    }

    fun setShowConnectionDetails(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_CONNECTION_DETAILS, enabled).apply()
        _showConnectionDetails.value = enabled
    }

    companion object {
        private const val KEY_FONT_SIZE = "font_size_override"
        private const val KEY_COMPACT = "compact_mode"
        private const val KEY_CONNECTION_DETAILS = "show_connection_details"
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 22
    }
}
