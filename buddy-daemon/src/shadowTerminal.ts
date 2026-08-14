import pkg from "@xterm/headless";
import type { Terminal as TerminalType } from "@xterm/headless";
const { Terminal } = pkg;

/**
 * Claude Code's TUI renders into a fixed-size screen (like btop/k9s/
 * lazygit) and manages its own scroll/expand state inside the process —
 * it does not use the terminal's native scrollback, so there is no
 * "history buffer" a remote mirror can read directly.
 *
 * This runs a second, headless instance of the *same terminal emulator*
 * (xterm's own headless build) fed the identical PTY byte stream, purely
 * to get an accurate parse of the redraws (cursor moves, DECSTBM regions,
 * clears) — the same parsing a real terminal does.
 *
 * The phone wants two genuinely different things, not one merged stream:
 *   - a scrollable history of everything ever shown, that scrolling
 *     never disturbs;
 *   - a small fixed footer (prompt + status) that's always visible at the
 *     bottom, updates in place, and does NOT scroll away with the rest.
 * Those map directly onto a real distinction in the underlying terminal
 * state: lines that have permanently scrolled off the active screen (can
 * never change again — safe to append to history exactly once) versus
 * the current active screen itself (still redrawable in place — the
 * fixed footer). Two callbacks instead of one merged append stream.
 */
function sameLines(a: string[], b: string[]): boolean {
  if (a.length !== b.length) return false;
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false;
  return true;
}

function isPureRepeatOf(block: string[], line: string | null): boolean {
  if (line == null || block.length === 0) return false;
  return block.every((l) => l === line);
}

// The footer is meant to be just the prompt box + status line — a
// handful of rows — not "everything still within the PC's real terminal
// height". Tying the split to the PC's actual row count (as an earlier
// version did) meant a moderate/short session left almost everything in
// the footer (scrollable in a cramped strip) with history empty above it,
// since nothing had actually scrolled off an 86-row PC screen yet. A
// fixed small tail size instead means most content flows into scrollable
// history quickly, regardless of how tall the real PC terminal is.
const TAIL_MAX_LINES = 6;

export class ShadowTerminal {
  private term: TerminalType;
  private capturedBoundary = 0; // buffer index up to which history is already sent
  private lastAppendedBlock: string[] = [];
  // Some TUIs (this one included) periodically re-render their idle
  // status/hint area even with no real new activity, which pushes new
  // lines into "permanently scrolled off" territory even though it's
  // just a repeat. lastAppendedBlock alone only catches steady-state
  // repeats, not the first transition into one, so also track the single
  // most recent line and suppress a block that's entirely repeats of it.
  private lastAppendedLine: string | null = null;
  private lastTail: string[] = [];
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private maxWaitTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    cols: number,
    rows: number,
    private onHistoryAppend: (lines: string[]) => void,
    private onTailUpdate: (lines: string[]) => void
  ) {
    this.term = new Terminal({ cols, rows, scrollback: 100_000, allowProposedApi: true });
  }

  resize(cols: number, rows: number) {
    this.term.resize(cols, rows);
  }

  write(chunk: string) {
    this.term.write(chunk);
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    this.debounceTimer = setTimeout(() => this.captureSnapshot(), 500);
    if (!this.maxWaitTimer) {
      this.maxWaitTimer = setTimeout(() => this.captureSnapshot(), 2000);
    }
  }

  private captureSnapshot() {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer);
      this.debounceTimer = null;
    }
    if (this.maxWaitTimer) {
      clearTimeout(this.maxWaitTimer);
      this.maxWaitTimer = null;
    }

    const buf = this.term.buffer.active;
    // buf.length always reports at least `rows` (it pads out to the full
    // active-screen size even when most of it is blank), so find the
    // real last non-blank row first — otherwise a short/moderate session
    // pushes a wall of blank padding into both history and the tail.
    let effectiveTotal = buf.length;
    while (effectiveTotal > 0) {
      const line = buf.getLine(effectiveTotal - 1);
      if ((line ? line.translateToString(true) : "") !== "") break;
      effectiveTotal--;
    }
    const boundary = Math.max(this.capturedBoundary, effectiveTotal - TAIL_MAX_LINES);

    // History: everything up to the last TAIL_MAX_LINES rows — safe to
    // append exactly once, verbatim, unless it's an idle-refresh repeat.
    if (boundary > this.capturedBoundary) {
      const block: string[] = [];
      for (let i = this.capturedBoundary; i < boundary; i++) {
        const line = buf.getLine(i);
        block.push(line ? line.translateToString(true) : "");
      }
      this.capturedBoundary = boundary;
      if (!sameLines(block, this.lastAppendedBlock) && !isPureRepeatOf(block, this.lastAppendedLine)) {
        this.onHistoryAppend(block);
        this.lastAppendedBlock = block;
        this.lastAppendedLine = block[block.length - 1];
      }
    }

    // Tail: the last few rows — prompt + status, still redrawable in
    // place. This is a wholesale replace (the footer shows whatever it
    // is right now), not an append, so no dedup needed.
    const tail: string[] = [];
    for (let i = boundary; i < effectiveTotal; i++) {
      const line = buf.getLine(i);
      tail.push(line ? line.translateToString(true) : "");
    }
    if (!sameLines(tail, this.lastTail)) {
      this.onTailUpdate(tail);
      this.lastTail = tail;
    }
  }
}
