# fixer-menu-noholder (regression for leaks-gui, holder-less variant)

Menu created with `Bukkit.createInventory(null, 27, title)` (no InventoryHolder), clicks cancelled by comparing
inventories. `/fxmenu <player>` opens it; reload the module while it is open; shift-click slot 13.

Expected (after the fix): the menu is closed by the engine on unload (inventories created by the generation are
tracked through a `createInventory` redirect), the player cannot take the diamonds
(`execute if items entity <player> container.* minecraft:diamond` -> Test failed).
