# leaks-world: a world created with a module ChunkGenerator CRASHES THE SERVER after a reload (critical)

Module: `/lkworld create` -> `new WorldCreator("leaks_void").generator(new VoidGen(gen)).createWorld()`
("void / sky world for a minigame"). createWorld itself blocked the main thread 1.4 s inside the guarded
command without any error (host code is not budgeted).

Steps: load, `/lkworld create`, reload the module (v2), then make the server generate a new chunk in that
world: `execute in minecraft:leaks_void run forceload add 800 800 832 832` (a player exploring does the same).

Expected: the world keeps working (or is unloaded together with the module).

Observed (`crash-excerpt.txt`): `[ChunkTaskScheduler] Chunk system error ... CompletionException:
ModuleKilledError: module 'leaks-world' v1 was unloaded` -> `unrecoverableChunkSystemFailure` -> "Chunk
system crash propagated to tick()" -> crash report -> server stopped. Any exception thrown by a LIVE module
generator crashes the server the same way: the generator never runs under the guard.

Suggested fix: redirect `WorldCreator.generator(...)` / `biomeProvider(...)` (and `Bukkit.createWorld`) to
wrap module generators / biome providers / populators in host delegates that catch Throwable and fall back
to an empty chunk (and become empty once the generation is killed); or forbid world creation by policy and
say so in CLAUDE.md ("use the existing world"). Track created worlds and unload them (moving players out)
when the module is removed.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Rejected at load: `forbidden: custom world generators (ChunkGenerator) are not allowed in modules ...` (ChunkGenerator / BiomeProvider / BlockPopulator subclasses).
