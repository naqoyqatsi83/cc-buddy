import { appendFileSync, existsSync, mkdirSync, renameSync, statSync, unlinkSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

/**
 * Basic file logging for connection lifecycle events and errors. Before
 * this, the daemon only ever wrote to whatever terminal `buddy start`
 * happened to be running in -- console output, nothing persisted -- so a
 * problem that happened while nobody was watching the terminal (a crash,
 * a rejected reconnect, a hook failure) left no trace to debug after the
 * fact.
 *
 * Deliberately simple: synchronous appends (log volume here is a handful
 * of lines per connection event, not a hot path) and a single-generation
 * size-based rotation, not a full logging framework -- this project has
 * no logging dependency and doesn't need one for what's actually logged.
 */
const LOG_DIR = join(homedir(), ".buddy");
const LOG_FILE = join(LOG_DIR, "daemon.log");
const MAX_LOG_BYTES = 5 * 1024 * 1024; // 5MB before rotating

type Level = "info" | "warn" | "error";

function rotateIfNeeded() {
  if (!existsSync(LOG_FILE)) return;
  try {
    if (statSync(LOG_FILE).size < MAX_LOG_BYTES) return;
  } catch {
    return;
  }
  const rotated = `${LOG_FILE}.1`;
  try {
    if (existsSync(rotated)) unlinkSync(rotated);
    renameSync(LOG_FILE, rotated);
  } catch {
    // If rotation itself fails, just keep appending to the existing file
    // rather than losing log output entirely.
  }
}

function write(level: Level, message: string, meta?: Record<string, unknown>) {
  const line = `${new Date().toISOString()} [${level.toUpperCase()}] ${message}${
    meta ? " " + JSON.stringify(meta) : ""
  }\n`;

  // Deliberately file-only, never console.error/warn -- `buddy start`'s
  // PTY output is written straight to this same process's stdout (see
  // session.ts's ptyProcess.onData), and Claude Code's TUI does absolute-
  // cursor-position redraws assuming exclusive control of that terminal.
  // A stray console write (e.g. a reconnect's cert/token rejection,
  // logged mid-session) interleaves with that stream and visibly
  // corrupts the live screen instead of just scrolling past harmlessly.
  // The log file below is the only place this should surface.
  try {
    mkdirSync(LOG_DIR, { recursive: true, mode: 0o700 });
    rotateIfNeeded();
    appendFileSync(LOG_FILE, line, { mode: 0o600 });
  } catch {
    // Logging must never be the reason a real connection/pairing
    // operation fails -- a permissions issue or full disk here is a
    // silent no-op, not a thrown error.
  }
}

export const logger = {
  info: (message: string, meta?: Record<string, unknown>) => write("info", message, meta),
  warn: (message: string, meta?: Record<string, unknown>) => write("warn", message, meta),
  error: (message: string, meta?: Record<string, unknown>) => write("error", message, meta),
};
