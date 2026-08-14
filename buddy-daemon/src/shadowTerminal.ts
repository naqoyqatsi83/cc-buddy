import pkg from "@xterm/headless";
import type { Terminal as TerminalType } from "@xterm/headless";
const { Terminal } = pkg;

/**
 * Claude Code's TUI renders into a fixed-size screen (like btop/k9s/
 * lazygit) and manages its own scroll/expand state inside the process —
 * it does not use the terminal's native scrollback, so there is no
 * "history buffer" a remote mirror can read directly. The PC and phone
 * were therefore sharing exactly one live, in-place-redrawing view.
 *
 * This runs a second, headless instance of the *same terminal emulator*
 * (xterm's own headless build) fed the identical PTY byte stream, purely
 * to get an accurate parse of the redraws (cursor moves, DECSTBM regions,
 * clears) — the same parsing a real terminal does.
 *
 * Two earlier versions of this got it wrong:
 * - Watching for genuine linefeed-based scrollback growth never fired
 *   against real Claude Code output — it turns out even its own internal
 *   scrolling redraws the same fixed rows via cursor addressing rather
 *   than emitting linefeeds.
 * - Periodic full-active-screen snapshots (debounced, with scrollback set
 *   to 0) worked for redraw-based changes, but silently discarded
 *   anything that scrolled off the active screen *during* a burst — with
 *   zero scrollback, once a line scrolls past row 0 of the active screen
 *   it's gone forever, and the debounce only captures whatever's left in
 *   the final settled state. Against a real, multi-step Claude Code
 *   response (several tool calls, longer output) this lost most of the
 *   actual content, capturing only the last couple of screens.
 *
 * This version gives the shadow terminal a large scrollback (so nothing
 * is ever discarded) and, on each debounced capture:
 *   1. treats any line that has now permanently scrolled off the active
 *      screen (buffer index below `length - rows`) as safe to append
 *      exactly once, verbatim — it can never change again;
 *   2. diffs the *current* active screen (the last `rows` lines, which
 *      can still be redrawn in place) against the last captured active
 *      screen, appending only the changed suffix — this is what catches
 *      a status line ticking without re-sending the whole screen.
 * A capture is also forced at least every 2s even under continuous
 * output, so a long unbroken burst doesn't delay bucket (1) indefinitely.
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

export class ShadowTerminal {
  private term: TerminalType;
  private capturedBoundary = 0; // buffer index up to which lines are already permanently captured
  private lastActiveLines: string[] = [];
  // The most recently appended block, whichever bucket it came from — some
  // TUIs (this one included, apparently) periodically re-render their
  // whole idle screen (e.g. a cycling hint or timer), which pushes new
  // lines into "permanently scrolled off" territory even though the
  // content is just a repeat. Comparing against only the immediately
  // preceding block is a cheap, good-enough guard against that without
  // trying to detect a general idle-refresh pattern.
  private lastAppendedBlock: string[] = [];
  // Also tracked separately: a block that's entirely repeats of this one
  // line is suppressed too — catches the transition into a steady idle
  // refresh, where each capture's *new suffix* is a single line that
  // matches the previous single-line append, even though lastAppendedBlock
  // itself was a larger, differently-shaped block at that point.
  private lastAppendedLine: string | null = null;
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;
  private maxWaitTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    cols: number,
    rows: number,
    private onAppend: (lines: string[]) => void
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
    const total = buf.length;
    const rows = this.term.rows;
    const permanentBoundary = Math.max(0, total - rows);
    const appended: string[] = [];

    // 1) Lines that have permanently scrolled off the active screen since
    // the last capture — safe to append exactly once, verbatim, unless
    // it's an exact repeat of the block we just appended (idle refresh).
    if (permanentBoundary > this.capturedBoundary) {
      const block: string[] = [];
      for (let i = this.capturedBoundary; i < permanentBoundary; i++) {
        const line = buf.getLine(i);
        block.push(line ? line.translateToString(true) : "");
      }
      this.capturedBoundary = permanentBoundary;
      if (!sameLines(block, this.lastAppendedBlock) && !isPureRepeatOf(block, this.lastAppendedLine)) {
        appended.push(...block);
        this.lastAppendedBlock = block;
        this.lastAppendedLine = block[block.length - 1];
      }
    }

    // 2) The current active screen — still redrawable in place — diffed
    // against the last capture so only the changed suffix is appended.
    const activeLines: string[] = [];
    for (let i = permanentBoundary; i < total; i++) {
      const line = buf.getLine(i);
      activeLines.push(line ? line.translateToString(true) : "");
    }
    while (activeLines.length > 0 && activeLines[activeLines.length - 1] === "") activeLines.pop();

    let firstDiff = 0;
    const minLen = Math.min(activeLines.length, this.lastActiveLines.length);
    while (firstDiff < minLen && activeLines[firstDiff] === this.lastActiveLines[firstDiff]) firstDiff++;
    const activeChanged = !(firstDiff === activeLines.length && activeLines.length === this.lastActiveLines.length);
    this.lastActiveLines = activeLines;
    if (activeChanged) {
      const block = activeLines.slice(firstDiff);
      if (!sameLines(block, this.lastAppendedBlock) && !isPureRepeatOf(block, this.lastAppendedLine)) {
        appended.push(...block);
        this.lastAppendedBlock = block;
        this.lastAppendedLine = block[block.length - 1];
      }
    }

    if (appended.length > 0) this.onAppend(appended);
  }
}
