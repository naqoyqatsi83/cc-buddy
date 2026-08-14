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
- `android/` — CC Buddy Android app (Kotlin + Jetpack Compose). Foreground
  service, embedded Ktor WS server on port 8765, PIN pairing handshake,
  paired-session list, and a live xterm.js terminal mirror with reply
  injection.

## Status

Build order (from the spec) so far:

- [x] 1. `buddy-daemon` skeleton — `buddy start` spawns `claude` in a PTY
      and transparently proxies the real terminal.
- [x] 2. Localhost control API (`/sessions`, `/scan`, `/pair`, `/peers`,
      `/unpair`, `/hook/*`) + the four `/buddy-*` commands.
- [x] 3. Android app MVP (foreground service + embedded Ktor WS server +
      PIN pairing screen + accept/deny prompt + paired-session list).
- [x] 4. PTY streaming: daemon forwards raw PTY output over the pairing
      WS as `pty_data` frames; phone renders it with a bundled xterm.js
      in a WebView.
- [x] 5. Reply injection: phone sends `input` frames (quick-reply buttons
      or the text field's Send button), daemon writes them into the PTY
      as `text + "\r"`.
- [x] 6. Claude Code HTTP hooks wired to push notifications —
      `buddy start` writes Notification/Stop/PreToolUse HTTP hooks into
      `.claude/settings.local.json` pointing at the session's control
      port; the daemon forwards them to paired phone(s), which shows a
      real high-importance Android notification ("Claude needs you") on
      Notification and clears it on Stop. Verified end-to-end on the
      emulator with real hook POSTs.
- [x] 7. mDNS discovery both sides — Android advertises `_buddycc._tcp`
      via `NsdManager`; daemon browses for it via `bonjour-service`.
      Verified working discovery in both directions (Android → host and
      standalone host round-trip) when nothing else on the host
      contends for UDP 5353. Note: on a machine already running
      `avahi-daemon` and/or `adb`'s own mDNS listener (both bind port
      5353), the kernel delivers each multicast response to only one of
      the competing sockets, so `/buddy-scan` can miss it non-
      deterministically — a host-environment quirk, not a bug in this
      code; manual `/buddy-pair <ip> <pin>` is unaffected either way.
- [x] 8. Tailscale — pairing itself needed no special code (the daemon's
      WS client just dials whatever `ip:port` it's given, same code path
      regardless of address range). What did need fixing: the phone was
      only labeling an address "Tailscale" if the network interface name
      literally contained "tailscale", which Android's Tailscale app
      (a generic VpnService `tun*` interface) never does — so the IP
      just silently showed up unlabeled, or not at all if mDNS also
      couldn't reach it (mDNS doesn't route over the VPN). Now detected
      by the 100.64.0.0/10 CGNAT range Tailscale actually assigns from,
      regardless of interface name. Verified on the emulator by
      attaching a real 100.x address to an unrelated dummy interface
      (`dummy0`) via `adb root` + `ip addr add` — the app correctly
      labeled and displayed it.
- Phase 2 (not part of the numbered build order):
  - [x] Encrypted token storage hardening — PC-side pairing tokens now
        live in the OS keychain via `keytar` instead of a plaintext
        file (Android already used `EncryptedSharedPreferences`).
        Verified: token appears in the OS keychain, not in
        `~/.buddy/peers.json`; unpairing removes it from both.
  - [x] Multi-session UI — one phone pairing with several PC sessions
        at once now mirrors and controls each independently instead of
        the second connection silently stealing the first's input
        routing and mixing its output into the same stream. Verified
        two simultaneous fake daemon sessions on the emulator: switching
        between them shows each one's correct isolated backlog, and a
        reply typed for session B only reaches session B's PTY. This
        testing round caught two real bugs beyond the core feature: (1)
        the terminal's WebView signaled "ready" immediately after
        `loadUrl()`, before the page's JS had actually finished loading,
        so replayed output silently failed to render on every session
        switch (fixed: wait for `onPageFinished`); (2) a second PC
        session connecting while the user had deliberately returned to
        the session list would auto-navigate them into a session they'd
        already seen instead of just updating the list quietly (fixed:
        auto-open only the very first bridge of the app's lifetime).
  - [ ] FCM for notifications when the app's fully backgrounded/killed —
        needs a Firebase project and a small cloud relay service, both
        requiring external account setup this environment can't do
        autonomously. Not started.

## Try it (daemon only, no phone yet)

```
cd buddy-daemon
npm install
npm run build
node dist/cli.js start --cwd /path/to/your/project
```

This should behave exactly like running `claude` directly. Once a
`BUDDY_DAEMON_URL`/`BUDDY_SESSION_ID`-aware session is running, the
`/buddy-*` commands work from inside it — `/buddy-scan` browses for
phones advertising `_buddycc._tcp` (see the mDNS caveat above), and
`/buddy-pair <ip> <pin>` always works against a real phone running the
Android app below regardless of scan reliability.

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
