# ai-coder-7: follow-up on (1) "les cochons doivent avoir des chapeaux (citrouilles) et disparaître après 10 secondes"

Module `oink-rain` v2 (edit of ai-coder-1). Pigs don't render helmets (`getEquipment().setHelmet(...)`
would compile and silently show nothing), so each pig gets an `ItemDisplay` carved pumpkin (scaled 0.55)
as a passenger; pig and hat are removed together after 10 s (both also tracked with `ctx.entity`).

Harness: `oink2.mjs`.

Expected = observed: 1 reload (2 Edits; the 2nd added the imports), 0 compile errors; 8 pigs, 8
item_display entities, every pig has a passenger; 11.4 s later 0 pigs named Oink and 0 item_display.
Time: ~40 s. CLAUDE.md had no hint about helmets on animals (added).
