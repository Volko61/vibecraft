package dev.vibecraft.core.ctx;

import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * Logger {@code VibeCraft/<id>}: every record goes to the module's ring buffer; console output is
 * rate-limited (token bucket) so a module logging every tick cannot flood the server console.
 */
public final class ModuleLogger extends Logger {

    private static final Formatter FORMAT = new SimpleFormatter();
    private static final double RATE_PER_SEC = 20;
    private static final double BURST = 40;

    private final String id;
    private final Logger console;
    private final LogRing ring;

    private double tokens = BURST;
    private long lastRefill = System.nanoTime();
    private long suppressed;
    private long lastSuppressNotice;

    public ModuleLogger(String id, Logger console, LogRing ring) {
        super("VibeCraft/" + id, null);
        this.id = id;
        this.console = console;
        this.ring = ring;
        setLevel(Level.ALL);
        setUseParentHandlers(false);
    }

    public LogRing ring() {
        return ring;
    }

    @Override
    public void log(LogRecord record) {
        if (record == null || !isLoggable(record.getLevel())) {
            return;
        }
        String msg;
        try {
            msg = FORMAT.formatMessage(record);
        } catch (RuntimeException e) {
            msg = String.valueOf(record.getMessage());
        }
        if (record.getThrown() != null) {
            msg = msg + " (" + record.getThrown() + ")";
        }
        msg = dev.vibecraft.ext.SecretStore.scrub(msg); // never let an API key reach logs / dashboard
        ring.add(record.getLevel().getName(), msg);
        Level level = record.getLevel().intValue() < Level.INFO.intValue() ? Level.INFO : record.getLevel();
        if (!acquire()) {
            return;
        }
        console.log(level, "[" + id + "] " + msg);
    }

    /** Engine message: ring + console, not rate limited. */
    public void engine(Level level, String msg) {
        ring.add(level.getName(), msg);
        console.log(level, "[" + id + "] " + msg);
    }

    /** Engine message to the ring only. */
    public void ringOnly(Level level, String msg) {
        ring.add(level.getName(), msg);
    }

    private synchronized boolean acquire() {
        long now = System.nanoTime();
        tokens = Math.min(BURST, tokens + (now - lastRefill) / 1e9 * RATE_PER_SEC);
        lastRefill = now;
        if (tokens >= 1) {
            tokens -= 1;
            if (suppressed > 0 && now - lastSuppressNotice > 5_000_000_000L) {
                long n = suppressed;
                suppressed = 0;
                lastSuppressNotice = now;
                console.warning("[" + id + "] " + n + " log line(s) not shown in the console (too many; see the module logs)");
            }
            return true;
        }
        suppressed++;
        return false;
    }
}
