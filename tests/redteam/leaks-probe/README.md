# leaks-probe: read-only state probe

`/lkprobe [player]` prints one JSON line (run it through `POST /api/command`). Used by every other leaks-*
repro to compare server state before / after a reload. Not a finding by itself.
