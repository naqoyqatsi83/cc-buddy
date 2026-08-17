import { createHash } from "node:crypto";
import type { TLSSocket, PeerCertificate } from "node:tls";
import { WebSocket } from "ws";

export function sha256Fingerprint(cert: PeerCertificate): string {
  return createHash("sha256").update(cert.raw).digest("hex");
}

/**
 * Opens a `wss://` connection to the phone's self-signed TLS server.
 * `rejectUnauthorized: false` is required (there's no real CA), so
 * `onCert` -- called once the peer certificate is available, before any
 * pairing/reconnect message is sent -- is the actual trust decision:
 * TOFU on first pair, pinned-fingerprint verification on reconnect. If it
 * returns false the socket is torn down immediately.
 */
export function openSecureWs(
  ip: string,
  port: number,
  onCert: (fingerprint: string) => boolean
): WebSocket {
  const ws = new WebSocket(`wss://${ip}:${port}`, {
    rejectUnauthorized: false,
    // Ktor's Netty engine offers HTTP/2 via ALPN when the client proposes
    // it; a plain WebSocket upgrade is HTTP/1.1-only, so without pinning
    // this the connection can silently negotiate h2 and the handshake
    // just hangs. Not in @types/ws's ClientOptions, but it's a real
    // tls.connect() option that passes through.
    ALPNProtocols: ["http/1.1"],
  } as WebSocket.ClientOptions);

  ws.once("upgrade", (req) => {
    const socket = req.socket as TLSSocket;
    const cert = socket.getPeerCertificate();
    const fingerprint = sha256Fingerprint(cert);
    if (!onCert(fingerprint)) {
      ws.terminate();
    }
  });

  return ws;
}
