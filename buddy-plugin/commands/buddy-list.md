---
description: List phones paired to this Claude Code session
allowed-tools: Bash(curl:*)
---

If the `BUDDY_DAEMON_URL` or `BUDDY_SESSION_ID` environment variables are not
set, stop and tell the user: "This session wasn't launched through
`buddy start` — relaunch with `buddy start` (or your `claude` alias) to use
CC Buddy pairing." Do nothing else in that case.

Otherwise run exactly:

```
curl -s "$BUDDY_DAEMON_URL/sessions/$BUDDY_SESSION_ID/peers"
```

The response is a JSON array of peer objects (`id`, `name`, `ip`, `port`,
`connected`, `pairedAt`, and optionally `latencyMs`/`lastSeenAt` once at
least one ping/pong round trip has happened). Print each as:

- `<name> (<id>) — <ip>:<port> — online · <latencyMs>ms` if `connected` is
  true and `latencyMs` is present, or just `— online` if not yet measured
  (right after pairing, before the first ~10s ping interval fires).
- `<name> (<id>) — <ip>:<port> — offline · last seen <relative time>` if
  `connected` is false and `lastSeenAt` is present (compute the relative
  time from `lastSeenAt` yourself, e.g. "3m ago"), or just `— offline` if
  `lastSeenAt` is absent (never successfully connected).

If empty, tell the user no phones are paired yet and suggest `/buddy-scan`
or `/buddy-pair <ip> <pin>`.
