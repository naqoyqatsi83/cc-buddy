package dev.ccbuddy.app.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ccbuddy.app.data.TerminalBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

private val QUICK_REPLIES = listOf("1", "2", "y", "n", "")

/**
 * A scrollable history (everything ever shown) with a small fixed footer
 * below it — the current prompt + status, always visible, updated in
 * place, that scrolling the history never disturbs. A single unified
 * scrollable stream (tried first) was wrong: scrolling up carried the
 * prompt/status away with it instead of leaving them anchored, which
 * doesn't match how a real terminal's status line or a chat app's input
 * bar behaves. See buddy-daemon/src/shadowTerminal.ts for how the two are
 * genuinely distinct daemon-side: lines that have permanently scrolled
 * off the PC's active screen (history, appended once) versus the current
 * active screen itself (tail, replaced wholesale as it's redrawn).
 *
 * The history pane is xterm.js (buddy-daemon captures plain, diffed,
 * already-rendered text — not raw PTY bytes — so a normal, non-alt-screen
 * scrollback works safely here without needing to mirror the PC's exact
 * row count). The footer is plain Compose text — it's only ever a few
 * lines, no scrolling needed.
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

    val transcript by (terminalBridge.transcriptFlow(peerId) ?: remember { MutableStateFlow(emptyList()) })
        .collectAsState(initial = emptyList())
    val tail by (terminalBridge.tailFlow(peerId) ?: remember { MutableStateFlow(emptyList()) })
        .collectAsState(initial = emptyList())

    LaunchedEffect(peerId, webView) {
        val wv = webView ?: return@LaunchedEffect
        // The WebView instance is reused across peer switches (Compose
        // doesn't recreate it just because peerId changed), so clear
        // whatever the previous peer left on screen — otherwise the two
        // peers' output visually concatenates in the same xterm.js buffer.
        wv.evaluateJavascript("clearTerminal()", null)
        terminalBridge.sizeFlow(peerId)?.value?.let { size ->
            wv.evaluateJavascript("resizeCols(${size.cols})", null)
        }
    }

    // Column width still mirrors the PC (so captured lines don't wrap at a
    // different width than they were originally rendered for) — rows no
    // longer need to, unlike the old raw-byte mirror.
    LaunchedEffect(peerId, webView) {
        val wv = webView ?: return@LaunchedEffect
        val sizes = terminalBridge.sizeFlow(peerId) ?: return@LaunchedEffect
        sizes.collect { size ->
            if (size != null) wv.evaluateJavascript("resizeCols(${size.cols})", null)
        }
    }

    // Feed only newly-appended transcript lines to the WebView, tracking
    // how many have been sent so far — transcript is the full growing
    // list (see TerminalBridge.transcriptFlow), re-sending it whole on
    // every emission would re-append everything already shown.
    var sentCount by remember(peerId) { mutableStateOf(0) }
    LaunchedEffect(peerId, webView) { sentCount = 0 }
    LaunchedEffect(webView, transcript) {
        val wv = webView ?: return@LaunchedEffect
        if (transcript.size > sentCount) {
            val newLines = transcript.subList(sentCount, transcript.size)
            val arr = JSONArray()
            newLines.forEach { line -> arr.put(Base64.encodeToString(line.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)) }
            wv.evaluateJavascript("appendLinesB64('${arr.toString()}')", null)
            sentCount = transcript.size
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

        TerminalWebView(
            modifier = Modifier.fillMaxWidth().weight(1f),
            onReady = { webView = it }
        )

        TailFooter(lines = tail, modifier = Modifier.fillMaxWidth())

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
 * The current prompt + status, fixed at the bottom of the screen — see
 * the file-level doc comment. Not a LazyColumn: this is only ever a
 * handful of lines (no need for lazy virtualization), and a plain Column
 * with a single horizontalScroll modifier is simplest/safest here —
 * mixing horizontal and vertical scroll modifiers caused real problems
 * for the (much longer) history list, but there's no vertical scroll
 * here to conflict with at all.
 */
@Composable
private fun TailFooter(lines: List<String>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) return
    // In real usage the tail (prompt + status) is only ever a handful of
    // lines — real conversations exceed the PC terminal's row count
    // quickly, at which point everything except the true tail flows into
    // history instead. But nothing guarantees that (a short session, or a
    // TUI with a large redrawable area), and an unbounded footer here
    // once ate the whole screen — starving the history WebView and
    // pushing the reply UI off-screen entirely, which is exactly what it
    // looks like when a real user says "I can't see the prompt or status
    // bar at all". Capped height with its own scroll as a hard backstop.
    Column(
        modifier = modifier.background(Color(0xFF0D0D0D))
            .heightIn(max = 160.dp)
            .verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                color = Color(0xFFE0E0E0),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                softWrap = false
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun TerminalWebView(modifier: Modifier = Modifier, onReady: (WebView) -> Unit) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                settings.javaScriptEnabled = true
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false

                // Vertical scrollback is driven explicitly from here rather
                // than relying on the WebView's native touch-to-DOM-scroll
                // handling of xterm's internal viewport: that path proved
                // unreliable across devices earlier in this project (worked
                // in an emulator, did nothing on a real device). A plain
                // single-finger vertical drag calls xterm's own
                // scrollLines() API directly. Horizontal panning is left to
                // the WebView's native handling (CSS overflow-x + pinch).
                val density = context.resources.displayMetrics.density
                val rowHeightPx = 14f * 1.15f * density // matches index.html's fixed fontSize
                val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    var accumulatedY = 0f

                    override fun onScroll(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        distanceX: Float,
                        distanceY: Float
                    ): Boolean {
                        if (kotlin.math.abs(distanceY) <= kotlin.math.abs(distanceX)) return false
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
                    // must still reach the WebView's own handling.
                    false
                }

                // Only signal ready once the page (and xterm.js, and our
                // appendLinesB64/clearTerminal functions) has actually
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
