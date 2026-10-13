# ai-coder-8: "une commande /meteo qui change la météo avec un compte à rebours en bossbar"

Module `meteo`. `/meteo <soleil|pluie|orage|stop> [secondes=10]`: Adventure boss bar (`ctx.track`, late
joiners) counting down, a pling on the last 3 s, then `setStorm/setThundering` + durations, broadcast and
level-up sound. `/meteo etat` prints `hasStorm/isThundering/getWeatherDuration` (added to verify the
server state, because mineflayer's `isRaining` is inverted through ViaVersion on 26.x).

Harness: `meteo.mjs` (run as a non-op player).

Expected = observed: 2 reloads (the 2nd added `etat`), 0 compile errors; boss bar packets add -> title
"2 s", "1 s" + progress 0.67/0.33 -> remove; `/meteo etat` after `pluie 1` = "Pluie : true", after
`soleil 1` = "Pluie : false". Time: ~62 s.

This module was also used for the `double-reload` measurement: append a comment line to the file, wait
D seconds, then `POST /reload`. D = 0 or 1 s -> +1 generation; D = 1.5 / 2 / 2.5 / 3.5 s -> +2 generations
(watcher reload + a redundant explicit one, each announced in chat).
