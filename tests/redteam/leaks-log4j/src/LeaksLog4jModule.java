package vibe.leaks_log4j;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.bukkit.Bukkit;

/**
 * REDTEAM-LEAKS: "console in chat for ops" with a Log4j appender added to the root logger (Paper logs with
 * Log4j). Not removed in disable(). /lklog4j prints how many appenders are attached.
 */
public class LeaksLog4jModule implements VibeModule {
    static final class Relay extends AbstractAppender {
        final int gen;

        Relay(int gen) {
            super("leaks-relay-v" + gen, null, null, true, Property.EMPTY_ARRAY);
            this.gen = gen;
        }

        @Override
        public void append(LogEvent event) {
            String m = event.getMessage().getFormattedMessage();
            if (m.contains("relayme")) {
                Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage("[console v" + gen + "] " + m));
            }
        }
    }

    @Override
    public void enable(ModuleContext ctx) {
        Relay r = new Relay(ctx.generation());
        r.start();
        ((Logger) LogManager.getRootLogger()).addAppender(r);
        ctx.command("lklog4j", (s, a) -> s.sendMessage("root appenders: " + ((Logger) LogManager.getRootLogger()).getAppenders().keySet()));
        ctx.logger().info("log4j relay v" + ctx.generation() + " installed");
    }
}
