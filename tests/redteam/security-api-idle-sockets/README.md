# security-api-idle-sockets
What: checks the 64 KB body limit, then holds 30 connections with half-sent request headers and polls `/api/status`.
Expected: the body is rejected with 413 (holds), and the status call still answers while the sockets are held.
Observed (2026-10-08, sandbox): 413 OK. `/api/status` times out (>8 s) while the sockets are held and recovers at once (200, ~2 ms) after they close. The game stayed at 20 TPS.
Reachable only from local processes or modules (API bound to 127.0.0.1). Edit H/P in the script for your port. Run: `node idle-sockets.mjs`

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

30 idle sockets: /api/status 200 in 3-6 ms; 100 idle sockets: ~1.9 s (32 request threads, `sun.net.httpserver.maxReqTime=3` closes half-open requests; long reloads are unaffected because the request is complete once read).
