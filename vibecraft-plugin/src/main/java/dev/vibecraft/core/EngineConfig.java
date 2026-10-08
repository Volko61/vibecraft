package dev.vibecraft.core;

import java.io.File;

import org.bukkit.configuration.file.FileConfiguration;

/** Engine settings from plugins/VibeCraft/config.yml (paths relative to the server directory). */
public record EngineConfig(File modulesDir, File dataDir, boolean autoReload, long pollIntervalMs, long debounceMs,
                           long handlerBudgetMs, long enableBudgetMs, long tickBudgetMs, int errorBurstLimit,
                           long errorBurstWindowMs) {

    public static EngineConfig from(FileConfiguration c) {
        File server = new File(".").getAbsoluteFile();
        return new EngineConfig(
                resolve(server, c.getString("modules-dir", "../workspace/modules")),
                resolve(server, c.getString("data-dir", "../workspace/data")),
                c.getBoolean("auto-reload", true),
                clamp(c.getLong("poll-interval-ms", 750), 100, 60_000),
                clamp(c.getLong("debounce-ms", 1200), 0, 60_000),
                clamp(c.getLong("guard.handler-budget-ms", 250), 10, 60_000),
                clamp(c.getLong("guard.enable-budget-ms", 5000), 50, 60_000),
                clamp(c.getLong("guard.tick-budget-ms", 2000), 100, 60_000),
                (int) clamp(c.getLong("guard.error-burst-limit", 25), 1, 1_000_000),
                clamp(c.getLong("guard.error-burst-window-ms", 10_000), 100, 3_600_000));
    }

    private static File resolve(File base, String path) {
        File f = new File(path);
        if (!f.isAbsolute()) {
            f = new File(base, path);
        }
        return f.toPath().toAbsolutePath().normalize().toFile();
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
}
