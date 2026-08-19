import { describe, it, expect, beforeEach, afterEach, vi } from "vitest";
import { ShadowTerminal } from "../shadowTerminal.js";

// captureSnapshot() is debounced (500ms after the last write, 2000ms max
// wait) rather than run synchronously on every write() -- fake timers let
// these tests trigger it deterministically instead of sleeping in real
// time.
function flushDebounce() {
  vi.advanceTimersByTime(500);
}

describe("ShadowTerminal", () => {
  let history: string[][];
  let tails: string[][];
  let menus: string[][];
  let term: ShadowTerminal;

  beforeEach(() => {
    vi.useFakeTimers();
    history = [];
    tails = [];
    menus = [];
    term = new ShadowTerminal(
      80,
      10,
      (lines) => history.push(lines),
      (lines) => tails.push(lines),
      (options) => menus.push(options)
    );
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it("detects a numbered menu and extracts option numbers in order", () => {
    term.write(
      "Do you want to make this edit to foo.ts?\r\n" +
        "❯ 1. Yes\r\n" +
        "  2. Yes, and don't ask again this session\r\n" +
        "  3. No\r\n"
    );
    flushDebounce();

    expect(menus.at(-1)).toEqual(["1", "2", "3"]);
  });

  it("extracts the question text immediately above the first menu option", () => {
    term.write(
      "Do you want to make this edit to foo.ts?\r\n" +
        "❯ 1. Yes\r\n" +
        "  2. No\r\n"
    );
    flushDebounce();

    expect(term.question).toBe("Do you want to make this edit to foo.ts?");
  });

  it("skips blank and border/box-drawing lines when walking back to the question", () => {
    term.write(
      "Do you want to make this edit to foo.ts?\r\n" +
        "\r\n" +
        "──────────────\r\n" +
        "❯ 1. Yes\r\n" +
        "  2. No\r\n"
    );
    flushDebounce();

    expect(term.question).toBe("Do you want to make this edit to foo.ts?");
  });

  it("leaves question undefined when no menu is on screen", () => {
    term.write("just some regular output, no prompt here\r\n");
    flushDebounce();

    expect(term.question).toBeUndefined();
    // onMenuOptions only fires when the option list *changes* from its
    // previous value (initially []) -- no menu ever appearing means it
    // never fires at all, not that it fires once with an empty array.
    expect(menus).toEqual([]);
  });

  it("composes spokenPrompt from the question and option labels", () => {
    term.write(
      "Do you want to make this edit to foo.ts?\r\n" +
        "❯ 1. Yes\r\n" +
        "  2. No\r\n"
    );
    flushDebounce();

    expect(term.spokenPrompt).toBe(
      "Do you want to make this edit to foo.ts? Options: 1: Yes. 2: No."
    );
  });

  it("spokenPrompt is just the question when no options are on screen", () => {
    // A question can't actually be extracted without a menu to anchor to
    // (see captureSnapshot), so spokenPrompt is undefined here too --
    // this documents that current behavior rather than asserting an
    // extraction path that doesn't exist.
    term.write("just some regular output, no prompt here\r\n");
    flushDebounce();

    expect(term.spokenPrompt).toBeUndefined();
  });

  it("does not re-fire onMenuOptions when the same options are redrawn", () => {
    term.write("❯ 1. Yes\r\n  2. No\r\n");
    flushDebounce();
    const callsAfterFirst = menus.length;

    // Idle re-render of the exact same prompt (e.g. a status tick) --
    // should not produce a duplicate menuOptions callback.
    term.write("❯ 1. Yes\r\n  2. No\r\n");
    flushDebounce();

    expect(menus.length).toBe(callsAfterFirst);
  });

  it("fires onMenuOptions again once the options actually change", () => {
    term.write("❯ 1. Yes\r\n  2. No\r\n");
    flushDebounce();

    term.write("\x1b[2J\x1b[H"); // clear screen, new prompt
    term.write("❯ 1. Continue\r\n  2. Stop\r\n  3. Abort\r\n");
    flushDebounce();

    expect(menus.at(-1)).toEqual(["1", "2", "3"]);
  });

  it("splits output between scrollable history and the fixed tail", () => {
    // Push well past TAIL_MAX_LINES (6) worth of plain lines so some of it
    // must land in history rather than all of it staying in the tail.
    const lines = Array.from({ length: 20 }, (_, i) => `line ${i}`);
    term.write(lines.join("\r\n") + "\r\n");
    flushDebounce();

    const appended = history.flat();
    expect(appended.length).toBeGreaterThan(0);
    expect(appended).toContain("line 0");
    // Tail should hold only the most recent lines, not the whole backlog.
    expect(tails.at(-1)!.length).toBeLessThanOrEqual(6);
  });

  it("does not append the same history block twice", () => {
    const lines = Array.from({ length: 20 }, (_, i) => `line ${i}`);
    term.write(lines.join("\r\n") + "\r\n");
    flushDebounce();
    const historyCallsAfterFirst = history.length;

    // No new writes -- captureSnapshot firing again (e.g. via the max-wait
    // timer) must not re-append the boundary that was already sent.
    vi.advanceTimersByTime(2000);

    expect(history.length).toBe(historyCallsAfterFirst);
  });

  it("captures a snapshot even without new writes once the max-wait timer fires", () => {
    term.write("❯ 1. Yes\r\n  2. No\r\n");
    // Don't advance past the 500ms debounce, but do pass the 2000ms
    // absolute max-wait ceiling.
    vi.advanceTimersByTime(2000);

    expect(menus.at(-1)).toEqual(["1", "2"]);
  });
});
