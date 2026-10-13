# leaks-prio: event priorities and ignoreCancelled (held)

Handlers on `RemoteServerCommandEvent` (fired by RCON commands) through `ctx.on` (5 priorities, one with
ignoreCancelled), `ctx.listen` (@EventHandler HIGHEST ignoreCancelled + LOW) and a raw
`PluginManager.registerEvent(..., ignoreCancelled=true)`. A command containing "cancelme" is cancelled at LOWEST.

Observed (`/lkprio` after `rcon "say normal-line"`):
`on-LOWEST > listen-LOW > on-NORMAL > raw-NORMAL-ignoreCancelled > on-HIGH-ignoreCancelled > on-HIGH > listen-HIGHEST-ignoreCancelled > on-MONITOR(cancelled=false)`
after `rcon "say cancelme please"`: `on-LOWEST > listen-LOW > on-NORMAL > on-HIGH > on-MONITOR(cancelled=true)`
(every ignoreCancelled handler skipped). After a reload: the same single set (no duplicates).
