# CC Buddy — Claude Code ↔ Android pairing app

## Goal

Pair a specific interactive Claude Code terminal session with a paired Android
phone so that:

1. The phone shows a live mirror of that terminal session.
2. When Claude Code is waiting on user input (permission prompt, idle,
   finished a turn), the phone gets a notification.
3. The user can type a reply on the phone and it's injected into the live
   Claude Code session, as if typed on the keyboard.
4. Pairing is initiated from the PC (`claude`'s terminal), using a PIN shown
   on the phone — never the other way around, since inbound connections to
   the PC are the ones firewalls/AV block.
5. Works over LAN (mDNS discovery) and over Tailscale (direct IP, no
   discovery needed) and on Windows, macOS, and Linux.

## Why we don't hook Claude Code's `claude` binary directly

A Claude Code skill/slash-command runs as a *child process of* `claude`. It
cannot reach back and control the terminal hosting `claude` itself — that
requires being the *parent*. So the architecture wraps `claude` in a PTY we
own, and the in-session slash commands become thin clients that talk sideways
to that wrapper over a local socket.

```
Phone (CC Buddy app)                         PC
┌─────────────────────┐   WebSocket   ┌────────────────────────────┐
│ Foreground service   │◄─────────────┤ buddy-daemon (Node.js)      │
│  - WS/HTTP server    │  (PC dials   │  - spawns `claude` in a PTY │
│  - NSD/mDNS advertise│   OUT to     │    via node-pty             │
│  - PIN + pairing UI  │   phone)     │  - localhost control API    │
│  - xterm.js WebView  │               │  - mDNS browse (pure-JS)    │
│    terminal mirror   │               │  - HTTP hook receiver       │
│  - push notification │               └─────────────┬────────────┘
└─────────────────────┘                               │ stdin/stdout
                                                        │ (PTY)
                                                ┌───────▼────────┐
                                                │  claude (CLI)   │
                                                │  running skills: │
                                                │  /buddy-scan     │
                                                │  /buddy-pair     │
                                                │  /buddy-list     │
                                                │  /buddy-unpair   │
                                                └──────────────────┘
```

The daemon passes `BUDDY_DAEMON_URL` and `BUDDY_SESSION_ID` as env vars to
the `claude` process it spawns. The `/buddy-*` slash commands (implemented as
a Claude Code plugin — commands + skill) just read those env vars and call
the daemon's localhost API. They never touch the network directly.

---

## Component 1: `buddy-daemon` (PC side)

**Stack:** Node.js + TypeScript. Chosen because `node-pty` (same lib VS Code
uses) gives real cross-platform PTY support including native Windows
ConPTY — no WSL required. Use pure-JS mDNS libraries (e.g. `bonjour-service`
or `multicast-dns`) rather than anything with native Bonjour bindings, since
those are unreliable on Windows.

**Responsibilities:**

1. `buddy start [--cwd <path>]` — spawns `claude` inside a `node-pty`
   pseudo-terminal, generates a `BUDDY_SESSION_ID` (uuid), sets
   `BUDDY_DAEMON_URL=http://127.0.0.1:<control-port>` and
   `BUDDY_SESSION_ID=<uuid>` in its env, and attaches to the *real* terminal
   so the user's local usage is completely unaffected — this is meant to
   fully replace typing `claude` directly (alias it: `alias claude="buddy start --"`).
2. **Localhost control API** (bind `127.0.0.1` only, never `0.0.0.0`):
   - `GET /sessions` — list active local sessions this daemon owns.
   - `POST /sessions/:id/scan` — trigger mDNS browse for `_buddycc._tcp`,
     return discovered `{name, ip, port}` entries (used by `/buddy-scan`).
   - `POST /sessions/:id/pair {ip, port, pin}` — dial out to the phone,
     perform the pairing handshake (see Component 3), store the resulting
     long-lived pairing token in the OS keychain (`keytar`) or an encrypted
     local file, bind that phone connection to this session id.
   - `GET /sessions/:id/peers` — list phones paired to this session
     (used by `/buddy-list`).
   - `POST /sessions/:id/unpair {peer_id}` — send revoke, close socket,
     delete stored token.
   - `POST /hook/notification`, `POST /hook/stop`, `POST /hook/pretooluse`
     — targets for Claude Code's native HTTP hooks (see Component 2).
3. **PTY bridge:** on the WebSocket connection to a paired phone, forward
   raw PTY output chunks (phone renders with xterm.js — gets the identical
   screen, including diffs Claude Code prints for Edit/Write tool calls).
   Incoming text frames from the phone are written straight into
   `pty.write(text + "\r")`, indistinguishable from local typing — this is
   how permission-prompt answers ("1", "y", free-text replies) get injected.
4. **Transcript tail (secondary/backup channel):** also tail
   `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl` and forward
   *structured* events (not just raw ANSI) to the phone. Useful for a
   cleaner mobile UI (e.g. rendering diffs nicely) even though the PTY
   stream already has everything — treat this as optional richness, not the
   critical path.

---

## Component 2: Claude Code hooks (structured "needs attention" signal)

Don't rely on scraping ANSI output to detect "Claude is waiting." Claude
Code has a native mechanism for this — register **HTTP hooks** in
`.claude/settings.json` (project) or `~/.claude/settings.json` (global) that
POST JSON straight to the daemon, no shell script needed:

```json
{
  "hooks": {
    "Notification": [
      { "matcher": "*", "hooks": [
        { "type": "http", "url": "http://127.0.0.1:<control-port>/hook/notification" }
      ]}
    ],
    "Stop": [
      { "matcher": "*", "hooks": [
        { "type": "http", "url": "http://127.0.0.1:<control-port>/hook/stop" }
      ]}
    ],
    "PreToolUse": [
      { "matcher": "Edit|Write", "hooks": [
        { "type": "http", "url": "http://127.0.0.1:<control-port>/hook/pretooluse" }
      ]}
    ]
  }
}
```

- `Notification` fires on permission prompts / idle waits — this is your
  "buzz the phone" trigger. Payload includes a `message` field, forward it
  verbatim as the push notification body.
- `Stop` fires when Claude finishes a turn — use to clear a "Claude is
  working" state on the phone.
- `PreToolUse` on `Edit|Write` gives you the tool input (file path + diff
  content) *before* it's applied — nicer for a mobile "review this change"
  card than parsing terminal output.

When `/hook/notification` fires, the daemon looks up which phone(s) are
paired to that session id and sends a WebSocket push; if the app is
backgrounded and the socket is dead, fall back to Firebase Cloud Messaging
(see Component 3, Phase 2) to actually wake the phone.

---

## Component 3: Android app ("CC Buddy")

**Why the phone is the server, not the client:** the PC usually sits behind
a firewall/AV that blocks unsolicited inbound connections; phones on the
same LAN/hotspot generally don't. So the phone opens a listening socket, the
PC dials out. This also matches what you described.

**Core pieces:**

1. **Foreground service** holding:
   - A lightweight embedded server (Ktor or NanoHTTPD) for the WebSocket
     endpoint the daemon connects to.
   - `NsdManager` (Android's mDNS/Bonjour API) advertising service type
     `_buddycc._tcp` with a TXT record containing app version / device name.
     This is what `/buddy-scan` discovers on LAN.
   - A persistent "CC Buddy — listening" notification (required for Android to
     keep the foreground service alive).
2. **Pairing screen:** generates a 6-digit PIN (regenerate on demand,
   short TTL — e.g. 2 minutes), displays it plus every local IP the phone
   currently has (Wi-Fi IP, and — if the Tailscale app is active — the
   `100.64.0.0/10` CGNAT-range IP, detected via
   `NetworkInterface.getNetworkInterfaces()`), so the user can read the
   right one for `/buddy-pair <ip> <PIN>` depending on whether they're on
   LAN or Tailscale.
3. **Pairing handshake** (over the WS connection the daemon just opened):
   - PC → phone: `{type: "pair_request", pin, device_name}`
   - Phone verifies PIN + TTL, shows an accept/deny prompt to the user
     (don't auto-accept even with a correct PIN — this is your real
     authorization step).
   - Phone → PC: `{type: "pair_ok", token}` — a long-lived random token,
     stored on both sides, used to skip the PIN on future reconnects to the
     same session id.
4. **Terminal mirror:** a WebView running `xterm.js`, fed the raw PTY byte
   stream over the WebSocket. This gets you literally the same screen as
   the PC, ANSI colors, cursor movement, and all — no reimplementing
   Claude Code's rendering.
5. **Reply input:** a text field + send button that writes a text frame
   back over the WS; daemon pipes it into the PTY. Also surface quick-reply
   buttons for common permission answers (`1`, `2`, `y`, `n`, Enter) since
   typing on a phone for a yes/no prompt is annoying.
6. **Paired-session list:** since one phone can pair with multiple PC
   sessions over time, show them as a list with online/offline state and
   an unpair action (mirrors `/buddy-list` / `/buddy-unpair` on the PC
   side).

**Branding:** show an ASCII-art wordmark at the top of the app (splash
screen and/or pairing screen header), styled to match Claude Code's own
terminal aesthetic — monospace font, block/figlet-style letters. Example:

```
 ██████╗ ██████╗          ██████╗ ██╗   ██╗██████╗ ██████╗ ██╗   ██╗
██╔════╝██╔════╝          ██╔══██╗██║   ██║██╔══██╗██╔══██╗╚██╗ ██╔╝
██║     ██║               ██████╔╝██║   ██║██║  ██║██║  ██║ ╚████╔╝
██║     ██║               ██╔══██╗██║   ██║██║  ██║██║  ██║  ╚██╔╝
╚██████╗╚██████╗          ██████╔╝╚██████╔╝██████╔╝██████╔╝   ██║
 ╚═════╝ ╚═════╝          ╚═════╝  ╚═════╝ ╚═════╝ ╚═════╝    ╚═╝
```

Render it as actual text (a monospace `Text`/`TextView`, not a bitmap) so it
stays crisp at any screen size and can be recolored to match the app's
theme. Keep it in a shared composable/layout so it's trivial to swap later.

**Version/build visibility:** every screen of the app (or at minimum the
pairing screen and a persistent footer) must show the running
`versionName` + `versionCode`, e.g. `v0.3.1 (build 47)`, sourced from
`BuildConfig.VERSION_NAME` / `BuildConfig.VERSION_CODE` — never hardcoded,
so it auto-updates every build. This exists purely so that a bug report or
screenshot during development can be tied to the exact build that produced
it; treat it as removable/hideable behind a debug flag once the app
stabilizes, but keep it on by default for the whole prototyping phase.
Suggested placement: small monospace line directly under the ASCII logo,
e.g.:

```
v0.3.1 (build 47) · debug
```

**Phase 2 (don't block MVP on this):** Add Firebase Cloud Messaging so
notifications still arrive if Android has killed the foreground service.
This requires a tiny cloud relay (the daemon can't call FCM directly without
your own backend/API key flow), so treat it as an enhancement, not part of
the first working version.

---

## The `/buddy-*` Claude Code plugin (skill + commands)

Package as a Claude Code plugin with four commands. Each is a thin wrapper —
all logic lives in the daemon; the command just reads `BUDDY_DAEMON_URL` /
`BUDDY_SESSION_ID` from env and calls the local control API, printing the
result.

- **`/buddy-scan`** → `POST {daemon}/sessions/{id}/scan` → prints discovered
  phones as a numbered list (`name`, `ip`, `port`).
- **`/buddy-pair <ip> <pin>`** → `POST {daemon}/sessions/{id}/pair` → prints
  success/failure. (If `BUDDY_DAEMON_URL` isn't set — i.e., `claude` was
  launched directly instead of via `buddy start` — print a clear message
  telling the user to relaunch through the wrapper.)
- **`/buddy-list`** → `GET {daemon}/sessions/{id}/peers` → prints paired
  phones + connection status.
- **`/buddy-unpair [peer_id]`** → `POST {daemon}/sessions/{id}/unpair` →
  confirms revocation.

---

## Build order (what to actually ask Claude Code to do, in sequence)

1. **`buddy-daemon` skeleton**: CLI entry (`buddy start`), spawns `claude`
   via `node-pty`, transparently proxies the real terminal (so at this
   stage using `buddy start` instead of `claude` directly changes nothing
   visible) — get this rock solid on macOS/Linux/Windows before anything
   else, since everything depends on it.
2. **Localhost control API** + the four `/buddy-*` commands, tested against
   a stub "phone" (a CLI script that fakes the WebSocket server) before any
   Android code exists.
3. **Android app MVP**: foreground service + WS server + PIN pairing screen
   only (no terminal mirror yet) — prove the handshake end-to-end.
4. **PTY streaming + xterm.js mirror** on the phone.
5. **Reply injection** — phone → daemon → `pty.write()`.
6. **Claude Code HTTP hooks** wired to `/hook/notification` and
   `/hook/stop`, driving a local Android notification.
7. **mDNS** both sides (`bonjour-service`/`multicast-dns` on the daemon,
   `NsdManager` on Android) — do this after IP-based pairing already works,
   since it's a convenience layer, not the critical path.
8. **Tailscale**: no special code — just confirm `/buddy-pair` works with a
   Tailscale IP as-is.
9. **Phase 2**: FCM for background notifications; encrypted token storage
   hardening; multi-session support in the Android UI.

## Security notes to bake in from step 1

- Control API binds `127.0.0.1` only — never expose it on the LAN.
- PIN has a short TTL and is single-use per pairing attempt.
- Pairing always requires an explicit accept tap on the phone, even with a
  correct PIN.
- Long-lived tokens stored via OS keychain (`keytar`) on PC and
  `EncryptedSharedPreferences` on Android, not plaintext.
- The WS channel between daemon and phone should be TLS where practical
  (self-signed cert pinned during pairing) since it carries full terminal
  content, including anything sensitive Claude Code prints.
