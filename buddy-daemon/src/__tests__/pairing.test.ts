import { describe, it, expect, vi } from "vitest";
import * as os from "node:os";
import * as path from "node:path";
import { hostDeviceName } from "../pairing.js";

describe("hostDeviceName", () => {
  it("resolves a relative cwd instead of literally basename-ing it", () => {
    // Regression test for #21: `buddy start --cwd .` passed the literal
    // string "." straight to path.basename(), producing a session named
    // "user@host:." instead of the actual directory name.
    const name = hostDeviceName(".");
    expect(name).not.toMatch(/:\.$/);
    expect(name).toContain(`:${path.basename(process.cwd())}`);
  });

  it("resolves a relative parent-dir cwd (..) the same way", () => {
    const name = hostDeviceName("..");
    expect(name).not.toMatch(/:\.\.$/);
    expect(name).toContain(`:${path.basename(path.resolve(".."))}`);
  });

  it("uses the basename of an already-absolute cwd unchanged", () => {
    const absolute = path.resolve("/some/project/cc-buddy");
    expect(hostDeviceName(absolute)).toContain(":cc-buddy");
  });

  it("includes the OS username and hostname", () => {
    const name = hostDeviceName(process.cwd());
    expect(name).toContain(os.userInfo().username || "user");
    expect(name.split("@")[1].split(":")[0]).toBe(
      (process.env.COMPUTERNAME || os.hostname() || "PC").split(".")[0]
    );
  });
});
