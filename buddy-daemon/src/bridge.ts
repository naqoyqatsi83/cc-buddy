import { WebSocket } from "ws";
import type { BuddySession } from "./session.js";
import { consumeSuppressedReconnect, scheduleReconnect, suppressReconnect } from "./reconnect.js";
import { logger } from "./logger.js";

const activeSockets = new Map<string, WebSocket>();

/**
 * Wires a paired phone's WS connection to the PTY in both directions
 * (build-order steps 4-5): raw PTY output chunks stream out as `pty_data`
 * frames, and `input` frames from the phone are written into the PTY as a
 * complete reply followed by Enter — matching the spec's quick-reply /
 * text-field-and-send-button UI rather than raw keystroke-by-keystroke
 * streaming.
 */
export function attachBridge(session: BuddySession, peerId: string, ws: WebSocket) {
  activeSockets.set(peerId, ws);

  const sendResize = () => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "resize", cols: session.pty.cols, rows: session.pty.rows }));
    }
  };
  // The phone must mirror the PC terminal's *exact* size — Claude Code's
  // TUI uses absolute cursor positioning sized for that terminal, so a
  // differently-sized mirror renders garbled. Send it once up front and
  // again whenever the PC terminal is resized.
  sendResize();
  const unsubscribeResize = session.onResize(sendResize);

  // A brand-new WS connection (fresh pairing, or reconnecting after the
  // app was killed/reinstalled) has only ever seen node-pty's raw output
  // as a live stream -- there's no backlog to replay. But Claude Code's
  // TUI paints its screen once and then only touches the cells that
  // change, so a mirror that starts from this point receives nothing for
  // whatever hasn't changed since -- the status bar/prompt look blank
  // even though the real PC terminal has always shown them, until enough
  // new content eventually forces a genuine full redraw. Repaint the
  // phone's screen from the ShadowTerminal's current state first, as an
  // ordinary pty_data chunk, so it's fully caught up before any live
  // deltas arrive.
  if (ws.readyState === WebSocket.OPEN) {
    const snapshot = session.getSnapshot();
    if (snapshot) {
      ws.send(JSON.stringify({ type: "pty_data", data: snapshot }));
    }
  }

  // Full history so far (see ShadowTerminal), sent once up front so the
  // phone's independent scrollback pane starts complete instead of empty
  // — then just the new lines as they're captured.
  if (ws.readyState === WebSocket.OPEN && session.transcript.length > 0) {
    ws.send(JSON.stringify({ type: "transcript_init", lines: session.transcript }));
  }
  const unsubscribeTranscript = session.onTranscriptAppend((lines) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "transcript_append", lines }));
    }
  });

  // The current active screen (prompt + status) — a fixed footer on the
  // phone, replaced wholesale each time rather than appended to history.
  if (ws.readyState === WebSocket.OPEN && session.tail.length > 0) {
    ws.send(JSON.stringify({ type: "tail_update", lines: session.tail }));
  }
  const unsubscribeTail = session.onTailUpdate((lines) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "tail_update", lines }));
    }
  });

  // Numbered-menu options detected in the current PC-screen viewport (see
  // ShadowTerminal) -- drives the phone's dynamic quick-reply buttons.
  if (ws.readyState === WebSocket.OPEN && session.menuOptions.length > 0) {
    ws.send(JSON.stringify({ type: "menu_options", options: session.menuOptions }));
  }
  const unsubscribeMenuOptions = session.onMenuOptionsUpdate((options) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "menu_options", options }));
    }
  });

  const unsubscribe = session.onData((chunk) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "pty_data", data: chunk }));
    }
  });

  // Round-trip latency for /buddy-list and (mirrored independently, not
  // relayed) the phone's own connection-details display -- each side pings
  // the other on its own timer and measures its own view of the round
  // trip, so a one-way degradation shows up on whichever side it actually
  // affects rather than needing the other side to report it.
  const pingInterval = setInterval(() => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "ping", ts: Date.now() }));
    }
  }, 10_000);

  // Input applied from the phone is written straight into the PTY the
  // same way local keystrokes are -- indistinguishable in the terminal's
  // own output, since the PTY doesn't know where a keystroke came from.
  // Logging it (audit marker for #8) goes to the daemon log rather than
  // injecting a notice directly into the live terminal output: Claude
  // Code's TUI manages its own absolute-cursor-positioned redraws, and an
  // out-of-band stdout write in the middle of one risks visibly
  // corrupting the display for a cosmetic marker.
  const logPhoneInput = (data: string, raw = false) => {
    const peerName = session.info.peers.find((p) => p.id === peerId)?.name ?? peerId;
    const preview = data.length > 200 ? data.slice(0, 200) + "…" : data;
    logger.info("input from phone", { peerId, deviceName: peerName, raw, preview });
  };

  ws.on("message", (raw) => {
    let msg: any;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (msg.type === "input" && typeof msg.data === "string") {
      logPhoneInput(msg.data);
      session.pty.write(msg.data + "\r");
    } else if (msg.type === "raw_input" && typeof msg.data === "string") {
      // A literal keystroke (e.g. Tab, for autocomplete) — must NOT get
      // an Enter appended, unlike a complete reply.
      logPhoneInput(msg.data, true);
      session.pty.write(msg.data);
    } else if (msg.type === "ping" && typeof msg.ts === "number") {
      if (ws.readyState === WebSocket.OPEN) {
        ws.send(JSON.stringify({ type: "pong", ts: msg.ts }));
      }
    } else if (msg.type === "pong" && typeof msg.ts === "number") {
      const peer = session.info.peers.find((p) => p.id === peerId);
      if (peer) {
        peer.latencyMs = Date.now() - msg.ts;
        peer.lastSeenAt = new Date().toISOString();
      }
    }
  });

  const cleanup = () => {
    unsubscribe();
    unsubscribeResize();
    unsubscribeTranscript();
    unsubscribeTail();
    unsubscribeMenuOptions();
    clearInterval(pingInterval);
    // Only drop the bridge for this exact socket -- if a reconnect already
    // replaced it in activeSockets (a stale event firing after the new
    // socket attached), don't tear down the live one.
    if (activeSockets.get(peerId) === ws) activeSockets.delete(peerId);
    const peer = session.info.peers.find((p) => p.id === peerId);
    if (peer) peer.connected = false;
    // A deliberate unpair already knows the peer is gone; anything else
    // (network drop, phone app killed, Tailscale re-route) is worth
    // retrying to get back to a durable connection.
    if (!consumeSuppressedReconnect(peerId)) {
      logger.info("peer disconnected, scheduling reconnect", { peerId, deviceName: peer?.name });
      scheduleReconnect(session, peerId);
    }
  };
  ws.on("close", cleanup);
  ws.on("error", cleanup);
}

export function closeBridge(peerId: string) {
  const ws = activeSockets.get(peerId);
  if (ws) {
    suppressReconnect(peerId);
    ws.close();
    activeSockets.delete(peerId);
  }
}

/**
 * Pushes a JSON message to every currently-connected peer of a session —
 * used by the `/hook/*` receivers to forward Claude Code's Notification /
 * Stop / PreToolUse events to whichever phone(s) are paired.
 */
export function broadcastToPeers(session: BuddySession, message: unknown) {
  const payload = JSON.stringify(message);
  for (const peer of session.info.peers) {
    if (!peer.connected) continue;
    const ws = activeSockets.get(peer.id);
    if (ws && ws.readyState === WebSocket.OPEN) {
      ws.send(payload);
    }
  }
}
