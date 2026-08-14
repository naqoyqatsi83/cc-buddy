import express from "express";
import type { Server } from "node:http";
import { sessionRegistry } from "./sessionRegistry.js";
import { pairWithPhone, unpairPeer } from "./pairing.js";
import { scanForPhones } from "./mdns.js";

/**
 * Starts the localhost-only control API used by the `/buddy-*` slash
 * commands and by Claude Code's HTTP hooks. Binds 127.0.0.1 only — never
 * expose this on the LAN (see spec's Security notes).
 */
export function startControlApi(port: number): Promise<Server> {
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

  app.post("/sessions/:id/unpair", (req, res) => {
    const session = sessionRegistry.get(req.params.id);
    if (!session) return res.status(404).json({ error: "unknown session" });
    const { peer_id } = req.body ?? {};
    if (!peer_id) return res.status(400).json({ error: "peer_id is required" });
    const ok = unpairPeer(session, peer_id);
    if (!ok) return res.status(404).json({ error: "unknown peer" });
    res.json({ ok: true });
  });

  // Claude Code native HTTP hook receivers (see .claude/settings.json).
  app.post("/hook/notification", (req, res) => {
    // TODO(build-order step 6): forward to paired phone(s) via WS push /
    // FCM fallback. Logged only for now.
    console.log("[hook:notification]", req.body);
    res.json({ ok: true });
  });

  app.post("/hook/stop", (req, res) => {
    console.log("[hook:stop]", req.body);
    res.json({ ok: true });
  });

  app.post("/hook/pretooluse", (req, res) => {
    console.log("[hook:pretooluse]", req.body);
    res.json({ ok: true });
  });

  return new Promise((resolve) => {
    const server = app.listen(port, "127.0.0.1", () => resolve(server));
  });
}
