import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { EventEmitter } from "node:events";
import type { PeerInfo } from "../types.js";
import type { BuddySession } from "../session.js";

// reconnect.ts talks to the real network (tls.ts), the OS keychain
// (tokenStore.ts) and the live PTY bridge (bridge.ts) -- all mocked so
// these tests exercise only the state machine (backoff, suppression,
// cert/token rejection) deterministically and offline.
vi.mock("../tls.js", () => ({ openSecureWs: vi.fn() }));
vi.mock("../bridge.js", () => ({ attachBridge: vi.fn() }));
vi.mock("../tokenStore.js", () => ({ getToken: vi.fn(), removeToken: vi.fn() }));
vi.mock("../logger.js", () => ({
  logger: { info: vi.fn(), warn: vi.fn(), error: vi.fn(), debug: vi.fn() },
}));

import { openSecureWs } from "../tls.js";
import { attachBridge } from "../bridge.js";
import { getToken, removeToken } from "../tokenStore.js";
import {
  scheduleReconnect,
  cancelReconnect,
  suppressReconnect,
  consumeSuppressedReconnect,
} from "../reconnect.js";

class FakeWs extends EventEmitter {
  terminated = false;
  sent: string[] = [];
  terminate() {
    this.terminated = true;
  }
  send(data: string) {
    this.sent.push(data);
  }
  removeAllListeners(event?: string) {
    return super.removeAllListeners(event);
  }
}

function makePeer(overrides: Partial<PeerInfo> = {}): PeerInfo {
  return {
    id: "peer-1",
    name: "Test Phone",
    ip: "192.168.1.50",
    port: 8765,
    connected: false,
    pairedAt: new Date().toISOString(),
    ...overrides,
  };
}

function makeSession(peers: PeerInfo[]): BuddySession {
  return {
    info: { id: "session-1", cwd: ".", controlPort: 0, startedAt: new Date().toISOString(), peers },
  } as unknown as BuddySession;
}

