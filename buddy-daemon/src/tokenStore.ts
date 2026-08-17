import { mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import keytar from "keytar";

const KEYCHAIN_SERVICE = "cc-buddy";

const STORE_DIR = join(homedir(), ".buddy");
const STORE_FILE = join(STORE_DIR, "peers.json");

/**
 * Non-secret pairing metadata, kept in a plain local file. The actual
 * long-lived token lives in the OS keychain via keytar, keyed by peerId —
 * never written to disk in the clear, per the spec's security notes
 * ("Long-lived tokens stored via OS keychain (keytar) on PC").
 */
interface PeerRecord {
  peerId: string;
  sessionId: string;
  deviceName: string;
  ip: string;
  port: number;
  pairedAt: string;
  // Absent on records written before TLS pinning was added -- reconnect.ts
  // treats a missing fingerprint as a mismatch, forcing a manual re-pair
  // rather than silently trusting whatever cert the phone presents.
  certFingerprint?: string;
}

export interface TokenRecord extends PeerRecord {
  token: string;
}

function loadMetadata(): PeerRecord[] {
  if (!existsSync(STORE_FILE)) return [];
  try {
    return JSON.parse(readFileSync(STORE_FILE, "utf8"));
  } catch {
    return [];
  }
}

function saveMetadata(records: PeerRecord[]) {
  mkdirSync(STORE_DIR, { recursive: true, mode: 0o700 });
  writeFileSync(STORE_FILE, JSON.stringify(records, null, 2), { mode: 0o600 });
}

export async function addToken(record: TokenRecord): Promise<void> {
  const { token, ...metadata } = record;
  const records = loadMetadata().filter((r) => r.peerId !== record.peerId);
  records.push(metadata);
  saveMetadata(records);
  await keytar.setPassword(KEYCHAIN_SERVICE, record.peerId, token);
}

export async function removeToken(peerId: string): Promise<void> {
  saveMetadata(loadMetadata().filter((r) => r.peerId !== peerId));
  await keytar.deletePassword(KEYCHAIN_SERVICE, peerId);
}

/** Reunites metadata with its keychain-stored token. Entries whose token
 * went missing from the keychain (e.g. cleared outside the app) are
 * dropped rather than returned half-broken. */
export async function listTokensForSession(sessionId: string): Promise<TokenRecord[]> {
  const metas = loadMetadata().filter((r) => r.sessionId === sessionId);
  const withTokens = await Promise.all(
    metas.map(async (m): Promise<TokenRecord | null> => {
      const token = await keytar.getPassword(KEYCHAIN_SERVICE, m.peerId);
      return token ? { ...m, token } : null;
    })
  );
  return withTokens.filter((r): r is TokenRecord => r !== null);
}

/** Single-peer lookup for auto-reconnect (see reconnect.ts) -- doesn't need
 * the rest of the session's peers, just this one's ip/port/token. */
export async function getToken(peerId: string): Promise<TokenRecord | null> {
  const meta = loadMetadata().find((r) => r.peerId === peerId);
  if (!meta) return null;
  const token = await keytar.getPassword(KEYCHAIN_SERVICE, peerId);
  return token ? { ...meta, token } : null;
}
