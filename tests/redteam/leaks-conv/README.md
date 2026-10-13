# leaks-conv: a Bukkit Conversation started by the old generation swallows the player's chat (medium)

Module: `/lkconv` starts a quiz with `new ConversationFactory(ctx.plugin()).withFirstPrompt(modulePrompt)...
.buildConversation(p).begin()`.

Steps: `/lkconv` (question shown), reload the module, the player answers "4" in chat, then says "hello2".

Expected: the conversation is abandoned on reload; chat works normally.
Observed: `WARN Plugin VibeCraft v0.1.0 generated an exception whilst handling conversation input ...
ModuleKilledError`; the answer AND every following chat line of that player are swallowed (nobody sees
them) until the conversation times out (120 s here; never with the factory's default timeout of 0 = until
the player reconnects).

Suggested fix: redirect `Conversation.begin()` / `Conversable.beginConversation(...)` to track
`abandon()` on unload.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

After the reload the chat works normally ("<FixBot> 4", "<FixBot> hello2"): conversations begun by a generation are abandoned on unload.
