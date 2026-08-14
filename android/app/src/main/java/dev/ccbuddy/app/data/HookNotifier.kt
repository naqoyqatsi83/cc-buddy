package dev.ccbuddy.app.data

/**
 * Receives the daemon's forwarded Claude Code hook events (Notification /
 * Stop / PreToolUse — see spec Component 2) over the paired WS connection.
 * Implemented by BuddyForegroundService, which is the piece with a
 * Context to actually post Android notifications.
 */
interface HookNotifier {
    fun onClaudeNotification(message: String)
    fun onClaudeStop()
    fun onPreToolUse(tool: String, input: String?)
}
