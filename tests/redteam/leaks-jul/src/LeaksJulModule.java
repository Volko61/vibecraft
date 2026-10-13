package vibe.leaks_jul;

import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;

/**
 * REDTEAM-LEAKS: "show server warnings to ops in chat" with a java.util.logging Handler added to the root
 * logger (VibeCraft's plugin logger propagates to it). Not removed in disable().
 */
public class LeaksJulModule implements VibeModule {

    static final class OpsRelay extends Handler {
        final int gen;

        OpsRelay(int gen) {
            this.gen = gen;
        }

        @Override
        public void publish(LogRecord r) {
            if (r.getLevel().intValue() >= Level.WARNING.intValue()) {
                String msg = "[relay v" + gen + "] " + r.getMessage();
                Bukkit.getOnlinePlayers().stream().filter(p -> p.isOp()).forEach(p -> p.sendMessage(msg));
            }
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Override
    public void enable(ModuleContext ctx) {
        // first variant: Bukkit.getLogger().addHandler(...) (crashes the shutdown); this one crashes the server at the first reload
        java.util.logging.Logger.getLogger("").addHandler(new OpsRelay(ctx.generation()));
        ctx.logger().info("relay v" + ctx.generation() + " installed; handlers on root logger: " + java.util.logging.Logger.getLogger("").getHandlers().length);
    }
}
