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

    // Off by default -- speaking every notification aloud is the kind of
    // thing you want deliberately, not sprung on you the first time
    // Claude needs attention while the phone's in your pocket in public.
    private val _readNotificationsAloud = MutableStateFlow(prefs.getBoolean(KEY_READ_ALOUD, false))
    val readNotificationsAloud: StateFlow<Boolean> = _readNotificationsAloud

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

    fun setReadNotificationsAloud(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_READ_ALOUD, enabled).apply()
        _readNotificationsAloud.value = enabled
    }

    // On by default -- the quick-reply row (menu numbers, y/n/Enter, Tab)
    // and swipe-to-scroll are the fast path for the common case, worth
    // keeping alongside direct terminal typing rather than making people
    // opt back into them.
    private val _showQuickReplyButtons = MutableStateFlow(prefs.getBoolean(KEY_QUICK_REPLY_BUTTONS, true))
    val showQuickReplyButtons: StateFlow<Boolean> = _showQuickReplyButtons

    // Off by default -- now that the terminal itself takes live keystrokes
    // (see #12 follow-up: direct in-place editing of a Tab-completed
    // prompt), the separate append-only field is redundant for most people;
    // it stays available for anyone who prefers composing text before it
    // hits the PTY.
    private val _showReplyTextField = MutableStateFlow(prefs.getBoolean(KEY_REPLY_TEXT_FIELD, false))
    val showReplyTextField: StateFlow<Boolean> = _showReplyTextField

    fun setShowQuickReplyButtons(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_QUICK_REPLY_BUTTONS, enabled).apply()
        _showQuickReplyButtons.value = enabled
    }

    fun setShowReplyTextField(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_REPLY_TEXT_FIELD, enabled).apply()
        _showReplyTextField.value = enabled
    }

    // The version string of the last update banner the user dismissed
    // (see UpdateChecker) -- null means nothing's been dismissed. Keyed by
    // version rather than a plain boolean so dismissing "update to 0.4.0"
    // doesn't also silence a later "update to 0.5.0" banner.
    private val _dismissedUpdateVersion = MutableStateFlow(prefs.getString(KEY_DISMISSED_UPDATE, null))
    val dismissedUpdateVersion: StateFlow<String?> = _dismissedUpdateVersion

    fun setDismissedUpdateVersion(version: String?) {
        prefs.edit().putString(KEY_DISMISSED_UPDATE, version).apply()
        _dismissedUpdateVersion.value = version
    }

    companion object {
        private const val KEY_FONT_SIZE = "font_size_override"
        private const val KEY_COMPACT = "compact_mode"
        private const val KEY_CONNECTION_DETAILS = "show_connection_details"
        private const val KEY_READ_ALOUD = "read_notifications_aloud"
        private const val KEY_QUICK_REPLY_BUTTONS = "show_quick_reply_buttons"
        private const val KEY_REPLY_TEXT_FIELD = "show_reply_text_field"
        private const val KEY_DISMISSED_UPDATE = "dismissed_update_version"
        const val MIN_FONT_SIZE = 8
        const val MAX_FONT_SIZE = 22
    }
}
