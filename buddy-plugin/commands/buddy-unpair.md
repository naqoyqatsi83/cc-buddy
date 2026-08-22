---
description: Unpair a phone from this Claude Code session
argument-hint: [peer_id]
allowed-tools: Bash(curl:*)
---

If the `BUDDY_DAEMON_URL` or `BUDDY_SESSION_ID` environment variables are not
set, stop and tell the user: "This session wasn't launched through
`buddy start` — relaunch with `buddy start` (or your `claude` alias) to use
CC Buddy pairing." Do nothing else in that case.

`$1` is an optional peer id. Before using it, compare it against the raw
`<command-args>` the user actually typed — a known harness bug can
mis-substitute positional arguments (see cc-buddy#24); if `$1` doesn't
match what the user typed, use the value from `<command-args>` instead.

If it's missing, first run
`curl -s "$BUDDY_DAEMON_URL/sessions/$BUDDY_SESSION_ID/peers"` and:
- if there are zero peers, tell the user there's nothing to unpair and stop.
- if there's exactly one peer, use its `id` as the target.
- if there are multiple, list them (name + id) and ask the user to re-run
  `/buddy-unpair <peer_id>` with the one they want.

Once you have a target peer id, run exactly:

```
curl -s -X POST "$BUDDY_DAEMON_URL/sessions/$BUDDY_SESSION_ID/unpair" \
  -H "Content-Type: application/json" \
  -d "{\"peer_id\": \"<id>\"}"
```

Confirm success (`{"ok": true}`) or show the `error` field on failure.