describe("reconnect", () => {
  let lastFakeWs: FakeWs;
  let lastOnCert: ((fp: string) => boolean) | undefined;

  beforeEach(() => {
    vi.useFakeTimers();
    vi.mocked(openSecureWs).mockImplementation((_ip, _port, onCert) => {
      lastFakeWs = new FakeWs();
      lastOnCert = onCert;
      return lastFakeWs as any;
    });
    vi.mocked(getToken).mockReset();
    vi.mocked(removeToken).mockReset().mockResolvedValue(undefined);
    vi.mocked(attachBridge).mockReset();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.clearAllMocks();
  });

  describe("suppression", () => {
    it("consumeSuppressedReconnect returns true exactly once after suppressReconnect", () => {
      suppressReconnect("peer-1");
      expect(consumeSuppressedReconnect("peer-1")).toBe(true);
      expect(consumeSuppressedReconnect("peer-1")).toBe(false);
    });

    it("consumeSuppressedReconnect returns false when never suppressed", () => {
      expect(consumeSuppressedReconnect("never-suppressed")).toBe(false);
    });
  });

  describe("backoff scheduling", () => {
    it("uses the attempt-indexed backoff delay (1000ms for attempt 0)", async () => {
      const session = makeSession([makePeer()]);
      vi.mocked(getToken).mockResolvedValue(null);

      scheduleReconnect(session, "peer-1", 0);
      await vi.advanceTimersByTimeAsync(999);
      expect(getToken).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(1);
      expect(getToken).toHaveBeenCalledWith("peer-1");
    });

    it("falls back to the max backoff once past the end of the table", async () => {
      const session = makeSession([makePeer()]);
      vi.mocked(getToken).mockResolvedValue(null);

      // BACKOFF_MS has 5 entries (indices 0-4); attempt 10 is well past
      // the end and should use the 60s ceiling, not undefined/NaN.
      scheduleReconnect(session, "peer-1", 10);
      await vi.advanceTimersByTimeAsync(59_999);
      expect(getToken).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(1);
      expect(getToken).toHaveBeenCalledWith("peer-1");
    });

    it("cancelReconnect prevents a scheduled attempt from firing", async () => {
      const session = makeSession([makePeer()]);
      vi.mocked(getToken).mockResolvedValue(null);

      scheduleReconnect(session, "peer-1", 0);
      cancelReconnect("peer-1");
      await vi.advanceTimersByTimeAsync(60_000);

      expect(getToken).not.toHaveBeenCalled();
    });

    it("scheduleReconnect replaces a previously scheduled attempt for the same peer", async () => {
      const session = makeSession([makePeer()]);
      vi.mocked(getToken).mockResolvedValue(null);

      scheduleReconnect(session, "peer-1", 0); // would fire at 1000ms
      await vi.advanceTimersByTimeAsync(500);
      scheduleReconnect(session, "peer-1", 4); // reschedules at 30000ms from now

      // The original 1000ms attempt must not fire just because its
      // original deadline passed.
      await vi.advanceTimersByTimeAsync(600);
      expect(getToken).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(29_400);
      expect(getToken).toHaveBeenCalledTimes(1);
    });
  });

  describe("attemptReconnect", () => {
    it("does nothing if the peer was unpaired before the attempt fires", async () => {
      const session = makeSession([]); // peer already removed
      scheduleReconnect(session, "peer-1", 0);
      await vi.advanceTimersByTimeAsync(1000);

      expect(getToken).not.toHaveBeenCalled();
      expect(openSecureWs).not.toHaveBeenCalled();
    });

    it("does nothing if the token was removed (unpaired) while waiting to retry", async () => {
      const session = makeSession([makePeer()]);
      vi.mocked(getToken).mockResolvedValue(null);

      scheduleReconnect(session, "peer-1", 0);
      await vi.advanceTimersByTimeAsync(1000);

      expect(openSecureWs).not.toHaveBeenCalled();
    });

    it("drops the peer when the cert fingerprint no longer matches what was pinned", async () => {
      const peer = makePeer();
      const session = makeSession([peer]);
      vi.mocked(getToken).mockResolvedValue({
        peerId: "peer-1",
        sessionId: "session-1",
        deviceName: "Test Phone",
        ip: "192.168.1.50",
        port: 8765,
        pairedAt: new Date().toISOString(),
        certFingerprint: "expected-fp",
        token: "tok",
      });

      scheduleReconnect(session, "peer-1", 0);
      await vi.advanceTimersByTimeAsync(1000);

      expect(lastOnCert!("different-fp")).toBe(false);
      expect(lastFakeWs!.terminated).toBe(true);
      expect(removeToken).toHaveBeenCalledWith("peer-1");
      expect(session.info.peers).toHaveLength(0);
    });

    it("drops the peer on reconnect_denied", async () => {
      const peer = makePeer();
      const session = makeSession([peer]);
      vi.mocked(getToken).mockResolvedValue({
        peerId: "peer-1",
        sessionId: "session-1",
        deviceName: "Test Phone",
        ip: "192.168.1.50",
        port: 8765,
        pairedAt: new Date().toISOString(),
        certFingerprint: "fp",
        token: "tok",
      });

      scheduleReconnect(session, "peer-1", 0);
      await vi.advanceTimersByTimeAsync(1000);

      lastFakeWs!.emit("message", Buffer.from(JSON.stringify({ type: "reconnect_denied" })));

      expect(lastFakeWs!.terminated).toBe(true);
      expect(removeToken).toHaveBeenCalledWith("peer-1");
      expect(session.info.peers).toHaveLength(0);
    });

    it("marks the peer connected and attaches the bridge on reconnect_ok", async () => {
      const peer = makePeer();
      const session = makeSession([peer]);
      vi.mocked(getToken).mockResolvedValue({
        peerId: "peer-1",
        sessionId: "session-1",
        deviceName: "Test Phone",
        ip: "192.168.1.50",
        port: 8765,
        pairedAt: new Date().toISOString(),
        certFingerprint: "fp",
        token: "tok",
      });

      scheduleReconnect(session, "peer-1", 0);
      await vi.advanceTimersByTimeAsync(1000);

      lastFakeWs!.emit("message", Buffer.from(JSON.stringify({ type: "reconnect_ok" })));

      expect(peer.connected).toBe(true);
      expect(attachBridge).toHaveBeenCalledWith(session, "peer-1", lastFakeWs);
      expect(removeToken).not.toHaveBeenCalled();
      // Peer stays in the list on success -- only dropPeer() removes it.
      expect(session.info.peers).toHaveLength(1);
    });

    it("retries with backoff instead of dropping the peer on a plain connection error", async () => {
      const peer = makePeer();
      const session = makeSession([peer]);
      vi.mocked(getToken).mockResolvedValue({
        peerId: "peer-1",
        sessionId: "session-1",
        deviceName: "Test Phone",
        ip: "192.168.1.50",
        port: 8765,
        pairedAt: new Date().toISOString(),
        certFingerprint: "fp",
        token: "tok",
      });

      scheduleReconnect(session, "peer-1", 0);
      await vi.advanceTimersByTimeAsync(1000); // attempt 0 fires, getToken called once
      expect(getToken).toHaveBeenCalledTimes(1);

      lastFakeWs!.emit("error", new Error("ECONNREFUSED"));

      // A plain error doesn't drop the peer -- it's still in the list, and
      // a fresh attempt (attempt 1 -> 2000ms backoff) should follow.
      expect(session.info.peers).toHaveLength(1);
      expect(removeToken).not.toHaveBeenCalled();

      await vi.advanceTimersByTimeAsync(1999);
      expect(getToken).toHaveBeenCalledTimes(1);
      await vi.advanceTimersByTimeAsync(1);
      expect(getToken).toHaveBeenCalledTimes(2);
    });
  });
});
