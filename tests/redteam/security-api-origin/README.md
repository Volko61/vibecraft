# security-api-origin
What: sends API requests carrying a foreign `Origin` and a foreign `Host` header. The only write is a harmless `say`.
Expected (after fix): 403 for a foreign Origin or a Host other than 127.0.0.1/localhost. curl from AI agents sends no Origin, so it keeps working.
Observed (2026-10-08, sandbox): 200 / 200, and the `say` ran as console. So any web page the presenter opens can POST `text/plain` to `/api/command` (no CORS preflight), and DNS rebinding could also read the responses.
Run: `sh probe.sh http://127.0.0.1:<api-port>`

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

foreign Origin -> 403, foreign Host -> 403, cross-site POST /api/command -> 403 `cross-origin request refused`; plain curl, same-origin dashboard and localhost keep working. `/api/command` also refuses admin command lines (op, deop, kick, whitelist, stop, tick..., also inside `execute ... run`).

Also refused (403): `Sec-Fetch-Site: cross-site` and `same-site` (browsers send it on every request, also on the no-Origin GETs
of `<img>` / `<script>`; another port of localhost counts as "same-site"), `Origin: null`, `Origin: http://localhost:3000`.
Checked in a real browser: the dashboard at http://127.0.0.1:8840/ still reads `/api/status` and POSTs a reload; a fetch from it to
http://localhost:8840 (other origin) fails. RCON: VibeCraft now logs a WARNING at startup when `enable-rcon=true` and `server-ip`
is not 127.0.0.1 (RCON reachable from the network), when `rcon.password` is shorter than 16 characters and when
`broadcast-rcon-to-ops=true` (see docs/ARCHITECTURE.md section 11).
