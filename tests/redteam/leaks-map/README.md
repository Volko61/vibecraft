# leaks-map: an old MapRenderer spams the console forever (medium)

Module: `/lkmap` gives the player a map whose `MapView` gets a module `MapRenderer` ("show text / an image on
a map"). The renderer is called by the server while a player holds the map, outside VibeCraft's guard.

Steps: `/lkmap`, wait 2 s, reload the module, keep holding the map.

Expected: the old renderer is removed (the map stops updating or the new generation takes over).
Observed: no crash (Paper catches it), but `ERROR Could not render map using renderer
vibe.leaks_map.LeaksMapModule$1 ... ModuleKilledError` ~4 times per second for as long as anybody holds the
map (46 errors in 11 s), not counted as a module error, and the MapView pins the old class loader forever.

Suggested fix: redirect `MapView.addRenderer` to track `removeRenderer` on unload (and wrap the renderer in a
guarded delegate so runtime exceptions are attributed to the module).

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

0 "Could not render map" errors after the reload: MapView.addRenderer is tracked (removeRenderer on unload).
