package dev.ccbuddy.app.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ccbuddy.app.data.TerminalBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

private val QUICK_REPLIES = listOf("1", "2", "y", "n", "")

/**
 * Two panes again, but for a different reason than the original split:
 * History (top) is the daemon's independently-captured transcript — see
 * buddy-daemon/src/shadowTerminal.ts — rendered natively (Compose,
 * plain text, no WebView). Live (bottom) is the exact 1:1 raw-byte PC
 * mirror, restored after several independent-capture designs proved too
 * fragile against real usage (blank-line gaps after a daemon restart,
 * idle-refresh duplication, row-height bugs). Colors and exact layout
 * only ever worked reliably through this raw path.
 *
 * Scrolling Live now sends real key sequences into the PC's PTY instead
 * of manipulating a local buffer — a swipe becomes an arrow-key press
 * Claude Code's own TUI receives and scrolls with, same as pressing that
 * key at the PC. This trades independent phone-side scroll (impossible
 * to get right against an app that manages its own redraw-based scroll
 * state — see commit history) for something that actually works: the
 * PC's own scrolling, remote-controlled. Tap-to-expand collapsed
 * sections is NOT implemented — untested whether Claude Code's TUI
 * responds to mouse clicks at all vs. being keyboard-only for that.
 */
@Composable
fun TerminalScreen(
    peerId: String,
    deviceName: String,
    terminalBridge: TerminalBridge,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var replyText by remember { mutableStateOf("") }
    // Real PTY row count, used to convert a touch-drag's pixel distance
    // into a line count for the swipe-to-scroll below.
    var rows by remember { mutableStateOf(30) }
    val density = LocalDensity.current.density
    var containerHeightCssPx by remember { mutableStateOf(0) }

    LaunchedEffect(webView, containerHeightCssPx) {
        if (containerHeightCssPx > 0) {
            webView?.evaluateJavascript("setContainerHeightPx($containerHeightCssPx)", null)
        }
    }

    LaunchedEffect(peerId, webView) {
        val wv = webView ?: return@LaunchedEffect
        val flow = terminalBridge.outputFlow(peerId) ?: return@LaunchedEffect
        // The WebView instance is reused across peer switches (Compose
        // doesn't recreate it just because peerId changed), so clear
        // whatever the previous peer left on screen before replaying this
        // one's backlog — otherwise the two peers' output visually
        // concatenates in the same xterm.js buffer.
        wv.evaluateJavascript("clearTerminal()", null)
        terminalBridge.sizeFlow(peerId)?.value?.let { size ->
            wv.evaluateJavascript("resizeTerm(${size.cols}, ${size.rows})", null)
            rows = size.rows
        }
        flow.collect { chunk ->
            val b64 = Base64.encodeToString(chunk.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            wv.evaluateJavascript("writeChunkB64('$b64')", null)
        }
    }

    LaunchedEffect(peerId, webView) {
        val wv = webView ?: return@LaunchedEffect
        val sizes = terminalBridge.sizeFlow(peerId) ?: return@LaunchedEffect
        sizes.collect { size ->
            if (size != null) {
                wv.evaluateJavascript("resizeTerm(${size.cols}, ${size.rows})", null)
                rows = size.rows
            }
        }
    }

    val transcript by (terminalBridge.transcriptFlow(peerId) ?: remember { MutableStateFlow(emptyList()) })
        .collectAsState(initial = emptyList())

    fun send(text: String) {
        scope.launch { terminalBridge.sendInput(peerId, text) }
    }

    fun sendRaw(text: String) {
        scope.launch { terminalBridge.sendRaw(peerId, text) }
    }

    // A swipe becomes this many arrow-key presses sent to the PC. Arrow
    // keys, not Page Up/Down, on the theory that most TUIs reserve
    // Page Up/Down for coarser jumps and treat arrows as the fine-grained
    // scroll/navigate key — genuinely untested against Claude Code's
    // specific TUI, may need to switch to [5~ / [6~ (Page
    // Up/Down) instead if arrows turn out to do something else (e.g.
    // moving an input cursor rather than scrolling).
    fun sendScrollKey(down: Boolean) {
        sendRaw(if (down) "[B" else "[A")
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("< Sessions") }
            Text(deviceName, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
        }

        // WebView doesn't reliably participate in Compose's weight()-based
        // flexible measurement — with two weighted siblings sharing this
        // Column, it ended up taking more space than its share (pushing
        // the reply UI off-screen entirely) regardless of the requested
        // weight. BoxWithConstraints measures the real available pixel
        // height once, and both panes get an explicit height computed
        // from it instead of a weight — deterministic regardless of how
        // WebView behaves internally.
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
            val labelHeight = 20.dp
            val splitHeight = maxHeight - labelHeight * 2
            val historyHeight = splitHeight * 0.45f
            val liveHeight = splitHeight - historyHeight
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    "History (independent, may show gaps right after a daemon restart)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.height(labelHeight).padding(horizontal = 8.dp)
                )
                TranscriptPane(
                    lines = transcript,
                    modifier = Modifier.fillMaxWidth().height(historyHeight)
                )

                Text(
                    "Live (mirrors PC exactly — swipe scrolls the PC's own view)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.height(labelHeight).padding(horizontal = 8.dp)
                )
                TerminalWebView(
                    modifier = Modifier.fillMaxWidth().height(liveHeight)
                        .onSizeChanged { containerHeightCssPx = (it.height / density).toInt() },
                    rows = rows,
                    onSwipeLines = { lines ->
                        // One key press per line-unit the swipe crossed —
                        // not a single Page Up/Down per gesture, so short
                        // vs. long swipes scroll proportionally, matching
                        // the granularity the old local-buffer scroll had.
                        repeat(kotlin.math.abs(lines)) { sendScrollKey(down = lines > 0) }
                    },
                    onReady = { webView = it }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QUICK_REPLIES.forEach { reply ->
                TextButton(onClick = { send(reply) }) {
                    Text(reply.ifEmpty { "⏎" })
                }
            }
            // Tab must NOT submit like the replies above (no trailing
            // Enter) — it's how you accept an autocomplete suggestion,
            // which is a raw keystroke, not a complete answer.
            TextButton(onClick = { sendRaw("\t") }) {
                Text("⇥")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = replyText,
                onValueChange = { replyText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Reply…") }
            )
            Button(onClick = {
                send(replyText)
                replyText = ""
            }) {
                Text("Send")
            }
        }
    }
}

