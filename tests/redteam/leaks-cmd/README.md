# leaks-cmd: command rename between generations (held)

Version A: `/lkold` (alias `/lkoldalias`, tab completion). Version B: edit `CMD = "lknew"` and reload.

Observed after B: the bot's command tree (declare_commands) contains `lknew`, `lknewalias`, `vibe:lknew`,
`vibe:lknewalias` and no `lkold*`; `/lkoldalias` and `/vibe:lkold` -> "Unknown or incomplete command"; the
server's known-commands map has no `lkold*`; tab completion of `/lk` lists only live commands. Also held
after 300 reloads of leaks-soak (one `/lksoak` + alias). Commands, aliases, Brigadier nodes and client
tab-complete are cleaned correctly.
