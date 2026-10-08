package dev.vibecraft.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Settings of VibeReconnect, read from {@code plugins/vibereconnect/config.properties} (UTF-8).
 * Missing file -> the bundled defaults are written there. Missing keys -> defaults.
 */
public final class ReconnectConfig {

    public final String mainServer;
    public final String limboServer;
    public final long pingIntervalMs;
    public final long pingTimeoutMs;
    public final int requiredSuccessfulPings;
    public final long minLimboMs;
    public final long forceReturnAfterMs;
    public final long returnStaggerMs;
    public final long returnBackoffMaxMs;
    public final List<String> deliberateKickPatterns;
    public final String title;
    public final String subtitle;
    public final String actionbarWaiting;
    public final String actionbarReturning;
    public final String backTitle;
    public final String backSubtitle;
    public final String returnFailedMessage;
    public final String redirectMessage;
    public final boolean freezeGuard;
    public final long freezeThresholdMs;
    public final String statusFile;
    public final boolean debug;

    private ReconnectConfig(Properties p) {
        mainServer = str(p, "main-server", "main");
        limboServer = str(p, "limbo-server", "limbo");
        pingIntervalMs = Math.max(250, num(p, "ping-interval-ms", 2000));
        pingTimeoutMs = Math.max(200, num(p, "ping-timeout-ms", 1500));
        requiredSuccessfulPings = (int) Math.max(1, num(p, "required-successful-pings", 1));
        minLimboMs = Math.max(0, num(p, "min-limbo-ms", 2000));
        forceReturnAfterMs = Math.max(0, num(p, "force-return-after-ms", 15000));
        returnStaggerMs = Math.max(0, num(p, "return-stagger-ms", 250));
        returnBackoffMaxMs = Math.max(1000, num(p, "return-backoff-max-ms", 30000));
        List<String> patterns = new ArrayList<>();
        for (String s : str(p, "deliberate-kick-patterns", "").split(",")) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (!t.isEmpty()) patterns.add(t);
        }
        deliberateKickPatterns = List.copyOf(patterns);
        title = str(p, "title", "<gold><bold>Le serveur redémarre…");
        subtitle = str(p, "subtitle", "<gray>ne bouge pas, tu reviens automatiquement");
        actionbarWaiting = str(p, "actionbar-waiting", "<yellow>Serveur principal hors ligne - attente <white><seconds>s</white>");
        actionbarReturning = str(p, "actionbar-returning", "<green>Serveur principal en ligne - retour en cours...");
        backTitle = str(p, "back-title", "<green><bold>De retour !");
        backSubtitle = str(p, "back-subtitle", "<gray>merci d'avoir patiente (<seconds>s)");
        returnFailedMessage = str(p, "return-failed-message", "<gray>Retour impossible pour l'instant (<reason>), nouvel essai dans <seconds>s...");
        redirectMessage = str(p, "redirect-message", "<gray>⏸ Serveur principal indisponible (<reason>) : tu patientes ici, retour automatique.");
        freezeGuard = Boolean.parseBoolean(str(p, "freeze-guard", "true"));
        freezeThresholdMs = Math.max(3000, num(p, "freeze-threshold-ms", 10000));
        statusFile = str(p, "status-file", "status.json");
        debug = Boolean.parseBoolean(str(p, "debug", "false"));
    }

    public static ReconnectConfig load(Path dataDir) throws IOException {
        Files.createDirectories(dataDir);
        Path file = dataDir.resolve("config.properties");
        if (!Files.exists(file)) {
            try (InputStream in = ReconnectConfig.class.getResourceAsStream("/config.properties")) {
                if (in != null) Files.copy(in, file);
            }
        }
        Properties p = new Properties();
        if (Files.exists(file)) {
            try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                p.load(r);
            }
        }
        return new ReconnectConfig(p);
    }

    private static String str(Properties p, String key, String def) {
        String v = p.getProperty(key);
        return v == null ? def : v.trim();
    }

    private static long num(Properties p, String key, long def) {
        try {
            return Long.parseLong(str(p, key, Long.toString(def)));
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
