import { describe, it, expect, beforeEach, afterEach } from "vitest";
import { mkdtempSync, rmSync, readFileSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { installHooks, uninstallHooks } from "../hooksConfig.js";

function readSettings(cwd: string): any {
  return JSON.parse(readFileSync(join(cwd, ".claude", "settings.local.json"), "utf8"));
}

describe("hooksConfig", () => {
  let cwd: string;

  beforeEach(() => {
    cwd = mkdtempSync(join(tmpdir(), "cc-buddy-hooks-test-"));
  });

  afterEach(() => {
    rmSync(cwd, { recursive: true, force: true });
  });

  it("creates .claude/settings.local.json with the three hooks when none exists", () => {
    installHooks(cwd, 12345);
    const settings = readSettings(cwd);

    expect(Object.keys(settings.hooks).sort()).toEqual(["Notification", "PreToolUse", "Stop"]);
    expect(settings.hooks.Notification[0].hooks[0].command).toContain(
      "http://127.0.0.1:12345/hook/notification"
    );
    expect(settings.hooks.PreToolUse[0].matcher).toBe("Edit|Write");
  });

  it("uses type: command with curl, not the native http type", () => {
    installHooks(cwd, 12345);
    const settings = readSettings(cwd);

    expect(settings.hooks.Stop[0].hooks[0].type).toBe("command");
    expect(settings.hooks.Stop[0].hooks[0].command).toMatch(/^curl /);
    expect(settings.hooks.Stop[0].hooks[0].command).toContain("; exit 0");
  });

  it("is idempotent -- re-running installHooks doesn't duplicate entries", () => {
    installHooks(cwd, 12345);
    installHooks(cwd, 12345);
    const settings = readSettings(cwd);

    expect(settings.hooks.Notification).toHaveLength(1);
    expect(settings.hooks.Stop).toHaveLength(1);
    expect(settings.hooks.PreToolUse).toHaveLength(1);
  });

  it("updates the port when re-installed with a different control port", () => {
    installHooks(cwd, 11111);
    installHooks(cwd, 22222);
    const settings = readSettings(cwd);

    expect(settings.hooks.Notification[0].hooks[0].command).toContain(":22222/hook/notification");
    expect(settings.hooks.Notification[0].hooks[0].command).not.toContain(":11111");
  });

  it("upgrades a pre-existing native http hook entry to the command form", () => {
    mkdirSync(join(cwd, ".claude"), { recursive: true });
    writeFileSync(
      join(cwd, ".claude", "settings.local.json"),
      JSON.stringify({
        hooks: {
          Notification: [
            {
              matcher: "*",
              hooks: [{ type: "http", url: "http://127.0.0.1:9999/hook/notification" }],
            },
          ],
        },
      })
    );

    installHooks(cwd, 12345);
    const settings = readSettings(cwd);

    expect(settings.hooks.Notification).toHaveLength(1);
    expect(settings.hooks.Notification[0].hooks[0].type).toBe("command");
    expect(settings.hooks.Notification[0].hooks[0].command).toContain(":12345");
  });

  it("preserves hook entries for events/matchers the user configured themselves", () => {
    mkdirSync(join(cwd, ".claude"), { recursive: true });
    writeFileSync(
      join(cwd, ".claude", "settings.local.json"),
      JSON.stringify({
        hooks: {
          Notification: [
            { matcher: "*", hooks: [{ type: "command", command: "echo user-owned-hook" }] },
          ],
        },
      })
    );

    installHooks(cwd, 12345);
    const settings = readSettings(cwd);

    // Both the user's own hook and this daemon's new one should survive,
    // as separate matcher entries -- installHooks only replaces entries it
    // recognizes as its own past install (matched by hookPath).
    const commands = settings.hooks.Notification.flatMap((entry: any) =>
      entry.hooks.map((h: any) => h.command)
    );
    expect(commands).toContain("echo user-owned-hook");
    expect(commands.some((c: string) => c.includes("/hook/notification"))).toBe(true);
  });

  it("preserves unrelated top-level settings", () => {
    mkdirSync(join(cwd, ".claude"), { recursive: true });
    writeFileSync(
      join(cwd, ".claude", "settings.local.json"),
      JSON.stringify({ someOtherSetting: "keep-me" })
    );

    installHooks(cwd, 12345);
    const settings = readSettings(cwd);

    expect(settings.someOtherSetting).toBe("keep-me");
  });

  it("recovers from a corrupt settings.local.json instead of throwing", () => {
    mkdirSync(join(cwd, ".claude"), { recursive: true });
    writeFileSync(join(cwd, ".claude", "settings.local.json"), "{ not valid json");

    expect(() => installHooks(cwd, 12345)).not.toThrow();
    const settings = readSettings(cwd);
    expect(settings.hooks.Notification).toHaveLength(1);
  });

  it("uninstallHooks removes exactly the hooks this daemon installed", () => {
    installHooks(cwd, 12345);
    uninstallHooks(cwd);
    const settings = readSettings(cwd);

    expect(settings.hooks).toBeUndefined();
  });

  it("uninstallHooks leaves user-owned hooks for the same event intact", () => {
    installHooks(cwd, 12345);
    const settings = readSettings(cwd);
    settings.hooks.Notification.push({
      matcher: "*",
      hooks: [{ type: "command", command: "echo user-owned-hook" }],
    });
    writeFileSync(join(cwd, ".claude", "settings.local.json"), JSON.stringify(settings));

    uninstallHooks(cwd);
    const after = readSettings(cwd);

    expect(after.hooks.Notification).toHaveLength(1);
    expect(after.hooks.Notification[0].hooks[0].command).toBe("echo user-owned-hook");
    // The daemon's own hooks (Stop, PreToolUse) had no user-owned entries,
    // so those events should be removed entirely, not left as empty lists.
    expect(after.hooks.Stop).toBeUndefined();
    expect(after.hooks.PreToolUse).toBeUndefined();
  });

  it("uninstallHooks is a no-op when settings.local.json doesn't exist", () => {
    expect(() => uninstallHooks(cwd)).not.toThrow();
  });
});
