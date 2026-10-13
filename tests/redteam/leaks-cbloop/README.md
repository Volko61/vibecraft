# leaks-cbloop: guard Errors thrown from an unguarded entry point crash the server (critical, same root cause as leaks-callback)

Not a leak but the proof of the root cause behind leaks-callback / leaks-jul: when the server calls module
code outside VibeCraft's guarded wrappers, whatever the guard throws goes straight into server code.

Module: `/lkcbloop throw` or `/lkcbloop loop` sends a clickable line whose LIVE `ClickEvent.callback` throws an
IllegalStateException, or loops forever.

Observed (`crash-excerpt.txt`):
- `throw`: `ERROR Failed to handle packet ServerboundCustomClickActionPacket[...], suppressing error` + stack
  trace; player and server fine (vanilla catches Exceptions); the module's runtimeErrorCount stays 0.
- `loop`: the main thread spins until the fallback deadline (tick start + tick-budget-ms = 2 s), then
  `Guard.check` throws `ModuleTimeoutError` (an Error) from `ServerCommonPacketListenerImpl.handleCustomClickAction`
  <- `PacketProcessor.executeSinglePacket` <- `MinecraftServer.runAllTasksAtTickStart` ->
  "Encountered an unexpected exception" -> crash report -> "Stopping server".

So: a 2 s freeze followed by a crash, for a bug the guard is meant to contain.

Suggested fix: (1) wrap ClickEvent.callback (see leaks-callback) so the callback runs inside `Guard.invoke`
with the handler budget; (2) generic: when `Guard.check` must abort module code that was entered from
unguarded server code (`depth == 0` on the main thread), throw a RuntimeException subtype (e.g.
`ModuleAbortException`) instead of an Error, so vanilla/Paper `catch (Exception)` blocks contain it (the
`timedOut` flag keeps the next check throwing, so loops still die even if module code catches it); for a
killed generation, return a default value at method entry instead of throwing (leaks-callback).

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

`loop`: the click callback runs under the guard -> aborted after the 250 ms handler budget, `ModuleTimeoutError` recorded for the module, server keeps running. Module code reached directly by the server with no guarded call on the stack is aborted with `ModuleAbortException` (a RuntimeException that vanilla/Paper contain) instead of an Error.
