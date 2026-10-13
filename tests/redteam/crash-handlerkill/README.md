# crash-handlerkill — unregister every module's event handlers (HIGH)

`HandlerList.getHandlerLists()` + instance `hl.unregister(ctx.plugin())` removes all event handlers
registered under the shared VibeCraft host plugin — i.e. EVERY module's listeners and the engine's
ServerTickStartEvent listener. Neither call is in Policy/Redirects.

## Repro
Deploy a victim (a module with an AsyncChatEvent listener + an every(20) task). Deploy & reload
this. Victim's chat listener goes silent; TPS stays 20 (silent breakage). The engine tick listener
is removed too (Guard.tickStart / msPerTick stop).

## Fix
Register listeners under a per-module proxy plugin, or forbid/redirect `getHandlerLists` +
`unregister(Plugin|Listener)` so a module can only touch its own handlers.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Rejected at load: `HandlerList.getHandlerLists()` and `unregister(plugin)` are forbidden (also `register`, `unregister(RegisteredListener)`); `HandlerList#unregister(Listener)` only removes the module's own listeners.
`HandlerList.unregisterAll(Listener)` / `hl.unregister(Listener)` only undo the module's own registrations: a listener object taken from
`getRegisteredListeners()` (the engine's tick listener, another module's or another plugin's) is left registered (probe: the
ServerTickStartEvent listener count stays the same after trying).
