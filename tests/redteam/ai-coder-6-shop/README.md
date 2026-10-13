# ai-coder-6: "un /shop avec un menu GUI pour acheter des items avec des émeraudes"

Module `shop`. `/shop [player]` (alias `/boutique`) opens a 27-slot chest menu (nested `Menu implements
InventoryHolder`): 7 offers with a price lore and a wallet item. Clicks in the menu are always cancelled;
a click on an offer removes the emeralds and gives the item, or refuses. `InventoryDragEvent` is
cancelled; open menus are closed on cleanup (the Menu class of the old generation is gone after a reload).

Harness: `shop.mjs` (give 10 emeralds first), `shop2.mjs` (reload while the menu is open).

Expected = observed: 1 reload, 0 compile errors; menu opens; golden apples bought (10 -> 7 emeralds),
elytra refused ("Il te faut 20 émeraudes (tu en as 7)"), shift-click buys (steaks) instead of stealing;
a reload closes the open menu. Time: ~45 s.

See also `ai-coder-command-collision`: a second module registering `/shop` or `/boutique` silently
shadows or steals these commands.
