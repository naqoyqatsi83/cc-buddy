import { WebSocket } from "ws";
import type { BuddySession } from "./session.js";

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

  const unsubscribe = session.onData((chunk) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: "pty_data", data: chunk }));
    }
  });

  ws.on("message", (raw) => {
    let msg: any;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }
    if (msg.type === "input" && typeof msg.data === "string") {
      session.pty.write(msg.data + "\r");
    } else if (msg.type === "raw_input" && typeof msg.data === "string") {
      // A literal keystroke (e.g. Tab, for autocomplete) — must NOT get
      // an Enter appended, unlike a complete reply.
      session.pty.write(msg.data);
    }
  });

  const cleanup = () => {
    unsubscribe();
    unsubscribeResize();
    activeSockets.delete(peerId);
    const peer = session.info.peers.find((p) => p.id === peerId);
    if (peer) peer.connected = false;
  };
  ws.on("close", cleanup);
  ws.on("error", cleanup);
}

export function closeBridge(peerId: string) {
  const ws = activeSockets.get(peerId);
  if (ws) {
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
