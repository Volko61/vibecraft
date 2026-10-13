# fixer-killed-entry (regression for leaks-jul / leaks-callback root cause)

What: a module adds a java.util.logging Handler through a method reference (bypasses the `Logger.addHandler`
redirect) on a private logger (not swept on unload), so old generations' handlers really stay registered.
`/fxkilled` logs one line on that logger and prints which generations' handlers ran.

Steps (sandbox API): copy `src/` to `modules/fixer-killed-entry/`, reload, reload again 2x with `?force=1`,
then `POST /api/command fxkilled`.

Expected (after the fix): `handlers=3 ran=[v3]` - the stale v1/v2 handlers are called by the JDK, enter their
`publish` method, and return immediately (no ModuleKilledError thrown into the logging code, nothing in the
console, no crash). Before the fix each stale handler threw `ModuleKilledError` (an Error) into the caller:
inside the next generation's enable() it made the reload and the rollback fail, from server code it crashed
the server (see ../leaks-jul).

Observed (fixer sandbox, 2026-10-13): `handlers=3 ran=[v3]`, no error in the console or in the module's logs.
