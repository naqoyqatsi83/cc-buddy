import { WebSocket } from "ws";
import { randomUUID } from "node:crypto";
import { addToken, removeToken } from "./tokenStore.js";
import type { PeerInfo } from "./types.js";
import type { BuddySession } from "./session.js";

const HANDSHAKE_TIMEOUT_MS = 10_000;

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
          device_name: hostDeviceName(),
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
        });
        session.info.peers.push(peer);
        resolve(peer);
        // Leave the socket attached for the PTY bridge (wired in a later
        // build-order step); don't close it here.
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

export function unpairPeer(session: BuddySession, peerId: string): boolean {
  const idx = session.info.peers.findIndex((p) => p.id === peerId);
  if (idx === -1) return false;
  session.info.peers.splice(idx, 1);
  removeToken(peerId);
  return true;
}

function hostDeviceName(): string {
  return process.env.COMPUTERNAME || process.env.HOSTNAME || "PC";
}
