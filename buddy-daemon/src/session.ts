import * as pty from "node-pty";
import { randomUUID } from "node:crypto";
import type { IPty } from "node-pty";
import type { SessionInfo } from "./types.js";
import { ShadowTerminal } from "./shadowTerminal.js";

export interface PtySize {
  cols: number;
  rows: number;
}

export interface BuddySession {
  info: SessionInfo;
  pty: IPty;
  /** Raw PTY output subscribers (control API / WS bridge attach here later). */
  onData: (listener: (chunk: string) => void) => () => void;
  /** Fires whenever the real PC terminal (and thus the PTY) is resized, so
   * the phone's mirror can be kept the same size — mismatched dimensions
   * cause the source's absolute cursor positioning to garble on a
   * differently-sized terminal. */
  onResize: (listener: (size: PtySize) => void) => () => void;
  /** Full transcript captured so far (see ShadowTerminal) — sent once to a
   * newly attaching phone so it starts with complete history. */
  transcript: string[];
  /** Fires with newly-appended transcript lines as they're captured. */
  onTranscriptAppend: (listener: (lines: string[]) => void) => () => void;
  /** The current active screen (prompt + status, still redrawable in
   * place) — a fixed footer on the phone, not appended to history. */
  tail: string[];
  /** Fires whenever the tail changes (wholesale replace, not append). */
  onTailUpdate: (listener: (lines: string[]) => void) => () => void;
}

export interface StartSessionOptions {
  cwd: string;
  controlPort: number;
  claudeArgs: string[];
}

const shellForPlatform = () =>
  process.platform === "win32" ? "claude.cmd" : "claude";

/**
 * Spawns `claude` inside a PTY and wires the *real* process stdio to it, so
 * `buddy start` is visually indistinguishable from running `claude` directly.
 */
export function startSession(opts: StartSessionOptions): BuddySession {
  const id = randomUUID();
  const dataListeners = new Set<(chunk: string) => void>();
  const resizeListeners = new Set<(size: PtySize) => void>();
  const transcriptListeners = new Set<(lines: string[]) => void>();
  const transcript: string[] = [];
  const tailListeners = new Set<(lines: string[]) => void>();
  let tail: string[] = [];

  const ptyProcess = pty.spawn(shellForPlatform(), opts.claudeArgs, {
    name: "xterm-256color",
    cols: process.stdout.columns || 80,
    rows: process.stdout.rows || 24,
    cwd: opts.cwd,
    env: {
      ...process.env,
      BUDDY_DAEMON_URL: `http://127.0.0.1:${opts.controlPort}`,
      BUDDY_SESSION_ID: id,
    },
  });

  // Real terminal -> PTY (raw mode so keystrokes, including control chars,
  // pass through untouched).
  if (process.stdin.isTTY) {
    process.stdin.setRawMode(true);
  }
  process.stdin.resume();
  process.stdin.setEncoding("utf8");
  const onStdin = (chunk: string) => ptyProcess.write(chunk);
  process.stdin.on("data", onStdin);

  const shadow = new ShadowTerminal(
    process.stdout.columns || 80,
    process.stdout.rows || 24,
    (lines) => {
      transcript.push(...lines);
      for (const listener of transcriptListeners) listener(lines);
    },
    (lines) => {
      tail = lines;
      for (const listener of tailListeners) listener(lines);
    }
  );

  // PTY -> real terminal, and fan out to any attached listeners (WS bridge).
  ptyProcess.onData((chunk) => {
    process.stdout.write(chunk);
    shadow.write(chunk);
    for (const listener of dataListeners) listener(chunk);
  });

  const onResize = () => {
    if (process.stdout.columns && process.stdout.rows) {
      ptyProcess.resize(process.stdout.columns, process.stdout.rows);
      shadow.resize(process.stdout.columns, process.stdout.rows);
      const size = { cols: process.stdout.columns, rows: process.stdout.rows };
      for (const listener of resizeListeners) listener(size);
    }
  };
  process.stdout.on("resize", onResize);

  const cleanup = () => {
    process.stdin.off("data", onStdin);
    process.stdout.off("resize", onResize);
    if (process.stdin.isTTY) {
      process.stdin.setRawMode(false);
    }
  };

  ptyProcess.onExit(({ exitCode }) => {
    cleanup();
    process.exit(exitCode);
  });

  return {
    info: {
      id,
      cwd: opts.cwd,
      controlPort: opts.controlPort,
      startedAt: new Date().toISOString(),
      peers: [],
    },
    pty: ptyProcess,
    onData: (listener) => {
      dataListeners.add(listener);
      return () => dataListeners.delete(listener);
    },
    onResize: (listener) => {
      resizeListeners.add(listener);
      return () => resizeListeners.delete(listener);
    },
    transcript,
    onTranscriptAppend: (listener) => {
      transcriptListeners.add(listener);
      return () => transcriptListeners.delete(listener);
    },
    // Getter, not a plain property: `tail` is reassigned (not mutated in
    // place) on every update, so a plain property would freeze on
    // whatever it was at attach time.
    get tail() {
      return tail;
    },
    onTailUpdate: (listener) => {
      tailListeners.add(listener);
      return () => tailListeners.delete(listener);
    },
  };
}
