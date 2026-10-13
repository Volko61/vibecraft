# ai-coder-1: "fais pleuvoir des cochons quand quelqu'un écrit 'oink' dans le chat"

Module `oink-rain` (v1). `AsyncChatEvent` (async) -> `ctx.later(0, ...)` -> 8 pigs with slow falling
spawn 12-18 blocks above the writer (cap 60, each removed after 20 s). `/oink <player>` triggers it from
the console for API tests (registered with a `null` TabHandler, which completes player names).

Run: copy `oink-rain/` into the modules dir, `curl -X POST .../api/modules/oink-rain/reload`,
then `curl -X POST .../api/command --data "oink <player>"`, or have a player chat "oink".
Harness: `../ai-coder-harness/scenarios/oink.mjs`, `tab.mjs`.

Expected = observed (2026-10-08, sandbox 25620/8820): reload ok first try (0 compile errors), bot chat
"oink oink les amis" -> "OINK ! Testeur invoque une pluie de cochons !" + 8 pigs above the bot,
runtimeErrorCount 0. Time to working feature: ~33 s.

Side observation: the watcher had already loaded v1 when the explicit reload (CLAUDE.md step 5) ran, so
it produced a redundant v2 (two "chargé à chaud" lines in chat). See the `double-reload` finding.
