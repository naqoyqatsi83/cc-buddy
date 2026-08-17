package dev.ccbuddy.app.data

/**
 * Receives the daemon's forwarded Claude Code hook events (Notification /
 * Stop / PreToolUse — see spec Component 2) over the paired WS connection.
 * Implemented by BuddyForegroundService, which is the piece with a
 * Context to actually post Android notifications.
 */
interface HookNotifier {
    // question is the daemon's best-effort extracted prompt text (see
    // buddy-daemon/src/shadowTerminal.ts) -- richer than message, but not
    // always present (null when nothing substantive was on screen to
    // extract it from), in which case message is the fallback.
    fun onClaudeNotification(message: String, question: String?)
    fun onClaudeStop()
    fun onPreToolUse(tool: String, input: String?)
}
