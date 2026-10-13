# crash-bignbt — oversized item NBT disconnects players + persistent lockout (HIGH)

256 lore lines x 60000 chars (~15 MB) passes Paper's 256-line lore check but overflows the 8 MB
clientbound packet cap.

## Repro
Deploy, reload, connect a player, `/bignbt`. Player dropped with
`PacketTooLargeException: ClientboundContainerSetSlotPacket is 15360781. Max is 8388608`.
The item persists in `world/players/data/<uuid>.dat` -> the player is kicked on every rejoin
(persistent lockout) until the data file is deleted.

## Fix
Cap serialized NBT/lore size of items a module creates/gives (threshold well under 8 MB).

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

`/bignbt` -> `IllegalArgumentException: item data too large (15000 KB, max 64 KB): players holding it would be disconnected` (ItemStack.setItemMeta / editMeta / lore / setData redirected; names and TextDisplay text capped at 32 KB); the bot stays connected.
