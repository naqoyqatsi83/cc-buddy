package dev.ccbuddy.app.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

    LaunchedEffect(peerId, webView) {
        val wv = webView ?: return@LaunchedEffect
        val flow = terminalBridge.outputFlow(peerId) ?: return@LaunchedEffect
        // The WebView instance is reused across peer switches (Compose
        // doesn't recreate it just because peerId changed), so clear
        // whatever the previous peer left on screen before replaying this
        // one's backlog — otherwise the two peers' output visually
        // concatenates in the same xterm.js buffer.
        wv.evaluateJavascript("clearTerminal()", null)
        flow.collect { chunk ->
            val b64 = Base64.encodeToString(chunk.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            wv.evaluateJavascript("writeChunkB64('$b64')", null)
        }
    }

    fun send(text: String) {
        scope.launch { terminalBridge.sendInput(peerId, text) }
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            QUICK_REPLIES.forEach { reply ->
                TextButton(onClick = { send(reply) }) {
                    Text(reply.ifEmpty { "⏎" })
                }
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

@SuppressLint("SetJavaScriptEnabled")
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
