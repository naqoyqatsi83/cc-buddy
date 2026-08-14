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
import kotlinx.coroutines.launch

private val QUICK_REPLIES = listOf("1", "2", "y", "n", "")

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
    // into a line count for the gesture-driven scroll below. Defaults to
    // a plausible guess before the first size is known.
    var rows by remember { mutableStateOf(30) }
    // The WebView's real measured height, forwarded to JS so font-size
    // math doesn't rely on window.innerHeight — that didn't reliably
    // match the actual Compose-allocated height and silently clipped the
    // bottom of the active grid with no way to scroll into it (only true
    // scrollback is scrollable, not active-grid overflow). onSizeChanged
    // reports raw physical pixels, but window.innerHeight inside a WebView
    // is in CSS/density-independent pixels — passing the raw value
    // through unconverted made the computed height ~density-times too
    // large (e.g. 719 physical px on a ~2.75x density screen is only
    // ~261 CSS px), which was the actual cause of the clipping: the font
    // math targeted an area far bigger than what the WebView could really
    // show.
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
        // Apply whatever column width is already known *before* replaying
        // any buffered output. Doing this the other way around — replay
        // first, resize whenever its own collector happens to run — let
        // buffered content render into the wrong-width grid and then
        // visibly jump/reflow the instant the resize landed a moment
        // later. That reflow was the "glitch right after pairing".
        terminalBridge.sizeFlow(peerId)?.value?.let { size ->
            wv.evaluateJavascript("resizeTerm(${size.cols}, ${size.rows})", null)
            rows = size.rows
        }
        flow.collect { chunk ->
            val b64 = Base64.encodeToString(chunk.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            wv.evaluateJavascript("writeChunkB64('$b64')", null)
        }
    }

    // Mirror the PC terminal's column width whenever it changes later
    // (e.g. the PC resizes its window) — the initial value was already
    // applied above before output replay started.
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

    fun send(text: String) {
        scope.launch { terminalBridge.sendInput(peerId, text) }
    }

    fun sendRaw(text: String) {
        scope.launch { terminalBridge.sendRaw(peerId, text) }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onBack) { Text("< Sessions") }
            Text(deviceName, modifier = Modifier.padding(top = 12.dp, end = 8.dp))
        }

        val transcript by (terminalBridge.transcriptFlow(peerId) ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) })
            .collectAsState(initial = emptyList())

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
            val historyHeight = splitHeight * 0.55f
            val liveHeight = splitHeight - historyHeight
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    "History (scroll freely, independent of PC)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.height(labelHeight).padding(horizontal = 8.dp)
                )
                TranscriptPane(
                    lines = transcript,
                    modifier = Modifier.fillMaxWidth().height(historyHeight)
                )

                Text(
                    "Live (mirrors PC exactly, including its own scroll/expand)",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.height(labelHeight).padding(horizontal = 8.dp)
                )
                TerminalWebView(
                    modifier = Modifier.fillMaxWidth().height(liveHeight)
                        .onSizeChanged { containerHeightCssPx = (it.height / density).toInt() },
                    rows = rows,
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
            // Wrapped, not clipped, unlike the live pane below: this is
            // plain captured text, not a column-aligned live grid, so
            // nothing is lost by wrapping and nothing is lost by NOT
            // supporting horizontal scroll here.
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
private fun TerminalWebView(modifier: Modifier = Modifier, rows: Int, onReady: (WebView) -> Unit) {
    val rowsState = rememberUpdatedState(rows)
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                // The terminal is no longer reflowed to fit the screen (see
                // index.html) — it renders at the PC terminal's actual
                // size, which is usually wider than the phone. Pinch-zoom
                // lets you zoom out to see more of it at once.
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                // Vertical scrollback is driven explicitly from here rather
                // than relying on the WebView's native touch-to-DOM-scroll
                // handling of xterm's internal viewport: that worked in the
                // emulator's WebView but did nothing at all on a real
                // device's, and cross-WebView-version touch/scroll
                // disambiguation isn't something worth fighting. A plain
                // single-finger vertical drag calls xterm's own
                // scrollLines() API directly. Horizontal panning is left to
                // the WebView's native handling (CSS overflow-x + pinch),
                // which does work — only the vertical case was broken.
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
                        if (lines != 0) evaluateJavascript("scrollLines($lines)", null)
                        return true
                    }
                })
                setOnTouchListener { _, event ->
                    gestureDetector.onTouchEvent(event)
                    // Never consume: taps, horizontal drags and pinch-zoom
                    // must still reach the WebView's own handling. Trying
                    // to have this listener consume vertical drags (return
                    // the detector's own decision) risked WebView's native
                    // touch handling never engaging at all for ambiguous
                    // gestures — returning false always keeps WebView's
                    // own handling intact and lets the GestureDetector run
                    // in parallel regardless.
                    false
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
