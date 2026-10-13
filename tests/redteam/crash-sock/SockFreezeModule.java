package vibe.sock;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * REDTEAM-CRASH / crash-sock  (severity: CRITICAL)
 *
 * A blocking socket connect on the MAIN server thread. Any native blocking call freezes the whole
 * server: the guard's time budget is only checked at module bytecode back-edges / method entries,
 * and a native block (connect/read, DNS, ReentrantLock.lock, synchronized monitor-enter,
 * InputStream.read, an un-redirected third-party blocking client) never returns control to module
 * bytecode, so Guard.check never runs and the watchdog thread cannot preempt it.
 *
 * Observed: /sock froze the main thread for the whole OS connect timeout (~20-30 s). Paper's
 * watchdog logged "The server has not responded for 10/15/20 seconds! Creating thread dump"; the
 * dump shows sun.nio.ch.Net.connect0(Native Method) <- Socket.connect <- SockFreezeModule <-
 * Guard.invoke. No ticks reached the client for the whole freeze. With a truly black-holed peer or a
 * remote that never replies, the freeze is unbounded -> watchdog crash.
 *
 * This is the single most likely ACCIDENTAL failure: an AI builds a "fetch the weather / call our
 * webhook" feature and does the HTTP/socket call synchronously on the main thread instead of inside
 * ctx.async(...).
 *
 * Suggested fix: the guard cannot interrupt native blocks, so block them at LOAD time (policy):
 * reject, for module code reached on a path that can run on the main thread, direct use of blocking
 * network/IO primitives (java.net.Socket/ServerSocket connect/read/accept, SocketChannel blocking
 * mode, java.io.InputStream.read on System.in, URL/URLConnection.getInputStream, java.util.concurrent
 * .locks.Lock.lock/lockInterruptibly, LockSupport.park(nanos=0), raw `synchronized` on shared
 * monitors) - or require all such calls to go through ctx.async. At minimum, run a dedicated main-
 * thread watchdog that, on a >Ns stall inside a guarded call, interrupts the main thread (Paper's own
 * watchdog only prints; it does not break the block).
 */
public class SockFreezeModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("sock", (s, a) -> {
            Socket so = new Socket();
            so.connect(new InetSocketAddress("10.255.255.1", 80), 0); // non-routable, infinite timeout
        });
        ctx.logger().info("enabled");
    }
}
