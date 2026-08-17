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

### Changed
- The phone's WS server now runs on Ktor's Netty engine instead of CIO —
  CIO has no server-side TLS support (`ktorio/ktor#886` is still open).

### Breaking
- Existing paired phones have no pinned certificate to verify against, so
  every current pairing needs one manual `/buddy-pair` after upgrading both
  the daemon and the Android app. There's no compatibility mode — old and
  new builds can't talk to each other (`ws://` vs `wss://`).

### Fixed
- The daemon now removes the Notification/Stop/PreToolUse hooks it installs
  into `.claude/settings.local.json` when it shuts down (however that
  happens — the wrapped `claude` exiting, Ctrl+C, or a kill). Previously
  they were left pointing at that run's now-dead control port, so every
  hook fired a visible `ECONNREFUSED` error until the next `buddy start`
  overwrote them.

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
