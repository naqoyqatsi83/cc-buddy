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
`connected`, `pairedAt`). Print each as `<name> (<id>) — <ip>:<port> —
online/offline`. If empty, tell the user no phones are paired yet and
suggest `/buddy-scan` or `/buddy-pair <ip> <pin>`.
