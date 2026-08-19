import type { WebSocket } from "ws";
import { randomUUID } from "node:crypto";
import * as os from "node:os";
import * as path from "node:path";
import { addToken, removeToken } from "./tokenStore.js";
import { attachBridge, closeBridge } from "./bridge.js";
import { cancelReconnect } from "./reconnect.js";
import { openSecureWs } from "./tls.js";
import { logger } from "./logger.js";
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
    // First pairing has nothing to pin against yet -- TOFU: accept
    // whatever cert the phone presents, and capture its fingerprint so it
    // can be pinned for all future reconnects once pairing succeeds.
    let certFingerprint: string | undefined;
    const ws = openSecureWs(ip, port, (fp) => {
      certFingerprint = fp;
      return true;
    });
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
          certFingerprint,
        }).catch((err) => logger.error("failed to save pairing token", { peerId: peer.id, error: String(err) }));
        session.info.peers.push(peer);
        attachBridge(session, peer.id, ws);
        logger.info("peer paired", { peerId: peer.id, deviceName: peer.name, ip, port });
        resolve(peer);
      } else if (msg.type === "pair_denied") {
        clearTimeout(timer);
        ws.close();
        logger.warn("pairing denied", { ip, port, reason: msg.reason ?? "unknown" });
        reject(new Error(msg.reason ?? "Pairing was denied on the phone"));
      }
    });

    ws.on("error", (err) => {
      clearTimeout(timer);
      logger.warn("pairing dial failed", { ip, port, error: String(err) });
      reject(err);
    });
  });
}

export async function unpairPeer(session: BuddySession, peerId: string): Promise<boolean> {
  const idx = session.info.peers.findIndex((p) => p.id === peerId);
  if (idx === -1) return false;
  const peer = session.info.peers[idx];
  session.info.peers.splice(idx, 1);
  await removeToken(peerId);
  cancelReconnect(peerId);
  closeBridge(peerId);
  logger.info("peer unpaired", { peerId, deviceName: peer.name });
  return true;
}

// "PC" alone doesn't distinguish sessions once someone has more than one
// paired -- login@hostname:cwd (e.g. "gus@laptop:cc-buddy") mirrors what
// you'd see in a real terminal prompt and is enough to tell sessions apart
// at a glance on the phone.
export function hostDeviceName(sessionCwd: string): string {
  const user = os.userInfo().username || "user";
  const host = (process.env.COMPUTERNAME || os.hostname() || "PC").split(".")[0];
  // The session's own --cwd (the project being worked on), not
  // process.cwd() -- that's wherever `buddy` itself was invoked from,
  // which is frequently the daemon's own install directory rather than
  // the project the user is actually pairing to look at.
  //
  // path.resolve() first: --cwd is whatever the user typed verbatim (the
  // commander default is process.cwd(), which happens to already be
  // absolute, but an explicit relative value like `--cwd .` is not) --
  // basename-ing an unresolved "." just gives back "." itself, showing up
  // on the phone as a session literally named "user@host:." instead of
  // the actual project directory name.
  const cwd = path.basename(path.resolve(sessionCwd)) || "/";
  return `${user}@${host}:${cwd}`;
}
