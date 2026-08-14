---
description: Pair with a CC Buddy phone by IP and PIN
argument-hint: <ip> <pin>
allowed-tools: Bash(curl:*)
---

Arguments: `$1` is the phone's IP (from `/buddy-scan` or the phone's pairing
screen), `$2` is the 6-digit PIN shown on the phone.

If the `BUDDY_DAEMON_URL` or `BUDDY_SESSION_ID` environment variables are not
set, stop and tell the user: "This session wasn't launched through
`buddy start` — relaunch with `buddy start` (or your `claude` alias) to use
CC Buddy pairing." Do nothing else in that case.

If either argument is missing, ask the user to run
`/buddy-pair <ip> <pin>` with both values.

Otherwise run exactly (`8765` is CC Buddy's fixed WebSocket port on the
phone — see `buddy-daemon/src/constants.ts`):

```
curl -s -X POST "$BUDDY_DAEMON_URL/sessions/$BUDDY_SESSION_ID/pair" \
  -H "Content-Type: application/json" \
  -d "{\"ip\": \"$1\", \"port\": 8765, \"pin\": \"$2\"}"
```

On success the response is a peer object (`id`, `name`, `ip`, `port`) —
tell the user pairing succeeded and name the phone. On failure the response
has an `error` field — show that message verbatim (common causes: wrong PIN,
PIN expired, or the user declined the accept prompt on the phone).
