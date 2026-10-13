package dev.vibecraft.core;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

import dev.vibecraft.core.ctx.ModuleLogger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;

/**
 * Removes Log4j appenders whose class belongs to an unloaded module generation (a "console in chat"
 * module that never removed its appender would otherwise pin the old generation and spam the console
 * with errors). Kept in its own class: any Log4j linkage problem stays contained in the caller's catch.
 */
final class Log4jSweep {

    private Log4jSweep() {
    }

    static void removeAppendersOf(ClassLoader loader, ModuleLogger logger) {
        if (!(LogManager.getContext(false) instanceof LoggerContext ctx)) {
            return;
        }
        Configuration cfg = ctx.getConfiguration();
        List<LoggerConfig> configs = new ArrayList<>(cfg.getLoggers().values());
        configs.add(cfg.getRootLogger());
        boolean changed = false;
        for (LoggerConfig lc : configs) {
            for (Appender a : new ArrayList<>(lc.getAppenders().values())) {
                if (a.getClass().getClassLoader() == loader) {
                    lc.removeAppender(a.getName());
                    if (cfg instanceof AbstractConfiguration ac) {
                        ac.removeAppender(a.getName());
                    }
                    try {
                        a.stop();
                    } catch (RuntimeException ignored) {
                        // best effort
                    }
                    changed = true;
                    logger.engine(Level.WARNING, "removed a Log4j appender left by the unloaded version (" + a.getName() + ")");
                }
            }
        }
        if (changed) {
            ctx.updateLoggers();
        }
    }
}
