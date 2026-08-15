import { WebSocket } from "ws";
import { randomUUID } from "node:crypto";
import * as os from "node:os";
import * as path from "node:path";
import { addToken, removeToken } from "./tokenStore.js";
import { attachBridge, closeBridge } from "./bridge.js";
import type { PeerInfo } from "./types.js";
import type { BuddySession } from "./session.js";

// Generous enough for a human to actually notice the phone, glance at the
// request, and tap Accept — 10s proved too tight even in manual testing.
const HANDSHAKE_TIMEOUT_MS = 60_000;

/**
 * Dials out to the phone (PC always initiates, per spec — phones don't take
 * inbound connections from the internet-facing PC role) and runs the PIN
 * handshake described in Component 3.
 */
export function pairWithPhone(
  session: BuddySession,
  ip: string,
  port: number,
  pin: string
): Promise<PeerInfo> {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`ws://${ip}:${port}`);
    const timer = setTimeout(() => {
      ws.terminate();
      reject(new Error("Pairing timed out waiting for phone response"));
    }, HANDSHAKE_TIMEOUT_MS);

    ws.on("open", () => {
      ws.send(
        JSON.stringify({
          type: "pair_request",
          pin,
          device_name: hostDeviceName(session.info.cwd),
        })
      );
    });

    ws.on("message", (raw) => {
      let msg: any;
      try {
        msg = JSON.parse(raw.toString());
      } catch {
        return;
      }

      if (msg.type === "pair_ok") {
        clearTimeout(timer);
        const peer: PeerInfo = {
          id: randomUUID(),
          name: msg.device_name ?? "Unknown phone",
          ip,
          port,
          connected: true,
          pairedAt: new Date().toISOString(),
        };
        addToken({
          peerId: peer.id,
          sessionId: session.info.id,
          deviceName: peer.name,
          ip,
          port,
          token: msg.token,
          pairedAt: peer.pairedAt,
        }).catch((err) => console.error("failed to save pairing token", err));
        session.info.peers.push(peer);
        attachBridge(session, peer.id, ws);
        resolve(peer);
      } else if (msg.type === "pair_denied") {
        clearTimeout(timer);
        ws.close();
        reject(new Error(msg.reason ?? "Pairing was denied on the phone"));
      }
    });

    ws.on("error", (err) => {
      clearTimeout(timer);
      reject(err);
    });
  });
}

export async function unpairPeer(session: BuddySession, peerId: string): Promise<boolean> {
  const idx = session.info.peers.findIndex((p) => p.id === peerId);
  if (idx === -1) return false;
  session.info.peers.splice(idx, 1);
  await removeToken(peerId);
  closeBridge(peerId);
  return true;
}

// "PC" alone doesn't distinguish sessions once someone has more than one
// paired -- login@hostname:cwd (e.g. "gus@laptop:cc-buddy") mirrors what
// you'd see in a real terminal prompt and is enough to tell sessions apart
// at a glance on the phone.
function hostDeviceName(sessionCwd: string): string {
  const user = os.userInfo().username || "user";
  const host = (process.env.COMPUTERNAME || os.hostname() || "PC").split(".")[0];
  // The session's own --cwd (the project being worked on), not
  // process.cwd() -- that's wherever `buddy` itself was invoked from,
  // which is frequently the daemon's own install directory rather than
  // the project the user is actually pairing to look at.
  const cwd = path.basename(sessionCwd) || "/";
  return `${user}@${host}:${cwd}`;
}
