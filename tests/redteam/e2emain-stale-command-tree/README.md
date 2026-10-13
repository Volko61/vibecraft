# e2emain-stale-command-tree

**What it does**: `repro.mjs` keeps VibeBot online, creates 7 tiny modules at once (`rt-tree-1..7`, one command
each, written to `workspace/modules` like several AI sessions finishing at the same time), waits until all of them
are ENABLED + 1.5 s, and checks the bot's latest `declare_commands` packet (the client's command tree, used for
tab completion and red "unknown command" highlighting). Then it deletes the 7 directories at once and checks the
tree again. It repeats for `--rounds` rounds and deletes everything at the end.

```bash
node tests/redteam/e2emain-stale-command-tree/repro.mjs --rounds 10      # main server (25565 / API 8765)
```

**Expected**: after things settle, the client's command tree matches the server's dispatcher: all 7 commands after the
add phase, none after the delete phase.

**Observed** (main server, 2026-10-08, plugin of commit e3c1132):

* 1 of 10 rounds: after the add phase, `rt-tree-7` was **missing from the client tree** although the server ran
  `/rttree7`. It stays like that until some later command change triggers a new `updateCommands()` (nothing re-sends
  it on its own).
* In `tools/examples-check.mjs` (7 example modules deleted at once) the tree still contained `countdown` and
  `vibe:countdown` after the server had removed them (1 of 2 runs).
* About 2 `java.util.ConcurrentModificationException` per round in the server console, logged as
  `ERROR Caught previously unhandled exception in Paper Async Command Builder Thread Pool - N`
  (`CommandNode.hashCode` -> `Commands.fillUsableCommands` -> `Commands.sendAsync`). They also show up during the
  e2e `parallel-reloads` scenario (3 in the first 3 full runs).

**Why**: `ModuleManagerImpl.flushPlayerUpdates()` calls `Player#updateCommands()` at the start of every tick in which
a module changed commands. Paper builds the tree on a cached thread pool (a new thread per build, see the growing
`Thread Pool - N` numbers), so when modules finish loading or unloading in consecutive ticks, (a) a build walks the
brigadier root while the main thread adds or removes nodes (CME: that update is lost), and (b) two builds for the same
player run at the same time and can finish in the wrong order, so the older snapshot reaches the client last.

**Impact**: the server always executes the right commands. On the client, a just-added command can show as unknown
(red) with no tab completion, or a removed one can still be suggested, until the next module change or a rejoin.
The console also shows ERROR stack traces, which looks bad if it is on the projector.

**Suggested fix**: (1) debounce the flush: only call `updateCommands()` once no command change happened for about
2-3 ticks; (2) always send one more `updateCommands()` about 10-20 ticks after the last change. That last build runs
alone, so whatever an earlier CME or out-of-order build broke gets fixed.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

`repro.mjs --rounds 10` (+ `--api/--mc-port/--modules-dir/--rcon-dir` of the sandbox): 0/10 rounds stale, 2-5 `declare_commands` per phase (debounced send once commands are quiet for 3 ticks + one re-send 20 ticks later). The debounce alone still left ~1 ConcurrentModificationException per 3 rounds (2 in 6 rounds): the trees were sent at tick START, so Paper's async build raced the module swaps run by the scheduler in the same tick. They are now sent at tick END (`ServerTickEndEvent`), while the main thread waits for the next tick: 1 CME in 10 rounds (the very first round, cold JIT right after startup), 0 during the whole e2e suite (parallel-reloads included). Paper builds trees on its own thread pool, so a rare CME cannot be ruled out; the re-send 20 ticks later always repairs the client tree. The repro now ops/whitelists the bot through RCON (`--rcon-dir`) because the API refuses admin commands.
