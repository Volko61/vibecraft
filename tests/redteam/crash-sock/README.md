# crash-sock — main-thread native block freezes the server (CRITICAL)

## What it does
Registers `/sock`, whose handler opens a `java.net.Socket` and `connect()`s to a non-routable
address (`10.255.255.1:80`) with an infinite timeout, on the main server thread.

## Repro
1. Deploy to `modules/sock/`, `POST /api/modules/sock/reload`.
2. With a player connected, run `/sock` (or `POST /api/command` body `sock`).

## Expected (safe engine)
The guard aborts the call at its handler budget (~250 ms) and the server keeps ticking.

## Observed
The main thread is stuck in `sun.nio.ch.Net.connect0` (native) for the whole OS connect timeout
(~20–30 s). Paper's watchdog fires: `The server has not responded for 10 seconds! Creating thread
dump`; the dump is `Net.connect0 <- Socket.connect <- SockFreezeModule.lambda$enable$1 <-
Guard.invoke`. No ticks reach clients; `/api/command` times out; `/api/status` goes stale. Recovers
only when the OS connect times out. A black-holed/slow peer makes the freeze unbounded → watchdog
crash.

## Class covered
Every un-redirected native block on the main thread: `ReentrantLock.lock`, `synchronized`
monitor-enter against a lock an async task holds (deadlock — unrecoverable, since unload also needs
the main thread), `InputStream.read`/`System.in`, `URL.getInputStream`, blocking `SocketChannel`,
synchronous HTTP clients. The guard only checks at module bytecode boundaries, so native blocks are
invisible to it.

## Suggested fix
Reject blocking network/IO/lock primitives at load for code reachable on the main thread, or force
them through `ctx.async`. Add a main-thread watchdog that actually interrupts the main thread on a
multi-second stall (Paper's watchdog only prints a dump).

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Rejected at load: `forbidden: Socket is not allowed: modules may not open sockets or servers ...` (socket / server socket / NIO channel types are forbidden). Blocking HTTP / URL / DNS / Mojang profile lookups now throw on the main thread (allowed in ctx.async); Lock.lock / Condition.await / LockSupport.park and timed waits are bounded by the budget there. Not covered: `synchronized` on a monitor held by another thread (cannot be redirected).
Probe module on the sandbox: `HttpClient.send` / `URL.openStream` to 10.255.255.1 in a command -> refused in ~0 ms with
"would freeze the whole server", `ReentrantLock.lock()` held forever by an async task -> the command is interrupted at its 250 ms budget.
