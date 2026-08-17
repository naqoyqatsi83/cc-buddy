import express from "express";
import type { Server } from "node:http";
import { sessionRegistry } from "./sessionRegistry.js";
import { pairWithPhone, unpairPeer } from "./pairing.js";
import { scanForPhones } from "./mdns.js";
import { broadcastToPeers } from "./bridge.js";
import type { BuddySession } from "./session.js";

/** Mutable handle so hook routes can reach the session created just after
 * the control API starts listening (see cli.ts for the startup order). */
export interface SessionRef {
  current?: BuddySession;
}

/**
 * Starts the localhost-only control API used by the `/buddy-*` slash
 * commands and by Claude Code's HTTP hooks. Binds 127.0.0.1 only — never
 * expose this on the LAN (see spec's Security notes).
 */
export function startControlApi(port: number, sessionRef: SessionRef): Promise<Server> {
  const app = express();
  app.use(express.json());

  app.get("/sessions", (_req, res) => {
    res.json(sessionRegistry.list());
  });

  app.post("/sessions/:id/scan", async (req, res) => {
    const session = sessionRegistry.get(req.params.id);
    if (!session) return res.status(404).json({ error: "unknown session" });
    const phones = await scanForPhones();
    res.json(phones);
  });

  app.post("/sessions/:id/pair", async (req, res) => {
    const session = sessionRegistry.get(req.params.id);
    if (!session) return res.status(404).json({ error: "unknown session" });
    const { ip, port: peerPort, pin } = req.body ?? {};
    if (!ip || !peerPort || !pin) {
      return res.status(400).json({ error: "ip, port, and pin are required" });
    }
    try {
      const peer = await pairWithPhone(session, ip, peerPort, pin);
      res.json(peer);
    } catch (err: any) {
      res.status(502).json({ error: err.message ?? "pairing failed" });
    }
  });

  app.get("/sessions/:id/peers", (req, res) => {
    const session = sessionRegistry.get(req.params.id);
    if (!session) return res.status(404).json({ error: "unknown session" });
    res.json(session.info.peers);
  });

  app.post("/sessions/:id/unpair", async (req, res) => {
    const session = sessionRegistry.get(req.params.id);
    if (!session) return res.status(404).json({ error: "unknown session" });
    const { peer_id } = req.body ?? {};
    if (!peer_id) return res.status(400).json({ error: "peer_id is required" });
    const ok = await unpairPeer(session, peer_id);
    if (!ok) return res.status(404).json({ error: "unknown peer" });
    res.json({ ok: true });
  });

  // Claude Code native HTTP hook receivers (see .claude/settings.local.json,
  // written by hooksConfig.ts). Push straight to any connected paired
  // phone(s); FCM fallback for a backgrounded/killed app is Phase 2.
  app.post("/hook/notification", (req, res) => {
    const session = sessionRef.current;
    if (session) {
      broadcastToPeers(session, {
        type: "notification",
        message: req.body?.message ?? "Claude needs your attention",
        // Best-effort extracted prompt text + options (see ShadowTerminal),
        // read at this exact moment -- undefined if nothing substantive is
        // on screen to anchor it to, in which case the phone falls back to
        // the generic message above.
        question: session.spokenPrompt,
      });
    }
    res.json({ ok: true });
  });

  app.post("/hook/stop", (req, res) => {
    const session = sessionRef.current;
    if (session) {
      broadcastToPeers(session, { type: "stop" });
    }
    res.json({ ok: true });
  });

  app.post("/hook/pretooluse", (req, res) => {
    const session = sessionRef.current;
    if (session) {
      broadcastToPeers(session, {
        type: "pretooluse",
        tool: req.body?.tool_name ?? req.body?.tool ?? "unknown",
        input: req.body?.tool_input ?? req.body?.input ?? null,
      });
    }
    res.json({ ok: true });
  });

  return new Promise((resolve) => {
    const server = app.listen(port, "127.0.0.1", () => resolve(server));
  });
}
