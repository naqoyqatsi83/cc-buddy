package dev.ccbuddy.app.server

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log

/**
 * Thin wrapper around Android's built-in text-to-speech engine, used to
 * read Claude Code's notification prompts aloud (opt-in, see
 * SettingsStore.readNotificationsAloud) -- no extra dependency, no special
 * permission needed for speech output (unlike RECORD_AUDIO for input).
 */
class TtsSpeaker(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (!ready) Log.w("TtsSpeaker", "TextToSpeech init failed, status=$status")
        }
    }

    fun speak(text: String) {
        if (!ready) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // Silences whatever's currently being read, without tearing down the
    // engine like shutdown() does -- speak() still works normally after
    // this (#22: needs to be safe to call from a notification action
    // that can fire any number of times over the service's lifetime).
    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
