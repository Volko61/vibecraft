# leaks-store: ModuleStore edge cases (data loss, inconsistent reads, main-thread stalls)

`/lkstore <case>` (see the class javadoc). The store survives reloads in memory (one ModuleStoreImpl per
module record), so effects that depend on re-reading the file appear after a server restart; the repro
forces a re-read by moving the module directory out and back in (new record = new store instance).

## 1. Non-YAML values wipe the whole store at the next restart (high, silent data loss)
`store.set("vals.instant", Instant.now())` (also `StringBuilder`, `Optional`, any JDK bean): `sanitize()` lets
it through, `get()` returns it fine while the server runs, `save()` writes `instant: !!java.time.Instant {}`
(the value itself is lost) and at the next load Bukkit's YAML loader refuses the global tag:
`store.yml is corrupt, starting empty (backup kept): Global tag is not allowed: tag:yaml.org,2002:java.time.Instant`
-> every key of the module (scores, counters...) reads as default; the next save overwrites the file
(backup `store.yml.corrupt-<ts>`). `AtomicInteger` is saved as `!!float '5'` (loads, as a Double).
Fix: `sanitize()` must reject (clear IllegalArgumentException) or convert anything that is not
String / boxed primitive / BigInteger / BigDecimal / Boolean / List / Map / ConfigurationSerializable
(convert `TemporalAccessor`, `CharSequence`, `Optional`, `UUID` to strings; `AtomicXxx` to numbers); on load,
fall back to a lenient parse (drop unknown tags) rather than starting empty.

## 2. Maps are not sections in memory (medium)
`store.set("md", Map.of("a.b", 1, "c", Map.of("d", 2)))` then `getInt("md.c.d", -1)` -> -1,
`contains("md.c.d")` -> false, `keys("md")` -> [] (a raw map, not a section) until a restart, after which
it becomes sections (and the key `a.b` turns into a -> b). Fix: in `set`, store Maps through
`createSection(path, map)` so reads behave the same before and after a restart.

## 3. Big stores stall the main thread (medium)
150 000 entries = 4.3 MB YAML: a dirty store is dumped on the main thread by every unload
(reload swap time 400-460 ms instead of 1-3 ms); `ctx.store().save()` in a command takes ~550 ms on the main
thread and is not caught by the 250 ms budget (host code); the store is first loaded lazily inside enable()
on the main thread (1.3 s cold for 4.3 MB, counted against the 2 s enable budget).
`/lkstore contend 10`: an async task saving while the main thread reads each tick -> worst main-thread
`getInt()` = **567 ms** (the store monitor is held during the whole YAML dump; the 60 s autosave on the
VibeCraft-io thread does the same to any module reading its store on the main thread).
Fix: serialize a snapshot outside the lock (deep-copy the value tree under the lock, dump outside), do the
unload save on the io executor, load the store off-thread before enable, warn above ~1 MB.

## 4. get() returns live internal objects (low)
`get("lst")` returns the store's own ArrayList: mutating it (here from an async task) bypasses the lock and the
dirty flag: the change is persisted only if something else dirties the store later; a concurrent
`saveToString()` can throw ConcurrentModificationException, and `saveIfDirty()` clears `dirty` BEFORE
serializing, so a failed autosave loses the pending changes until the next `set()` (code reading; the CME was
not hit in 50 tries). Fix: return copies from `get()` (as getStringList already does) and only clear `dirty`
after a successful write.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

1: Instant / StringBuilder / Optional stored as strings, AtomicInteger as a number, unknown types throw; a store.yml with Java tags is loaded leniently (other keys kept, backup made). 2: maps are stored as sections (`getInt(md.c.d)` = 2 before and after a re-read). 3: 4.3 MB store: reload swaps 6-32 ms (save on the io thread, tree copied under the lock, serialized outside), worst main-thread get() during `contend 10` = 31 ms (was 567 ms), warning above 1 MB. 4: get() returns copies; dirty is cleared only after a successful write.
