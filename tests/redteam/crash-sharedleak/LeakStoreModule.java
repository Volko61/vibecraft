package vibe.leakstore;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
/**
 * REDTEAM-CRASH / crash-sharedleak (severity: HIGH) - writer half.
 * ModuleManagerImpl.removeSharedFrom() only drops shared() entries whose VALUE's class was loaded by
 * the module's own ClassLoader. JDK/Bukkit-typed values (byte[], ArrayList, String, ItemStack, ...)
 * - exactly the types the workspace docs tell modules to put in shared() - are NEVER reclaimed. They
 * outlive reload/disable/removal of the module and live until a full server restart.
 * Proven: after this module is deleted (API 404), a separate reader still finds "leak.bytes" present
 * with size 8388608. A module that caches growing data in shared() leaks unbounded host memory ->
 * eventual OutOfMemoryError (observed crashing the server when combined with an allocation task).
 * Suggested fix: scope shared() per-generation-group with explicit ownership and clear ALL entries a
 * generation wrote on unload (track keys written via the module's context), regardless of value type;
 * or document that shared() is never GC'd and cap its total size.
 */
public class LeakStoreModule implements VibeModule {
    @Override public void enable(ModuleContext ctx) {
        ctx.shared().put("leak.bytes", new byte[8 * 1024 * 1024]);
        ctx.logger().info("leak set");
    }
}
