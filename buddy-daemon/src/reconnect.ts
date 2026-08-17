import { attachBridge } from "./bridge.js";
import { getToken, removeToken } from "./tokenStore.js";
import { openSecureWs } from "./tls.js";
import { logger } from "./logger.js";
import type { BuddySession } from "./session.js";

// A brief network blip (mobile network handoff, Tailscale re-routing around
// a dead path, the phone's WS server restarting) should recover in seconds;
// a phone that's genuinely gone (app killed, powered off) shouldn't be
// hammered forever, so back off but never fully give up on it -- the PC
// side has no way to know "gone for good" from "still reachable eventually"
// the way a token-rejection does (see reconnect_denied below).
const BACKOFF_MS = [1000, 2000, 5000, 10000, 30000];
const MAX_BACKOFF_MS = 60_000;
const DIAL_TIMEOUT_MS = 8000;

const timers = new Map<string, NodeJS.Timeout>();
// peerIds whose *next* close event was caused by us (unpair), not a real
// drop -- attachBridge's cleanup consults this so a deliberate unpair
// doesn't immediately reconnect the peer it was just asked to remove.
const suppressed = new Set<string>();

export function suppressReconnect(peerId: string) {
  suppressed.add(peerId);
}

/** Called once by cleanup() on every close/error. Returns true if this
 * close was expected (unpair or an in-flight reconnect superseding an old
 * socket) and no reconnect should be scheduled. */
export function consumeSuppressedReconnect(peerId: string): boolean {
  return suppressed.delete(peerId);
}

export function cancelReconnect(peerId: string) {
  const timer = timers.get(peerId);
  if (timer) {
    clearTimeout(timer);
    timers.delete(peerId);
  }
}

export function scheduleReconnect(session: BuddySession, peerId: string, attempt = 0) {
  cancelReconnect(peerId);
  const delay = BACKOFF_MS[attempt] ?? MAX_BACKOFF_MS;
  const timer = setTimeout(() => {
    timers.delete(peerId);
    attemptReconnect(session, peerId, attempt);
  }, delay);
  timers.set(peerId, timer);
}

async function attemptReconnect(session: BuddySession, peerId: string, attempt: number) {
  // The peer may have been unpaired while we were waiting to retry.
  const peer = session.info.peers.find((p) => p.id === peerId);
  if (!peer) return;

  const record = await getToken(peerId);
  if (!record) return; // token gone (unpaired) -- nothing to reconnect with

  let settled = false;

  // The phone no longer recognizes this token, or its cert no longer
  // matches what was pinned at pairing time -- either way retrying won't
  // ever succeed, so drop the peer instead of backing off forever.
  const dropPeer = () => {
    settled = true;
    clearTimeout(timeout);
    ws.terminate();
    removeToken(peerId).catch((err) => logger.error("failed to remove stale token", { peerId, error: String(err) }));
    const idx = session.info.peers.findIndex((p) => p.id === peerId);
    if (idx !== -1) session.info.peers.splice(idx, 1);
    logger.warn("peer dropped (token/cert rejected)", { peerId, deviceName: peer.name });
  };

  // Verify the phone's cert still matches what was pinned at pairing time
  // -- a missing (pre-TLS peer) or mismatched fingerprint is treated the
  // same as a rejected token.
  const ws = openSecureWs(record.ip, record.port, (fp) => {
    if (record.certFingerprint !== fp) {
      dropPeer();
      return false;
    }
    return true;
  });

  const retry = () => {
    if (settled) return;
    settled = true;
    ws.removeAllListeners();
    scheduleReconnect(session, peerId, attempt + 1);
  };

  const timeout = setTimeout(() => {
    ws.terminate();
    retry();
  }, DIAL_TIMEOUT_MS);

  ws.on("open", () => {
    ws.send(JSON.stringify({ type: "reconnect", token: record.token }));
  });

  ws.on("message", (raw) => {
    if (settled) return;
    let msg: any;
    try {
      msg = JSON.parse(raw.toString());
    } catch {
      return;
    }

    if (msg.type === "reconnect_ok") {
      settled = true;
      clearTimeout(timeout);
      peer.connected = true;
      attachBridge(session, peerId, ws);
      logger.info("peer reconnected", { peerId, deviceName: peer.name, attempt });
    } else if (msg.type === "reconnect_denied") {
      dropPeer();
    }
  });

  ws.on("error", () => {
    clearTimeout(timeout);
    logger.info("reconnect attempt failed, retrying", { peerId, deviceName: peer.name, attempt });
    retry();
  });
}
