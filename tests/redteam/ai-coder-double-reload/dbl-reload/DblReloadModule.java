package vibe.dbl_reload;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/** Trivial module: each enable is one generation (and one "charge a chaud" line in chat). */
public class DblReloadModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.logger().info("enabled gen " + ctx.generation());
    }
}
