# ai-coder-command-collision: two parallel AI modules register the same command name

Two chats choose the same command name (very likely with "un /shop", "un /jeu", "un /menu"...).
Module `shop` (see `ai-coder-6-shop`) registers `/shop` with alias `/boutique`; module `shop-bis`
(here) registers `/shop` and `/boutique` too.

Repro (sandbox API 8820), with `shop` already loaded:
```
copy shop-bis/ into the modules dir
curl -s -X POST http://127.0.0.1:8820/api/modules/shop-bis/reload      -> ok:true, diagnostics [] (no warning)
curl -s -X POST http://127.0.0.1:8820/api/command --data "shop"         -> "Usage : /shop [joueur]"   (module shop)
curl -s -X POST http://127.0.0.1:8820/api/command --data "boutique"     -> "shop-bis boutique"        (alias stolen)
curl -s -X POST http://127.0.0.1:8820/api/command --data "vibe:shop"    -> "shop-bis repond"          (vibe:shop overwritten)
curl -s -X POST http://127.0.0.1:8820/api/modules/shop-bis/disable
curl -s -X POST http://127.0.0.1:8820/api/command --data "boutique"     -> "Unknown command: /boutique"
curl -s -X POST http://127.0.0.1:8820/api/command --data "vibe:shop"    -> "Unknown command: /vibe:shop"
```
`shop`'s alias and `vibe:shop` only come back when `shop` itself is reloaded. Re-enable `shop-bis`, reload
`shop`: `/shop` and `/vibe:shop` -> module shop, `/boutique` -> shop-bis; shop-bis's own `/shop` is then
unreachable under any label, yet its status still lists `"commands":["shop","boutique"]`.

Observed: the only trace is a log line in shop-bis's logs, `WARNING /shop already exists (another module
or plugin): use /vibe:shop` (hint wrong once the other module reloads), nothing for the stolen alias;
the reload result is ok:true without diagnostics, and the second chat's smoke test
`/api/command shop` prints the OTHER module's output.

Expected: a clear signal to the second module (reload diagnostic WARNING or enable failure "command /shop
is owned by module shop, pick another name"), no alias stealing between modules, and labels of the
remaining module restored when the other one unloads.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

shop-bis -> reload `ok:false`, "command /shop is already used by module 'shop': pick another command name"; /shop, /boutique and /vibe:shop still go to shop. A taken alias is skipped with a WARNING diagnostic (../fixer-alias-conflict).