/**
 * Plain, growing, independently-scrollable history — see
 * buddy-daemon/src/shadowTerminal.ts for how these lines are captured. A
 * LazyColumn appended to like this never fights the user's scroll
 * position (unlike the live pane, it's just static text growing over
 * time), so ordinary touch-scroll works with no special handling needed —
 * and scrolling it never affects, or is affected by, the PC.
 */
@Composable
private fun TranscriptPane(lines: List<String>, modifier: Modifier = Modifier) {
    LazyColumn(modifier = modifier.background(Color(0xFF0D0D0D)).padding(horizontal = 4.dp)) {
        items(lines) { line ->
            Text(
                text = line,
                color = Color(0xFFE0E0E0),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun TerminalWebView(
    modifier: Modifier = Modifier,
    rows: Int,
    onSwipeLines: (Int) -> Unit,
    onReady: (WebView) -> Unit
) {
    val rowsState = rememberUpdatedState(rows)
    val onSwipeLinesState = rememberUpdatedState(onSwipeLines)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                // The terminal is not reflowed to fit the screen — it
                // renders at the PC terminal's actual size, which is
                // usually wider than the phone. Pinch-zoom lets you zoom
                // out to see more of it at once.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                // A vertical drag here sends real key presses to the PC
                // (see onSwipeLines above) instead of scrolling a local
                // xterm buffer — Claude Code manages its own scroll state
                // via redraws, so there's no local buffer that scrolling
                // could meaningfully affect independent of the PC anyway.
                val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    var accumulatedY = 0f

                    override fun onScroll(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        distanceX: Float,
                        distanceY: Float
                    ): Boolean {
                        if (kotlin.math.abs(distanceY) <= kotlin.math.abs(distanceX)) return false
                        val rowHeightPx = height / rowsState.value.coerceAtLeast(1)
                        if (rowHeightPx <= 0) return false
                        accumulatedY += distanceY
                        var lines = 0
                        while (accumulatedY >= rowHeightPx) {
                            accumulatedY -= rowHeightPx
                            lines++
                        }
                        while (accumulatedY <= -rowHeightPx) {
                            accumulatedY += rowHeightPx
                            lines--
                        }
                        if (lines != 0) onSwipeLinesState.value(lines)
                        return true
                    }
                })
                setOnTouchListener { _, event ->
                    // Propagate the detector's own consumption decision:
                    // true only when onScroll just handled a vertical-
                    // dominant drag, so it doesn't also reach the
                    // WebView's native touch handling. Taps, horizontal
                    // drags and pinch-zoom are untouched by onScroll and
                    // fall through as usual.
                    gestureDetector.onTouchEvent(event)
                }

                // Only signal ready once the page (and xterm.js, and our
                // writeChunkB64/clearTerminal functions) has actually
                // finished loading — calling evaluateJavascript() any
                // earlier fails silently since those functions don't exist
                // yet, which was dropping the whole replay backlog on every
                // WebView recreation (returning from the session list).
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        onReady(view)
                    }
                }
                loadUrl("file:///android_asset/xterm/index.html")
            }
        }
    )
}
