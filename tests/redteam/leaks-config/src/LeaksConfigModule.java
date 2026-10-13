package vibe.leaks_config;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/** REDTEAM-LEAKS: prints what ctx.config() sees (/lkcfg). */
public class LeaksConfigModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        String msg = ctx.config().getString("message", "default");
        int power = ctx.config().getInt("power", 1);
        ctx.logger().info("config v" + ctx.generation() + ": message=" + msg + " power=" + power);
        ctx.command("lkcfg", (s, a) -> s.sendMessage("v" + ctx.generation() + " message=" + msg + " power=" + power + " keys=" + ctx.config().getKeys(true)));
    }
}
