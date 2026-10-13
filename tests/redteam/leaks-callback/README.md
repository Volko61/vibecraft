# leaks-callback: clicking an old ClickEvent.callback CRASHES THE SERVER (critical) + retains every generation

Module: `/lkcb [player]` (and every enable) sends `[cliquez vN]` built with
`Component.text(...).clickEvent(ClickEvent.callback(a -> a.sendMessage(...)))`. The callback captures module
state (1 MB array, to make the retention visible).

## 1. Crash

Steps (sandbox, bot LeakBot connected, `../leaks-tools`):
1. load the module (v1); `/lkcb` -> the bot receives a message whose click event is
   `{"action":"custom","id":"paper:click_callback","payload":{"id":...}}`
2. click it while v1 is live (`botctl '{"op":"customClick","id":"paper:click_callback","payloadId":"<hex>"}'`)
   -> "callback v1 ran" (works)
3. `/lkcb` again (a new, not clicked callback), then `POST /api/modules/leaks-callback/reload` (v2)
4. click the v1 message (a human just clicks an old line in the chat history)

Expected: nothing (or "no longer available"); the server keeps running.

Observed: `ERROR Encountered an unexpected exception dev.vibecraft.core.guard.ModuleKilledError: module
'leaks-callback' v1 was unloaded` -> crash report "Exception in server tick loop" -> "Stopping server", every
player disconnected (`crash-report-excerpt.txt`).

Why: the lambda is module code; its instrumented entry `Guard.check(id)` throws `ModuleKilledError` (an
`Error`). Paper runs the callback from the custom-click packet handler on the main thread, outside any
VibeCraft guard, and the vanilla task runners only catch `Exception`, so the Error reaches the tick loop.

## 2. Retention

Each generation that sent a callback stays reachable from Paper's callback store (12 h default lifetime)
until it is clicked or expires. 50 reloads with one online player (`soak.mjs --module leaks-callback
--count 50 --every 25`): ModuleClassLoader instances 14 -> 38 -> 63, heap after full GC 353 -> 419 -> 463 MB.

## Suggested fix (engine)

- Redirect `ClickEvent.callback(...)` (3 overloads; same for Paper `DialogAction.customClick`) to a host
  wrapper `ClickCallback<Audience>` that keeps the module callback in a field cleared on unload (fixes the
  retention) and runs it through `Guard.invoke` (handler budget, errors recorded); a no-op once killed.
- Generic safety net for every "the server calls stale module code" path (also leaks-jul, leaks-world,
  leaks-map, leaks-goal, leaks-conv): make the METHOD-ENTRY check of a killed generation return a default
  value (no-op) instead of throwing an Error; keep the throwing check on loop back-edges only (that is what
  stops zombie loops). A `ModuleKilledError` must never be thrown into server code.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Clicking the v1 line after the reload -> "Ce lien n'est plus actif (module rechargé)." and the server keeps running. `ClickEvent.callback` is redirected to a guarded host wrapper whose module callback is dropped on unload (no retention). Generic safety net: the entry of killed code called by the server returns a default value instead of throwing (see ../fixer-killed-entry).
