#!/bin/sh
# Harmless probe: does the VibeCraft API check Origin / Host? Uses only "say".
B=${1:-http://127.0.0.1:8830}
curl -s -o /dev/null -w "foreign Origin GET /api/status -> %{http_code}\n" -H "Origin: https://evil.example" "$B/api/status"
curl -s -o /dev/null -w "foreign Host  GET /api/status -> %{http_code}\n" -H "Host: rebind.example:8830" "$B/api/status"
curl -s -w "\n" -X POST -H "Origin: https://evil.example" -H "Content-Type: text/plain" --data "say redteam-sec origin probe" "$B/api/command"
