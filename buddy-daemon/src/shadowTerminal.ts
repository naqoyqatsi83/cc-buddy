import pkg from "@xterm/headless";
import type { Terminal as TerminalType } from "@xterm/headless";
const { Terminal } = pkg;

/**
 * Claude Code's TUI renders into a fixed-size screen (like btop/k9s/
 * lazygit) and manages its own scroll/expand state inside the process —
 * it does not use the terminal's native scrollback, so there is no
 * "history buffer" a remote mirror can read directly. The PC and phone
 * were therefore sharing exactly one live, in-place-redrawing view: the
 * phone could not scroll independently, and scrolling on the PC visibly
 * redrew the phone too.
 *
 * This runs a second, headless instance of the *same terminal emulator*
 * (xterm's own headless build) fed the identical PTY byte stream, purely
 * to get an accurate parse of the redraws (cursor moves, DECSTBM regions,
 * clears) — the same parsing a real terminal does.
 *
 * The first version of this tried to detect "history" by watching for
 * lines that scroll off the top via genuine linefeeds (xterm's own
 * scrollback growing). That never fired against the real Claude Code TUI:
 * it turns out even *its own* internal scrolling (paging through a long
 * tool result, for instance) redraws the same fixed rows via cursor
 * addressing rather than emitting linefeeds — so nothing ever "scrolled
 * off" in the sense the old code was watching for, and history stayed
 * permanently empty against real usage despite working fine in synthetic
 * tests built on plain `printf`.
 *
 * This version instead takes a periodic snapshot of the whole active
 * screen once output settles (debounced), and appends it to the
 * transcript whenever it differs from the last snapshot. That captures
 * every distinct screen the user was ever shown — regardless of whether
 * it arrived via linefeeds or via the app's own redraw-based paging —
 * which is what "independent, freely-scrollable history" actually needs
 * to mean here. Re-viewing already-captured content (e.g. paging back up
 * within the PC's own view) can re-append a duplicate block; that's an
 * acceptable trade-off for actually capturing real content at all.
 */
export class ShadowTerminal {
  private term: TerminalType;
  private lastLines: string[] = [];
  private debounceTimer: ReturnType<typeof setTimeout> | null = null;

  constructor(
    cols: number,
    rows: number,
    private onAppend: (lines: string[]) => void
  ) {
    this.term = new Terminal({ cols, rows, scrollback: 0, allowProposedApi: true });
  }

  resize(cols: number, rows: number) {
    this.term.resize(cols, rows);
  }

  write(chunk: string) {
    this.term.write(chunk);
    if (this.debounceTimer) clearTimeout(this.debounceTimer);
    this.debounceTimer = setTimeout(() => this.captureSnapshot(), 500);
  }

  private captureSnapshot() {
    this.debounceTimer = null;
    const buf = this.term.buffer.active;
    const lines: string[] = [];
    for (let i = 0; i < this.term.rows; i++) {
      const line = buf.getLine(i);
      lines.push(line ? line.translateToString(true) : "");
    }
    // Trim trailing blank rows (the screen usually isn't fully packed) so
    // near-empty frames don't get padded out with blank lines forever.
    while (lines.length > 0 && lines[lines.length - 1] === "") lines.pop();
    if (lines.length === 0) return;

    // Only append the lines that actually changed from the last capture —
    // find the first row that differs (e.g. everything above a status
    // line that just ticked its context percentage is identical) and only
    // send from there. Appending the *entire* screen on every capture
    // (the first version of this) buried History in near-duplicate
    // full-screen dumps every time something as small as a status line
    // changed, which is what made it confusing to read.
    let firstDiff = 0;
    const minLen = Math.min(lines.length, this.lastLines.length);
    while (firstDiff < minLen && lines[firstDiff] === this.lastLines[firstDiff]) firstDiff++;
    if (firstDiff === lines.length && lines.length === this.lastLines.length) {
      return; // identical to last capture
    }
    this.lastLines = lines;
    this.onAppend(lines.slice(firstDiff));
  }
}
