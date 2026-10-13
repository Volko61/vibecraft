# leaks-gui: a chest menu opened by the old generation becomes a free chest after a reload (high)

Module = the chest-menu pattern of CLAUDE.md: nested `Menu implements InventoryHolder`, 5 diamonds in slot
13, `InventoryClickEvent` cancelled when `getHolder() instanceof Menu`. It does NOT close open menus in
onCleanup (CLAUDE.md asks for it in one sentence; AI coders forget).

Steps: `/lkgui` (opens for LeakBot), click slot 13 -> cancelled (v1 logs it); reload the module while the
menu is open; shift-click slot 13 (bot op `click` with mode 1).

Expected: the menu is closed on reload (or clicks in it stay cancelled).

Observed: the menu stays open (holder class `LeaksGuiModule$Menu` from loader `vibe:leaks-gui#312`); v2's
listener tests `instanceof Menu` against ITS OWN Menu class -> false -> the click is not cancelled -> the
player took the 5 diamonds (`execute if items entity LeakBot container.* minecraft:diamond` -> 5). No old
code ran and nothing was thrown into Bukkit. Same for any shop / menu module edited while someone has it open.

Suggested fix (engine, cheap and robust): on unload (before ctx.close()), for each online player, if
`getOpenInventory().getTopInventory().getHolder(false)` is an instance of a class loaded by the
generation's ModuleClassLoader, call `closeInventory()`. Optionally track inventories created by
`Bukkit.createInventory` (POST redirect, weak set) to also close holder-less menus.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Menu closed by the engine on reload (open inventories whose holder class belongs to the unloaded generation, or created by it, are closed before cleanup): window null, `execute if items ... diamond` -> Test failed. Holder-less variant: ../fixer-menu-noholder.
