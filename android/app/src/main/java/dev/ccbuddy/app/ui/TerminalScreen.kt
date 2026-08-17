package dev.ccbuddy.app.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ccbuddy.app.data.TerminalBridge
import kotlinx.coroutines.launch

// Fallback when the daemon hasn't detected a numbered menu in the current
// prompt (see buddy-daemon/src/shadowTerminal.ts) -- matches Claude Code's
// most common case (a plain yes/no confirm) so the buttons are still
// useful by default.
private val DEFAULT_NUMBER_REPLIES = listOf("1", "2")

/**
 * One full-screen view: the exact 1:1 raw-byte PC mirror. The
 * independently-captured History pane (tried in several forms —
 * linefeed watching, debounced snapshots, a tail/history split) was
 * dropped entirely at the user's request rather than kept alongside —
 * a two-pane screen was rejected regardless of what filled the second
 * pane. Colors and exact layout only ever worked reliably through this
 * raw path anyway.
 *
 * Scrolling now sends real key sequences into the PC's PTY instead of
 * manipulating a local buffer — a swipe becomes a Page Up/Down press
 * Claude Code's own TUI receives and scrolls with, same as pressing
 * that key at the PC. (Arrow keys were tried first and were wrong —
 * Claude Code's prompt treats them as command-history navigation, not
 * transcript scroll.) This trades independent phone-side scroll
 * (impossible to get right against a TUI that manages its own
 * redraw-based scroll state — see commit history) for something that
 * reuses Claude Code's own, already-working scroll handling. A tap
 * sends Ctrl+O for the same reason, to expand/collapse a section — that
 * turned out to be a real Claude Code keyboard shortcut (confirmed via
 * its own GitHub issues), not a mouse click; an earlier version tried
 * forwarding an SGR mouse click at the tapped cell instead, needing
 * pixel-to-cell coordinate math for no benefit once it was clear
 * Claude Code doesn't actually depend on click position for this.
 */
