# Changelog

All notable changes to this project are documented here.
Format loosely follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.6.2] - 2026-08-21

### Removed
- Volume-key TTS mute — pressing a volume key while CC Buddy was
  foregrounded silenced any in-progress reading, but it also fired on a
  normal volume adjustment, muting when you just wanted it louder or
  quieter (#23). The notification "Stop reading" button, tapping the
  🔊/🔇 icon in the terminal top bar, and turning the screen off/on
  remain as ways to stop a reading.

## [0.6.1] - 2026-08-20

### Fixed
- The daemon crashed outright when a reconnect attempt timed out or a
  peer's token/cert was rejected: `ws.terminate()` on a not-yet-open
  socket emits `'error'` asynchronously, but the reconnect logic stripped
  all of the socket's listeners (including `error`) right before that
  event fired, leaving it unhandled and crashing the whole process.
  `error` is now left attached (it's a no-op once the attempt has
  settled) so the async emission is safely absorbed instead of crashing.

## [0.6.0] - 2026-08-20

### Added
- A "Stop reading" action button on the "Claude needs you" notification
  silences an in-progress TTS reading — previously the only way to stop
  it was a full engine `shutdown()`, not exposed anywhere in the UI (#22).
- Tapping the 🔊/🔇 icon in the terminal top bar, or pressing a volume
  key while CC Buddy is in the foreground, also stops any in-progress
  reading (not just toggles the setting for future notifications).
- Turning the screen off (or back on) stops an in-progress reading too —
  useful when the phone is locked and you just want it to be quiet.

### Fixed
- The daemon's logger wrote warnings/errors straight to the console —
  since `buddy start` writes the wrapped PTY's raw output to that same
  process's stdout, this interleaved with and visibly corrupted Claude
  Code's TUI (most noticeably when a stale paired session tried to
  reconnect after the phone app was reinstalled and got a cert/token
  rejection). Logging is now file-only (`~/.buddy/daemon.log`).
- The pairing accept/deny dialog only ever rendered from the session-list
  screen — a request arriving while on Settings or in the Terminal view
  wasn't shown at all until you navigated back. Now renders regardless of
  which screen is active.

## [0.5.0] - 2026-08-20

### Added
- The terminal mirror now accepts live keyboard input directly — typing,
  backspace, and arrow keys edit the real PC prompt in place (e.g. a
  Tab-completed suggestion is now actually editable, not just
  append-only) instead of only going through the separate reply text
  field. xterm's own key handling turns keystrokes into the right escape
  sequences; they're forwarded straight to the PTY over the same
  raw-keystroke channel the Tab button already used (#12).
- Two new Settings toggles: "Quick-reply buttons" (the menu-number/y-n/
  Enter/Tab shortcut row, on by default) and "Reply text field" (the
  separate append-and-send field, off by default now that typing
  directly into the terminal covers that).

## [0.4.1] - 2026-08-19

### Added
- A paired session's name on the phone can now be renamed by tapping it
  (opens a text field, Save/Cancel) — the PC-derived `user@host:dir` name
  doesn't disambiguate two different checkouts that happen to share a
  directory name. Persists across reconnects, resets only on a full
  unpair + re-pair (deviceName is only ever set at initial pairing).

### Fixed
- `buddy start --cwd .` (or any relative `--cwd`) showed up on the phone
  as a session literally named `user@host:.` instead of the actual
  project directory name — `hostDeviceName()` basename'd the raw,
  unresolved `--cwd` value, and `path.basename('.')` is just `"."` again.
  Now resolves it to an absolute path first (#21).

## [0.4.0] - 2026-08-19

### Added
- The Android app now checks GitHub for a newer release on open (a
  best-effort, silent-on-failure hit against the public releases API —
  no network, GitHub down, anything unexpected all just skip it quietly)
  and shows a dismissible "Update available" card on the pairing screen
  linking to the release page when one exists. Dismissal is keyed by
  version, so dismissing one release's banner doesn't silence a later
  one. There's still no in-app auto-update — the app is sideloaded, not
  distributed through a store — this only closes the "how would I even
  know" gap.
- Unit tests for the daemon's parsing/heuristic logic, the kind of thing
  that silently regresses without coverage: menu-option detection and
  question extraction (`shadowTerminal.ts`), the reconnect state machine's
  backoff/suppression/cert-and-token-rejection paths (`reconnect.ts`), and
  hook install/uninstall path-matching (`hooksConfig.ts`) — 34 tests via a
  new `vitest` dev dependency and `npm test` in `buddy-daemon/`. A new
  `daemon-tests.yml` CI workflow runs them (plus the `tsc` build) on every
  push/PR touching `buddy-daemon/`, separate from the release-only APK
  build workflow. The Android side's logic is mostly UI-heavy Compose
  code, not worth unit testing, but its few pure-logic pieces now have
  JVM unit tests too: the pairing PIN rate limiter's backoff/lockout/reset
  behavior (`PairingAttemptLimiter`, refactored to take an injectable
  clock so tests don't sleep through a real 60s window and 30s lockout),
  PIN generation, and the Wi-Fi/Tailscale address-labeling logic
  (`NetworkUtils`, with the Tailscale-CGNAT-range check and the label
  picker split out into testable functions). 21 tests, run via a new
  `android-tests.yml` CI workflow (`./gradlew testDebugUnitTest`) on every
  push/PR touching `android/`.
- Settings now surfaces a manufacturer-specific "battery management" row
  (Samsung, Xiaomi, Huawei, Oppo, Vivo, OnePlus) on top of the existing
  stock Android battery-optimization exemption. Those OEM skins layer
  their own "sleeping apps" / autostart management on background apps
  that the standard exemption doesn't cover, so the connection could
  still get killed even when the app reports itself exempt. Deep-links
  straight to that OEM's settings screen where recognized, falling back
  to the app's own details page otherwise.

### Fixed
- `npm run build` in `buddy-daemon/` could leave the globally-linked
  `buddy` command unusable (`Permission denied`) after any rebuild.
  `npm link` sets `dist/cli.js`'s executable bit once at link time, but
  `tsc` recreates that file from scratch on every build without
  preserving it — since `buddy` is `npm link`ed straight to this repo's
  `buddy-daemon/`, that's the exact file it runs. A new `postbuild` step
  re-applies the executable bit after every build, so this can't recur
  regardless of who runs `npm run build` or how.
- The app could crash-loop on startup with `AEADBadTagException` if
  Android restored its `EncryptedSharedPreferences` (paired-session tokens,
  TLS identity) from a backup — the Android Keystore key that encrypted
  that data is device-specific and is never restored with it, so every
  decrypt attempt failed and neither `uninstall` nor `reinstall` cleared
  it (the restore just reapplied). `allowBackup` is now `false` (this data
  shouldn't leave the device anyway), and both stores now self-heal by
  discarding and regenerating an undecryptable store instead of crashing
  — previously paired sessions are lost in that case, requiring a re-pair,
  but the app starts. Reinstalling after this fix will change the phone's
  TLS identity, so any already-paired PC sessions need to be re-paired
  once (their pinned cert fingerprint won't match anymore).
- The pairing screen's block-letter "CC BUDDY" banner ran off the right
  edge on a narrower/higher-density screen (Galaxy A34) with no visible
  hint it could be scrolled — it was only ever fit-tested at a fixed 10sp
  on wider phones. It now measures the available width and picks the
  largest font size that actually fits (down to a floor), same auto-fit
  approach the terminal mirror already uses for its own sizing; horizontal
  scroll remains as a fallback for anything still too narrow at the floor.
- The TTS read-aloud misread a plain "No" option as "Number" when it was
  the last (or only) one on screen — Android's TextToSpeech normalizer
  reads a bare "No." at the very end of an utterance as the abbreviation
  ("No. 5" → "Number 5"), not the word, since nothing follows it to
  disambiguate. `spokenPrompt` (`shadowTerminal.ts`) now inserts an
  inaudible non-breaking space before that closing period on a bare "No",
  which breaks the abbreviation match without changing the actual word
  spoken or anything else about the phrase.

## [0.3.1] - 2026-08-17

### Fixed
- The daemon's installed hook commands now use POSIX shell syntax
  (`; exit 0`, `>/dev/null`) on every platform, including Windows. The
  0.3.0 fix for silent hook failures (below) assumed Claude Code invokes
  hook commands via `cmd.exe` on Windows and used `cmd` syntax
  (`& exit /b 0`, `>nul`) there — but Claude Code actually runs hook
  commands through Git Bash on Windows too, so `exit /b` failed with a
  visible `numeric argument required` error on every hook fire, and the
  `>nul` redirect (bash doesn't treat `nul` as a null device the way
  `cmd` does) left a stray `nul` file in the working directory each time.

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
