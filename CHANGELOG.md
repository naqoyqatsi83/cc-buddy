# Changelog

All notable changes to this project are documented here.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.3.0] - 2026-08-17

### Added
- The phone↔PC connection is now encrypted (`wss://` instead of plain
  `ws://`). The phone generates a self-signed TLS certificate on first run;
  the daemon trusts it on first pairing (same trust-on-first-use model as
  the existing PIN + explicit accept-tap flow) and pins its fingerprint for
  every reconnect afterward. A cert that no longer matches what was pinned
  is treated exactly like a rejected pairing token — the peer is dropped
  rather than silently trusted.
- A battery-optimization exemption prompt: a banner on the main screen
  (when not yet exempt) and a status row in Settings, both backed by the
  same OS-level check. "App settings" opens the app's own system Settings
  page directly rather than the generic battery-optimization list, which
  defaults to hiding already-exempted apps and varies a lot across OEM
  skins (Samsung's One UI among them).
- Dynamic quick-reply buttons: the daemon scans the PC's current on-screen
  viewport for a numbered menu (Claude Code's confirm/permission prompts,
  model picker, etc.) and sends the phone exactly the option numbers
  actually offered — a 5-way menu shows five buttons, not just the old
  fixed 1/2. `y`/`n`/Tab/Enter stay as permanent baseline buttons
  regardless of what's detected; a plain 1/2 fallback covers prompts with
  no recognizable numbered menu.
- A connection-quality indicator: the daemon and phone each independently
  ping/pong every 10s over the existing paired connection and measure
  their own round-trip latency (no relaying a single measurement between
  devices). `/buddy-list` always shows it, plus last-seen for offline
  peers; the phone's own paired-session list shows it too, gated behind a
  new "Show connection details" Settings toggle (off by default).
- Read notifications aloud: a new "Read notifications aloud" Settings
  toggle (off by default) speaks Claude Code's actual on-screen prompt via
  Android's built-in text-to-speech when a Notification hook fires — not
  just the hook's generic "Claude needs your attention" message, but the
  real question and its numbered options (e.g. "Do you want to make this
  edit to foo.ts? Options: 1: Yes. 2: Yes, and don't ask again this
  session. 3: No."), extracted with the same viewport scan added for the
  dynamic quick-reply buttons above. Falls back to the generic message
  when nothing substantive is on screen to extract from.
- Rate limiting on the pairing PIN handshake: 5 wrong PINs from the same
  source within 60s locks that source out for 30s, during which further
  attempts are denied immediately without even checking the submitted
  PIN. The PIN itself (6 digits, single-use, 2-minute TTL) was the only
  defense before this — brute-forcing that window at LAN speed with no
  rate limiting was realistic. A successful pairing clears the source's
  history, so a legitimate device that mistypes once isn't penalized.
- Basic daemon-side logging to `~/.buddy/daemon.log` (single-generation
  size-based rotation at 5MB) — connection lifecycle events (daemon start,
  pair, unpair, reconnect attempts/success, dropped/rejected peers) and
  errors are now persisted, not just printed to whatever terminal
  `buddy start` happens to be running in. A problem that happens while
  nobody's watching the terminal now leaves a trace to debug afterward.
- An audit marker for phone-originated input: every reply/keystroke
  applied from the phone now logs to `~/.buddy/daemon.log` (deviceName,
  peerId, a truncated preview of the text) instead of being
  indistinguishable from local keyboard input in the terminal's own
  output. Logged rather than injected directly into the live terminal
  display — Claude Code's TUI manages its own absolute-cursor-positioned
  redraws, and an out-of-band stdout write in the middle of one risks
  visibly corrupting the display for a cosmetic marker.
- A speaker icon in the terminal screen's top bar toggles "read
  notifications aloud" directly (same setting as the Settings screen
  toggle), with a Toast confirming on/off — no trip to Settings needed
  for the screen you're actually looking at when you'd want to flip it.
- Compact mode is now actually compact. Tightening button content padding
  alone barely changed anything — Material3 enforces a ~40dp minimum
  touch-target height on buttons regardless of padding, which dominated
  the rendered size. Now uses an explicit height override (which does win
  over that internal minimum) across the quick-reply row, side scroll
  buttons, top bar, and the reply text field (matched to the Send button
  instead of towering over it, at a slightly taller floor than the other
  buttons since text fields clip their own content below ~48dp).
- Local (non-CI) debug builds now show a real, distinct, auto-incrementing
  build number in the app's own UI instead of a hardcoded "build 1"
  regardless of how many times the APK was actually rebuilt. A counter
  file (gitignored — a local dev artifact, not meaningful across
  machines) bumps once per Gradle invocation; CI-tagged release builds
  are unaffected, still using their real versionCode from the workflow.

### Changed
- The phone's WS server now runs on Ktor's Netty engine instead of CIO —
  CIO has no server-side TLS support (`ktorio/ktor#886` is still open).
- The foreground service's type is now `connectedDevice` instead of
  `dataSync`. `dataSync` is meant for finite jobs (upload/backup/fetch),
  and starting with Android 15 it's subject to a 6-hour cumulative
  background runtime cap — wrong fit for a service meant to hold an
  indefinite live connection open. `connectedDevice` (network/Bluetooth/
  USB connection to an external device) is both the correct semantic fit
  and has no such time limit.

### Breaking
- Existing paired phones have no pinned certificate to verify against, so
  every current pairing needs one manual `/buddy-pair` after upgrading both
  the daemon and the Android app. There's no compatibility mode — old and
  new builds can't talk to each other (`ws://` vs `wss://`).

### Fixed
- The daemon's installed Notification/Stop/PreToolUse hooks now fail
  silently when it isn't running, instead of surfacing a visible
  "ECONNREFUSED" error on every hook fire. They're `type: "command"`
  (`curl ... ; exit 0`) rather than Claude Code's native `type: "http"`,
  which has no way to suppress a connection error — this covers the
  daemon exiting via a crash/reboot, not just a graceful stop.

## [0.2.0] - 2026-08-16

### Added
- Auto-reconnect: if a paired phone's connection drops unexpectedly (network
  blip, phone backgrounding, Tailscale re-routing), the daemon now retries
  the connection on its own with capped exponential backoff, reusing the
  phone's saved token to skip the PIN/accept flow. Previously any drop left
  the peer permanently disconnected until a manual `/buddy-pair`.
- A deliberate `/buddy-unpair` correctly suppresses the reconnect it
  triggers, and a rejected token (phone unpaired from its side, app data
  cleared) drops the peer instead of retrying forever.

This release only touches `buddy-daemon` (the PC side) — no Android app
changes.

## [0.1.0] - 2026-08-16

### Added
- Initial public release: pair a Claude Code terminal session with the CC
  Buddy Android app over LAN or Tailscale, mirror the terminal to the
  phone, and get push notifications (including while the phone is locked)
  when Claude needs input.
- `scripts/install.sh` / `scripts/install.ps1` to install, update, or
  uninstall the daemon + Claude Code plugin from a clone of this repo.
- GitHub Actions workflow to build and sign a release APK on version tags,
  publishing it as a GitHub Release.
- Fixed a rendering bug where the phone's mirror left the bottom
  status bar/prompt blank until enough new terminal output forced a full
  redraw, by seeding new connections with a full-screen snapshot.
