# CC Buddy

Pair a Claude Code terminal session with an Android phone: live mirror,
push notifications when Claude is waiting on input, and reply injection
from the phone. See `buddy-cc-android-pairing-spec.md` for the full design.

## Layout

- `buddy-daemon/` — Node.js/TypeScript daemon. `buddy start` wraps `claude`
  in a PTY (via `node-pty`) and exposes a localhost-only control API used
  by the plugin commands and by Claude Code's HTTP hooks.
- `buddy-plugin/` — Claude Code plugin providing `/buddy-scan`,
  `/buddy-pair`, `/buddy-list`, `/buddy-unpair`. Thin wrappers over the
  daemon's control API via `BUDDY_DAEMON_URL` / `BUDDY_SESSION_ID`.
- `android/` — CC Buddy Android app (Kotlin + Jetpack Compose). MVP:
  foreground service, embedded Ktor WS server on port 8765, PIN pairing
  handshake, paired-session list. No terminal mirror yet.

## Status

Build order (from the spec) so far:

- [x] 1. `buddy-daemon` skeleton — `buddy start` spawns `claude` in a PTY
      and transparently proxies the real terminal.
- [x] 2. Localhost control API (`/sessions`, `/scan`, `/pair`, `/peers`,
      `/unpair`, `/hook/*`) + the four `/buddy-*` commands.
- [x] 3. Android app MVP (foreground service + embedded Ktor WS server +
      PIN pairing screen + accept/deny prompt + paired-session list). No
      terminal mirror yet — that's step 4.
- [ ] 4. PTY streaming + xterm.js mirror on the phone.
- [ ] 5. Reply injection (phone → daemon → `pty.write()`).
- [ ] 6. Claude Code HTTP hooks wired to push notifications.
- [ ] 7. mDNS discovery both sides.
- [ ] 8. Tailscale (should work for free once IP pairing works).
- [ ] 9. Phase 2: FCM, encrypted token storage hardening, multi-session UI.

## Try it (daemon only, no phone yet)

```
cd buddy-daemon
npm install
npm run build
node dist/cli.js start --cwd /path/to/your/project
```

This should behave exactly like running `claude` directly. Once a
`BUDDY_DAEMON_URL`/`BUDDY_SESSION_ID`-aware session is running, the
`/buddy-*` commands work from inside it (`/buddy-scan` still reports no
phones found — mDNS is step 7 — but `/buddy-pair <ip> <pin>` works against
a real phone running the Android app below).

## Try it (Android app)

```
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open the app, grant the notification permission, and it'll show a 6-digit
PIN plus this phone's local IPs (Wi-Fi / Tailscale). From a `buddy start`
session on the PC (same LAN or same Tailscale network), run
`/buddy-pair <ip> <pin>` — a pairing request will pop up on the phone to
accept or deny. Building requires the Android SDK (platform 34,
build-tools 34.0.0); `android/local.properties` (gitignored) points at it.
