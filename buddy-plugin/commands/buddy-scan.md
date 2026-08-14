---
description: Scan the LAN for CC Buddy phones this session can pair with
allowed-tools: Bash(curl:*)
---

If the `BUDDY_DAEMON_URL` or `BUDDY_SESSION_ID` environment variables are not
set, stop and tell the user: "This session wasn't launched through
`buddy start` — relaunch with `buddy start` (or your `claude` alias) to use
CC Buddy pairing." Do nothing else in that case.

Otherwise run exactly:

```
curl -s -X POST "$BUDDY_DAEMON_URL/sessions/$BUDDY_SESSION_ID/scan"
```

The response is a JSON array of `{name, ip, port}` objects. Print the
results as a numbered list (`1. <name> — <ip>:<port>`) for use with
`/buddy-pair <ip> <pin>`. If the array is empty, tell the user no phones
were found on the LAN yet and that they can still pair manually with
`/buddy-pair <ip> <pin>` using the IP shown on the phone's pairing screen.
