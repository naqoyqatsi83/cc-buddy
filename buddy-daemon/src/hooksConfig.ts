import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

interface HttpHook {
  type: "http";
  url: string;
}

interface HookMatcherEntry {
  matcher: string;
  hooks: HttpHook[];
}

/**
 * Writes (merging, non-destructively) the three HTTP hooks from the spec's
 * Component 2 into `.claude/settings.local.json` in the session's cwd, so
 * this specific `claude` process POSTs Notification/Stop/PreToolUse events
 * straight to this session's control API — no shell script, no manual
 * setup. `settings.local.json` (not `settings.json`) because the control
 * port is different every `buddy start`, so this is machine/session-local
 * config, not something to commit.
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
  upsertHook(settings.hooks, "Notification", "*", `${base}/hook/notification`);
  upsertHook(settings.hooks, "Stop", "*", `${base}/hook/stop`);
  upsertHook(settings.hooks, "PreToolUse", "Edit|Write", `${base}/hook/pretooluse`);

  mkdirSync(claudeDir, { recursive: true });
  writeFileSync(settingsPath, JSON.stringify(settings, null, 2) + "\n");
}

/**
 * Replaces any hook entry this daemon previously installed for the same
 * event (matched by URL path, ignoring the port, which changes every run)
 * instead of piling up a duplicate on every `buddy start`. Hook entries
 * for other events/matchers the user configured themselves are untouched.
 */
function upsertHook(hooks: Record<string, HookMatcherEntry[]>, event: string, matcher: string, url: string) {
  const path = new URL(url).pathname;
  const list = hooks[event] ?? [];
  const kept = list.filter((entry) => !entry.hooks?.some((h) => h.url && samePath(h.url, path)));
  kept.push({ matcher, hooks: [{ type: "http", url }] });
  hooks[event] = kept;
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
 * shuts down -- otherwise they keep pointing at this run's now-dead
 * control port, and every Notification/Stop/PreToolUse event fails with a
 * visible "ECONNREFUSED" until the next `buddy start` overwrites them.
 * Matches by URL path only (ignoring host/port, which change every run),
 * same as upsertHook, so it only ever touches entries this daemon itself
 * installed -- hooks the user configured for other events are untouched.
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
      (entry) => !entry.hooks?.some((h) => h.url && INSTALLED_HOOK_PATHS.some((p) => samePath(h.url, p)))
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
