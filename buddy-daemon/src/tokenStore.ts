import { mkdirSync, readFileSync, writeFileSync, existsSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

// TODO(phase2): swap for OS keychain (keytar) per spec's security notes.
// This is the "encrypted local file" fallback tier — currently plaintext
// JSON with owner-only permissions, encryption to be layered on before
// this leaves the prototyping phase.
const STORE_DIR = join(homedir(), ".buddy");
const STORE_FILE = join(STORE_DIR, "tokens.json");

interface TokenRecord {
  peerId: string;
  sessionId: string;
  deviceName: string;
  ip: string;
  port: number;
  token: string;
  pairedAt: string;
}

function load(): TokenRecord[] {
  if (!existsSync(STORE_FILE)) return [];
  return JSON.parse(readFileSync(STORE_FILE, "utf8"));
}

function save(records: TokenRecord[]) {
  mkdirSync(STORE_DIR, { recursive: true, mode: 0o700 });
  writeFileSync(STORE_FILE, JSON.stringify(records, null, 2), { mode: 0o600 });
}

export function addToken(record: TokenRecord) {
  const records = load().filter((r) => r.peerId !== record.peerId);
  records.push(record);
  save(records);
}

export function removeToken(peerId: string) {
  save(load().filter((r) => r.peerId !== peerId));
}

export function listTokensForSession(sessionId: string): TokenRecord[] {
  return load().filter((r) => r.sessionId === sessionId);
}