@Composable
fun TerminalScreen(
    peerId: String,
    deviceName: String,
    terminalBridge: TerminalBridge,
    onBack: () -> Unit,
    fontSizeOverride: Int? = null,
    compactMode: Boolean = false,
    readNotificationsAloud: Boolean = false,
    onToggleReadNotificationsAloud: () -> Unit = {},
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
    var menuOptions by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(peerId) {
        val flow = terminalBridge.menuOptionsFlow(peerId) ?: return@LaunchedEffect
        flow.collect { menuOptions = it }
    }
    val numberReplies = remember(menuOptions) { menuOptions.ifEmpty { DEFAULT_NUMBER_REPLIES } }
    val quickReplies = remember(numberReplies) { numberReplies + listOf("y", "n", "") }

    LaunchedEffect(webView, containerHeightCssPx) {
        if (containerHeightCssPx > 0) {
            webView?.evaluateJavascript("setContainerHeightPx($containerHeightCssPx)", null)
        }
    }

    // null clears the override on the JS side, falling back to the
    // auto-fit calculation there.
    LaunchedEffect(webView, fontSizeOverride) {
        webView?.evaluateJavascript("setFontSizeOverride(${fontSizeOverride ?: "null"})", null)
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

    fun send(text: String) {
        scope.launch { terminalBridge.sendInput(peerId, text) }
    }

    fun sendRaw(text: String) {
        scope.launch { terminalBridge.sendRaw(peerId, text) }
    }

    // A swipe becomes a Page Up/Down press sent to the PC. Arrow keys
    // were tried first and were wrong -- Claude Code's prompt treats
    // them as command-history navigation (like a shell), not transcript
    // scroll, so swiping was literally cycling through past prompts
    // instead of scrolling.
    val esc = ""
    fun sendScrollKey(down: Boolean) {
        sendRaw(if (down) "$esc[6~" else "$esc[5~")
    }

    // Finer-grained scroll than a full Page Up/Down -- a single mouse
    // wheel tick, SGR mouse-reporting format (button 64 = wheel up, 65 =
    // wheel down). Position doesn't track a real cursor (there isn't
    // one, this is a synthetic wheel event from a button tap, not a
    // hover), so it's sent at a fixed nominal cell -- most apps scroll
    // whatever has general focus regardless of the reported position for
    // a plain wheel event. Only does anything if Claude Code's TUI has
    // mouse reporting enabled at all; untested.
    fun sendScrollWheel(down: Boolean) {
        val button = if (down) 65 else 64
        sendRaw("$esc[<$button;1;1M")
    }

    // Expand/collapse in Claude Code is a keyboard shortcut (Ctrl+O),
    // not a mouse click -- confirmed via its own GitHub issues; the
    // click-a-toggle-icon alternative exists in some builds but is
    // reported unreliable even on the PC itself. A tap anywhere on the
    // terminal sends Ctrl+O rather than a coordinate-dependent mouse
    // click, which needed sending mouse-reporting bytes Claude Code may
    // not even be listening for. Ctrl+O is ASCII 0x0F.
    fun sendExpandToggle() {
        sendRaw("")
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Same defaultMinSize override as the bottom row (see
        // bottomButtonModifier below) -- this bar was previously fixed
        // size regardless of compactMode, plus a hand-tuned top-padding
        // hack on deviceName to visually center it against the (also
        // fixed-size) buttons. verticalAlignment = CenterVertically
        // replaces that hack and keeps working regardless of button size.
        val topRowPadding = if (compactMode) 2.dp else 8.dp
        val topButtonModifier = if (compactMode) Modifier.height(32.dp) else Modifier
        val topButtonPadding = if (compactMode) {
            PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        } else {
            ButtonDefaults.TextButtonContentPadding
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = topRowPadding),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack, modifier = topButtonModifier, contentPadding = topButtonPadding) {
                Text("< Sessions")
            }
            Text(deviceName)
            // Quick access to the same setting in SettingsScreen -- this is
            // the screen you're actually looking at when you'd want to
            // flip it, not worth a trip to Settings and back for.
            TextButton(onClick = onToggleReadNotificationsAloud, modifier = topButtonModifier, contentPadding = topButtonPadding) {
                Text(if (readNotificationsAloud) "🔊" else "🔇")
            }
        }

        Row(modifier = Modifier.fillMaxWidth().weight(1f)) {
            TerminalWebView(
                modifier = Modifier.weight(1f).fillMaxHeight()
                    .onSizeChanged { containerHeightCssPx = (it.height / density).toInt() },
                onSwipePages = { pages ->
                    repeat(kotlin.math.abs(pages)) { sendScrollKey(down = pages > 0) }
                },
                onTap = { sendExpandToggle() },
                onReady = { webView = it }
            )
            // A narrow side column, not the bottom button row: keeping
            // these off the bottom row leaves room there for Tab/Enter/
            // quick-replies to all stay visible without needing a
            // horizontal scroll to reach any of them. Double arrows
            // (Page Up/Down) for large jumps, single arrows (one mouse
            // wheel tick each) for fine adjustment -- same idea as
            // having both a scrollbar's page-jump area and its wheel.
            Column(
                modifier = Modifier.fillMaxHeight().width(32.dp),
                verticalArrangement = Arrangement.Center
            ) {
                val sideButtonPadding = PaddingValues(
                    horizontal = 0.dp,
                    vertical = if (compactMode) 2.dp else 8.dp
                )
                // Same defaultMinSize override as the bottom row -- see
                // bottomButtonModifier below.
                val sideButtonModifier = if (compactMode) {
                    Modifier.fillMaxWidth().height(32.dp)
                } else {
                    Modifier.fillMaxWidth()
                }
                TextButton(
                    onClick = { sendScrollKey(down = false) },
                    contentPadding = sideButtonPadding,
                    modifier = sideButtonModifier
                ) { Text("▲") }
                TextButton(
                    onClick = { sendScrollWheel(down = false) },
                    contentPadding = sideButtonPadding,
                    modifier = sideButtonModifier
                ) { Text("△") }
                TextButton(
                    onClick = { sendScrollWheel(down = true) },
                    contentPadding = sideButtonPadding,
                    modifier = sideButtonModifier
                ) { Text("▽") }
                TextButton(
                    onClick = { sendScrollKey(down = true) },
                    contentPadding = sideButtonPadding,
                    modifier = sideButtonModifier
                ) { Text("▼") }
            }
        }

        // "Compact mode" shrinks this bottom cluster so more of the
        // terminal is visible. Tightening contentPadding alone barely
        // changed anything -- Material3's Button/TextButton enforce a
        // ~40dp minimum touch-target height internally
        // (ButtonDefaults.MinHeight) regardless of padding, which
        // dominates the rendered size. An explicit height modifier here
        // is what actually overrides that minimum (the caller's modifier
        // wraps the button's own internal defaultMinSize, so a tighter
        // outer constraint wins); default (disabled) keeps Material's
        // normal sizing and touch target untouched.
        val bottomButtonModifier = if (compactMode) Modifier.height(32.dp) else Modifier
        val bottomButtonPadding = if (compactMode) {
            PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        } else {
            ButtonDefaults.TextButtonContentPadding
        }
        // Taller than the 32.dp quick-reply/side buttons -- comfortable
        // single-line text entry needs a bit more room than a bare label
        // button does -- but still well under the default ~56dp
        // OutlinedTextField height. Applied to both the Send button and
        // the text field next to it so they visually match instead of
        // the field towering over an already-shrunk button. 40.dp clipped
        // the field's own placeholder/input text -- unlike a plain
        // Button, OutlinedTextField reserves internal space for its
        // label/placeholder area even with none set, so it needs more
        // headroom than a button's defaultMinSize before content fits
        // without clipping; 48.dp (also Android's own standard minimum
        // touch target size) is the safe floor.
        val replyRowHeight = 48.dp
        val sendButtonModifier = if (compactMode) Modifier.height(replyRowHeight) else Modifier
        val sendButtonPadding = if (compactMode) {
            PaddingValues(horizontal = 12.dp, vertical = 2.dp)
        } else {
            ButtonDefaults.ContentPadding
        }
        val replyFieldModifier = if (compactMode) {
            Modifier.weight(1f).height(replyRowHeight)
        } else {
            Modifier.weight(1f)
        }

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickReplies.forEach { reply ->
                TextButton(onClick = { send(reply) }, modifier = bottomButtonModifier, contentPadding = bottomButtonPadding) {
                    Text(reply.ifEmpty { "⏎" })
                }
            }
            // Tab must NOT submit like the replies above (no trailing
            // Enter) — it's how you accept an autocomplete suggestion,
            // which is a raw keystroke, not a complete answer.
            TextButton(onClick = { sendRaw("\t") }, modifier = bottomButtonModifier, contentPadding = bottomButtonPadding) {
                Text("⇥")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = if (compactMode) 2.dp else 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = replyText,
                onValueChange = { replyText = it },
                modifier = replyFieldModifier,
                singleLine = true,
                placeholder = { Text("Reply…") }
            )
            Button(onClick = {
                send(replyText)
                replyText = ""
            }, modifier = sendButtonModifier, contentPadding = sendButtonPadding) {
                Text("Send")
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun TerminalWebView(
    modifier: Modifier = Modifier,
    onSwipePages: (Int) -> Unit,
    onTap: () -> Unit,
    onReady: (WebView) -> Unit
) {
    val onSwipePagesState = rememberUpdatedState(onSwipePages)
    val onTapState = rememberUpdatedState(onTap)
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

                // A vertical drag here sends real Page Up/Down presses to
                // the PC (see onSwipePages above) instead of scrolling a
                // local xterm buffer — Claude Code manages its own scroll
                // state via redraws, so there's no local buffer that
                // scrolling could meaningfully affect independent of the
                // PC anyway. One page per ~70% of this view's own height
                // dragged, so a near-full-screen swipe is roughly one
                // page, matching how swiping normally feels.
                val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
                    var accumulatedY = 0f

                    override fun onScroll(
                        e1: MotionEvent?,
                        e2: MotionEvent,
                        distanceX: Float,
                        distanceY: Float
                    ): Boolean {
                        if (kotlin.math.abs(distanceY) <= kotlin.math.abs(distanceX)) return false
                        val pageThresholdPx = height * 0.7f
                        if (pageThresholdPx <= 0) return false
                        accumulatedY += distanceY
                        var pages = 0
                        while (accumulatedY >= pageThresholdPx) {
                            accumulatedY -= pageThresholdPx
                            pages++
                        }
                        while (accumulatedY <= -pageThresholdPx) {
                            accumulatedY += pageThresholdPx
                            pages--
                        }
                        if (pages != 0) onSwipePagesState.value(pages)
                        return true
                    }

                    // Double-tap, not single-tap: a single tap consuming
                    // every tap (to send Ctrl+O) fought with the WebView's
                    // own pinch/double-tap zoom handling -- reported as
                    // "suddenly I can't zoom" once single-tap forwarding
                    // was added. Double-tap is unambiguous with a normal
                    // tap, which now falls through to the WebView
                    // untouched, and is still distinct from pinch-zoom
                    // (two fingers).
                    override fun onDoubleTap(e: MotionEvent): Boolean {
                        onTapState.value()
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
                // The captured terminal content includes plain-looking
                // URLs (from things like install instructions), and a
                // long-press on one triggered the WebView's native
                // link/text context menu -- surfacing as an unrelated
                // Android system "app info" popup on some builds. Nothing
                // in this read-only mirror needs a long-press menu.
                setOnLongClickListener { true }

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
