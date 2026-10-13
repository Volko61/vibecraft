# leaks-chat: ChatRenderer runs outside the guard (low/medium)

Module: `ctx.on(AsyncChatEvent.class, e -> e.renderer((source, name, message, viewer) -> ...))`, the renderer
throws when the message contains "boom".

Steps: the bot says "hello world" then "boom now".

Expected: the error is caught and recorded for the module (runtimeErrorCount, circuit breaker), the message
falls back to the default format.
Observed: "boom now" is silently lost for every player; the exception goes to the console as a raw stack
trace from `ChatProcessor`; the module's runtimeErrorCount stays 0 (no circuit breaker). A stale renderer
(module reloaded while a chat message is in flight) would fail the same way (ModuleKilledError) - not
reproduced (tiny window). No time budget either (the renderer runs on the async chat thread).

Suggested fix: redirect `AsyncChatEvent.renderer(ChatRenderer)` to wrap the renderer: guarded, errors
recorded, fall back to `ChatRenderer.defaultRenderer()` on error or once the generation is killed.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

The renderer exception is recorded for the module (runtimeErrorCount 1, recentErrors) and the default renderer is used for that viewer (AbstractChatEvent.renderer is redirected to a guarded wrapper).
