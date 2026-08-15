<div align="center">

# 📱 CC Buddy

Pair a Claude Code terminal session with your Android phone: live mirror, push notifications, and reply from the couch.

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](LICENSE)
[![Platform: Node.js](https://img.shields.io/badge/daemon-node.js%2018%2B-339933.svg?style=for-the-badge&logo=node.js&logoColor=white)](buddy-daemon)
[![Platform: Android](https://img.shields.io/badge/app-android-3ddc84.svg?style=for-the-badge&logo=android&logoColor=white)](android)
[![Claude Code Plugin](https://img.shields.io/badge/claude%20code-plugin-D97757.svg?style=for-the-badge)](buddy-plugin)

Claude Code keeps working on your machine. Your phone gets a live terminal, a nudge when it needs you, and a way to answer back — no cloud relay, no accounts, pairs directly over your LAN or Tailscale.

[Quick Start](#quick-start) · [How it works](#how-it-works) · [Commands](#commands) · [Manage your installation](#manage-your-installation) · [Build from source](#build-from-source)

</div>

<div align="center">
  <img src="assets/pairing-screen.png" alt="CC Buddy Android pairing screen" width="300">
  <img src="assets/settings-screen.png" alt="CC Buddy Android settings screen" width="300">
  <p><em>Pairing screen (PIN, addresses, paired sessions) and the settings screen (font size, compact mode).</em></p>
</div>

## What You Get

- Wrap any `claude` session with `buddy start` — it behaves exactly like `claude`, nothing changes about how you work.
- Pair a phone by scanning the LAN (`/buddy-scan`) or typing an IP + 6-digit PIN (`/buddy-pair`), works over Wi-Fi or Tailscale.
- Live, byte-exact terminal mirror on the phone (real xterm.js, not a screenshot) — sized to match your actual PTY so TUI redraws (box-drawing, scroll regions, status lines) render correctly.
- Push notification when Claude is waiting on you, cleared automatically once you're back.
- Reply from the phone: quick-reply buttons, a text field, or raw keystrokes (Tab, Page Up/Down, scroll wheel, Ctrl+O expand/collapse) sent straight into the PTY.
- Pair one phone with several PC sessions at once, switch between them from a single session list.
- Runs entirely on your LAN/Tailscale — the daemon only ever dials out to a phone IP you gave it; pairing tokens live in your OS keychain (`keytar` on the PC, `EncryptedSharedPreferences` on Android), not a plaintext file.

## Quick Start

### 1. Clone and install

```bash
git clone https://github.com/naqoyqatsi83/cc-buddy.git
cd cc-buddy
./scripts/install.sh
```

Windows (PowerShell):

```powershell
git clone https://github.com/naqoyqatsi83/cc-buddy.git
cd cc-buddy
.\scripts\install.ps1
```

This builds the daemon, links a `buddy` command onto your `PATH`, and installs the `cc-buddy` Claude Code plugin (`/buddy-scan`, `/buddy-pair`, `/buddy-list`, `/buddy-unpair`). Restart any open Claude Code sessions afterward. Re-run the same command any time you `git pull` to update — see [Manage your installation](#manage-your-installation).

### 2. Start a paired session

```bash
buddy start --cwd /path/to/your/project
```

This behaves exactly like running `claude` directly — same session, same everything — but now `/buddy-*` commands work inside it.

### 3. Install the Android app

Grab the APK from [Releases](../../releases), or build it yourself:

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Open the app and grant the notification permission — it shows a 6-digit PIN and this phone's local IPs (Wi-Fi / Tailscale).

### 4. Pair

From the `buddy start` session on your PC:

```
/buddy-scan
/buddy-pair <ip> <pin>
```

A pairing request pops up on the phone — accept it, and the terminal mirror opens.

## How it works

```mermaid
flowchart LR
    subgraph PC["buddy start (wraps claude in a PTY via node-pty)"]
        H["Claude Code HTTP hooks\nNotification / Stop / PreToolUse"]
    end

    subgraph Phone["CC Buddy Android"]
        WS["Ktor WS server :8765"]
        X["xterm.js mirror"]
        R["Reply UI"]
        N["Push notification"]
    end

    PC <-->|"WebSocket (PIN)"| WS
    PC -->|"raw PTY bytes, resize"| X
    R -->|"input / raw keystrokes"| PC
    H -->|"waiting-for-you event"| N
```

The daemon spawns `claude` inside a real PTY sized to match your terminal, so anything the TUI does (scroll regions, absolute cursor addressing, box-drawing) mirrors byte-for-byte on the phone instead of reflowing or garbling. Claude Code's own HTTP hooks tell the daemon when a session is waiting on you; the daemon forwards that to every paired, connected phone as a real Android notification.

## Commands

| Command | What it does |
|---|---|
| `/buddy-scan` | Browse the LAN for phones advertising `_buddycc._tcp` (mDNS) |
| `/buddy-pair <ip> <pin>` | Pair with a phone by address and the PIN shown on its screen |
| `/buddy-list` | List phones paired to this session, with online/offline status |
| `/buddy-unpair [peer_id]` | Unpair a phone (prompts if more than one is paired) |

## Manage your installation

Re-run the installer any time — it's idempotent, and picks up whatever changed after a `git pull`:

```bash
git pull
./scripts/install.sh update      # or just: ./scripts/install.sh
```

To remove CC Buddy entirely (unlinks the `buddy` command, uninstalls the Claude Code plugin, deregisters the marketplace):

```bash
./scripts/install.sh uninstall
```

Windows: `.\scripts\install.ps1 update` / `.\scripts\install.ps1 uninstall`. Your local checkout isn't deleted by either script — remove the folder yourself if you're done with it.

## Build from source

<details>
<summary>Daemon only (no phone yet)</summary>

```bash
cd buddy-daemon
npm install
npm run build
node dist/cli.js start --cwd /path/to/your/project
```

This behaves exactly like running `claude` directly.
</details>

<details>
<summary>Android app</summary>

```bash
cd android
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

Requires the Android SDK (platform 34, build-tools 34.0.0); `android/local.properties` (gitignored) should point at it.
</details>

## Project layout

- `buddy-daemon/` — Node.js/TypeScript daemon. `buddy start` wraps `claude` in a PTY (`node-pty`) and exposes a localhost-only control API used by the plugin commands and Claude Code's HTTP hooks.
- `buddy-plugin/` — Claude Code plugin providing `/buddy-scan`, `/buddy-pair`, `/buddy-list`, `/buddy-unpair`. Thin wrappers over the daemon's control API.
- `android/` — CC Buddy Android app (Kotlin + Jetpack Compose). Foreground service, embedded Ktor WebSocket server, PIN pairing handshake, paired-session list, and a live xterm.js terminal mirror with reply injection.
- `scripts/` — `install.sh` / `install.ps1`: install, update, and uninstall the daemon + Claude Code plugin.

## License

MIT — see [LICENSE](LICENSE).
