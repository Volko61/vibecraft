# ai-coder harness (persistent mineflayer test player)

Used by the AI-CODER red-team role to test the `ai-coder-*` modules as a real player.

- `botd.mjs [name]` connects ONE bot (default `Testeur`, port `$MC_PORT` or 25620) through
  `tests/e2e/lib/bot.mjs` (tick_end shim included), then runs every `queue/*.mjs` dropped next to it:
  each file exports `default async (vb, h) => result` (vb = VibeBot, h = {sleep, log}).
- `scen.sh <scenario.mjs> [timeoutSec]` drops a scenario in `queue/` and prints its JSON result.
- `scenarios/*.mjs`: the scenarios used for the 8 features. They call the sandbox API on
  `http://127.0.0.1:8820` and assume the bot is named `Testeur` (edit the port for another sandbox).

| scenario | feature | what it checks |
|---|---|---|
| oink.mjs | 1 | bot chats "oink oink les amis" -> broadcast + 8 pigs above the bot |
| tab.mjs | 1 | `/oink ` tab completion with a null TabHandler -> player names |
| sword.mjs, sword2.mjs | 2 | left click on a block -> exactly 1 lightning_bolt there, block not broken; hit a cow -> 1 bolt |
| pk.mjs, pk2.mjs, pk3.mjs | 3 | course built (12 wool + gold), chrono packets, tp onto gold -> finish + record, `/parkour top`, fall -> reset |
| tnt.mjs | 4 | bot places TNT -> primed TNT, 4 firework_rocket after 2 s, 0 explosion packets, ground intact, health 20 |
| compass.mjs | 5 | 2nd bot `Cible`; Testeur's spawn_position (compass target) packets follow Cible |
| shop.mjs, shop2.mjs | 6 | GUI opens, buy ok / refused, shift-click cannot steal; reload closes the open menu |
| oink2.mjs | 7 | 8 pigs each with an item_display passenger; all gone 11 s later |
| meteo.mjs | 8 | `/meteo pluie 3` as a non-op player: boss bar add/update/remove packets (mineflayer's `isRaining` is unreliable through ViaVersion: check `/meteo etat`) |
| naive.mjs | naive-old-api | runtime errors of the naive module when a player uses it |

Prerequisites per scenario: `foudre Testeur` before sword*.mjs, `summon minecraft:cow <x> <y> <z> {NoAI:1b}`
next to the bot before sword2.mjs, `parkour Testeur` before pk*.mjs, `give Testeur tnt 3` before tnt.mjs,
`traqueur Testeur` before compass.mjs, `give Testeur emerald 10` before shop.mjs.
