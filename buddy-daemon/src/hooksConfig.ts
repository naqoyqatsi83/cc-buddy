import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

interface CommandHook {
  type: "command";
  command: string;
}

interface HookMatcherEntry {
  matcher: string;
  hooks: Array<CommandHook | { type: string; url?: string; command?: string }>;
}

/**
 * Writes (merging, non-destructively) the three Notification/Stop/
 * PreToolUse hooks from the spec's Component 2 into
 * `.claude/settings.local.json` in the session's cwd, so this specific
 * `claude` process forwards those events straight to this session's
 * control API — no shell script, no manual setup. `settings.local.json`
 * (not `settings.json`) because the control port is different every
 * `buddy start`, so this is machine/session-local config, not something
 * to commit.
 *
 * Uses `type: "command"` (curl piping stdin to the control API, `; exit 0`
 * to always succeed) rather than Claude Code's native `type: "http"` --
 * the latter has no way to suppress a connection error, so once `buddy`
 * stops (including a hard crash/reboot that skips uninstallHooks()'s
 * normal cleanup-on-exit), every hook fire surfaced a visible
 * "Stop hook error: connect ECONNREFUSED ..." until the next `buddy start`
 * overwrote the file. A command that swallows its own failure is
 * resilient to that gap by construction, instead of depending on cleanup
 * always getting a chance to run.
 */
export function installHooks(cwd: string, controlPort: number) {
  const claudeDir = join(cwd, ".claude");
  const settingsPath = join(claudeDir, "settings.local.json");

  let settings: any = {};
  if (existsSync(settingsPath)) {
    try {
      settings = JSON.parse(readFileSync(settingsPath, "utf8"));
    } catch {
      settings = {};
    }
  }
  settings.hooks ??= {};

  const base = `http://127.0.0.1:${controlPort}`;
  upsertHook(settings.hooks, "Notification", "*", "/hook/notification", `${base}/hook/notification`);
  upsertHook(settings.hooks, "Stop", "*", "/hook/stop", `${base}/hook/stop`);
  upsertHook(settings.hooks, "PreToolUse", "Edit|Write", "/hook/pretooluse", `${base}/hook/pretooluse`);

  mkdirSync(claudeDir, { recursive: true });
  writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + "\n");
}

/** curl piping stdin (the hook's JSON payload) to `url` as the POST body,
 * silently discarding output and forcing exit 0 regardless of outcome --
 * a refused/timed-out connection (daemon not running) is then just a
 * no-op, never a visible error. `-m 2` bounds how long a hung connection
 * can hold up the hook. Windows' bundled curl.exe understands the same
 * flags; `&` (unconditional next-command) is cmd's equivalent of `;`. */
function curlCommand(url: string): string {
  return process.platform === "win32"
    ? `curl -s -m 2 -X POST --data-binary @- "${url}" >nul 2>&1 & exit /b 0`
    : `curl -s -m 2 -X POST --data-binary @- "${url}" >/dev/null 2>&1; exit 0`;
}

/**
 * Replaces any hook entry this daemon previously installed for the same
 * event (matched by the fixed `/hook/...` path embedded in either an old
 * `type: "http"` entry's `url` or a `type: "command"` entry's `command`
 * string) instead of piling up a duplicate on every `buddy start` -- also
 * what upgrades a pre-existing plain-`http`-hook install cleanly to the
 * command form instead of leaving both. Hook entries for other events/
 * matchers the user configured themselves are untouched.
 */
function upsertHook(
  hooks: Record<string, HookMatcherEntry[]>,
  event: string,
  matcher: string,
  hookPath: string,
  url: string
) {
  const list = hooks[event] ?? [];
  const kept = list.filter((entry) => !entry.hooks?.some((h) => matchesInstalledHook(h, hookPath)));
  kept.push({ matcher, hooks: [{ type: "command", command: curlCommand(url) }] });
  hooks[event] = kept;
}

function matchesInstalledHook(h: { url?: string; command?: string }, hookPath: string): boolean {
  if (h.url) return samePath(h.url, hookPath);
  if (h.command) return h.command.includes(hookPath);
  return false;
}

function samePath(url: string, path: string): boolean {
  try {
    return new URL(url).pathname === path;
  } catch {
    return false;
  }
}

const INSTALLED_HOOK_PATHS = ["/hook/notification", "/hook/stop", "/hook/pretooluse"];

/**
 * Removes the hook entries installHooks() wrote, called when the daemon
 * shuts down gracefully. Kept as a courtesy for a clean exit (Ctrl+C,
 * normal exit, SIGTERM) even though installHooks() no longer depends on
 * it for safety -- a hard crash/reboot skips this, and that's fine now
 * that the installed hooks fail silently on their own.
 */
export function uninstallHooks(cwd: string) {
  const settingsPath = join(join(cwd, ".claude"), "settings.local.json");
  if (!existsSync(settingsPath)) return;

  let settings: any;
  try {
    settings = JSON.parse(readFileSync(settingsPath, "utf8"));
  } catch {
    return;
  }
  if (!settings.hooks) return;

  for (const event of Object.keys(settings.hooks)) {
    const kept = (settings.hooks[event] as HookMatcherEntry[]).filter(
      (entry) => !entry.hooks?.some((h) => INSTALLED_HOOK_PATHS.some((p) => matchesInstalledHook(h, p)))
    );
    if (kept.length > 0) {
      settings.hooks[event] = kept;
    } else {
      delete settings.hooks[event];
    }
  }
  if (Object.keys(settings.hooks).length === 0) delete settings.hooks;

  writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + "\n");
}
