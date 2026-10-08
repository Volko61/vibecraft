package dev.vibecraft.proxy;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * VibeReconnect: safety net for VibeCraft live-coding sessions.
 *
 * <p>When the main backend crashes, is killed or restarts, players are moved to a tiny "limbo"
 * backend instead of being disconnected. The main backend is pinged every couple of seconds and,
 * as soon as it answers again, every waiting player is sent back (staggered).</p>
 */
@Plugin(id = "vibereconnect", name = "VibeReconnect", version = "1.0.0",
        description = "Keeps players connected (limbo) while the VibeCraft main server restarts",
        authors = {"VibeCraft"})
public final class VibeReconnect {

    /** Player waiting in limbo for the main server. */
    static final class Waiting {
        final long since;
        final String reason;
        /** Main server was seen down (failed ping) since this player arrived in limbo. */
        volatile boolean mainSeenDown;
        volatile int attempts;
        volatile long nextAttemptAt;
        volatile boolean inFlight;
        volatile String lastFailure;
        volatile long attemptStartedAt;
        volatile long lastFailureAt;

        Waiting(long since, String reason, boolean mainSeenDown) {
            this.since = since;
            this.reason = reason;
            this.mainSeenDown = mainSeenDown;
        }
    }

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final long startedAt = System.currentTimeMillis();

    private volatile ReconnectConfig config;
    private volatile ScheduledTask pingTask;
    private volatile ScheduledTask freezeTask;
    private final FreezeGuard freezeGuard;
    volatile long lastFreezeAt;
    private volatile long lastEvacuationAt;
    volatile long lastMainSilenceMs = -1;
    private final AtomicBoolean pinging = new AtomicBoolean();

    final Map<UUID, Waiting> waiting = new ConcurrentHashMap<>();

    // main server health (guarded by "this" for transitions)
    volatile boolean mainUp;
    volatile boolean mainEverPinged;
    volatile int consecutiveOk;
    volatile int consecutiveFail;
    volatile long mainStateSince = System.currentTimeMillis();
    volatile long lastPingAt;
    volatile long lastLatencyMs = -1;
    volatile String lastPingError;
    volatile boolean limboUp;
    volatile long limboLastPingAt;
    private long tickCount;

    final AtomicLong statRedirects = new AtomicLong();
    final AtomicLong statInitialToLimbo = new AtomicLong();
    final AtomicLong statReturns = new AtomicLong();
    final AtomicLong statReturnFailures = new AtomicLong();
    final AtomicLong statDeliberateKicks = new AtomicLong();
    final AtomicLong statFreezeEvacuations = new AtomicLong();

    @Inject
    public VibeReconnect(ProxyServer proxy, Logger logger, @DataDirectory Path dataDir) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
        this.freezeGuard = new FreezeGuard(logger);
    }

    // ------------------------------------------------------------------ lifecycle

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        reloadConfig();
        proxy.getCommandManager().register(
                proxy.getCommandManager().metaBuilder("vibeproxy").aliases("vproxy").plugin(this).build(),
                new VibeProxyCommand(this));
        logger.info("VibeReconnect pret : principal='{}', limbo='{}', ping toutes les {} ms",
                config.mainServer, config.limboServer, config.pingIntervalMs);
        if (main().isEmpty()) logger.warn("Serveur '{}' introuvable dans velocity.toml [servers] !", config.mainServer);
        if (limbo().isEmpty()) logger.warn("Serveur '{}' introuvable dans velocity.toml [servers] !", config.limboServer);
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        ScheduledTask t = pingTask;
        if (t != null) t.cancel();
        ScheduledTask f = freezeTask;
        if (f != null) f.cancel();
    }

    /** (Re)loads the config file and restarts the ping loop. Returns an error message or null. */
    synchronized String reloadConfig() {
        try {
            config = ReconnectConfig.load(dataDir);
        } catch (IOException e) {
            logger.error("Impossible de lire config.properties", e);
            if (config == null) {
                try {
                    config = ReconnectConfig.load(dataDir.resolve("defaults-fallback"));
                } catch (IOException ignored) {
                    // cannot happen in practice; leave config null -> NPE visible in logs
                }
            }
            return e.toString();
        }
        ScheduledTask old = pingTask;
        if (old != null) old.cancel();
        pingTask = proxy.getScheduler().buildTask(this, this::tick)
                .delay(200, TimeUnit.MILLISECONDS)
                .repeat(config.pingIntervalMs, TimeUnit.MILLISECONDS)
                .schedule();
        ScheduledTask oldFreeze = freezeTask;
        if (oldFreeze != null) oldFreeze.cancel();
        freezeTask = proxy.getScheduler().buildTask(this, this::checkFreeze)
                .delay(1, TimeUnit.SECONDS)
                .repeat(1, TimeUnit.SECONDS)
                .schedule();
        return null;
    }

    ReconnectConfig config() {
        return config;
    }

    ProxyServer proxy() {
        return proxy;
    }

    Optional<RegisteredServer> main() {
        return proxy.getServer(config.mainServer);
    }

    Optional<RegisteredServer> limbo() {
        return proxy.getServer(config.limboServer);
    }

    private boolean is(RegisteredServer server, String name) {
        return server != null && server.getServerInfo().getName().equalsIgnoreCase(name);
    }

    boolean isOnLimbo(Player p) {
        Optional<ServerConnection> cur = p.getCurrentServer();
        return cur.isPresent() && is(cur.get().getServer(), config.limboServer);
    }

    boolean isOnMain(Player p) {
        Optional<ServerConnection> cur = p.getCurrentServer();
        return cur.isPresent() && is(cur.get().getServer(), config.mainServer);
    }

    // ------------------------------------------------------------------ events

    /** Player joins the proxy: if the main server is known to be down, go straight to limbo. */
    @Subscribe(priority = -100) // run late: we want the final word on the result
    public void onChooseInitial(PlayerChooseInitialServerEvent event) {
        Optional<RegisteredServer> limbo = limbo();
        Optional<RegisteredServer> initial = event.getInitialServer();
        if (limbo.isEmpty() || initial.isEmpty() || !is(initial.get(), config.mainServer)) return;
        boolean knownDown = mainEverPinged && !mainUp
                && System.currentTimeMillis() - lastPingAt < config.pingIntervalMs * 3 + config.pingTimeoutMs;
        if (knownDown) {
            event.setInitialServer(limbo.get());
            waiting.put(event.getPlayer().getUniqueId(),
                    new Waiting(System.currentTimeMillis(), "serveur principal hors ligne a la connexion", true));
            statInitialToLimbo.incrementAndGet();
            logger.info("{} arrive pendant que '{}' est hors ligne -> limbo", event.getPlayer().getUsername(), config.mainServer);
        }
    }

    @Subscribe(priority = -100) // run late: we want the final word on the result
    public void onKick(KickedFromServerEvent event) {
        ReconnectConfig cfg = config;
        Player player = event.getPlayer();
        if (!is(event.getServer(), cfg.mainServer)) return; // only the main server is protected
        Optional<RegisteredServer> limboOpt = limbo();
        if (limboOpt.isEmpty()) return; // misconfigured -> Velocity default behaviour
        RegisteredServer limbo = limboOpt.get();
        Optional<Component> reason = event.getServerKickReason();
        String reasonText = reason.map(VibeReconnect::plain).orElse("connexion perdue");
        boolean deliberate = isDeliberate(reason.orElse(null), cfg);

        if (event.kickedDuringServerConnect()) {
            if (isOnLimbo(player)) {
                // a return attempt failed: stay in limbo, retry later (with backoff)
                Waiting w = waiting.computeIfAbsent(player.getUniqueId(),
                        id -> new Waiting(System.currentTimeMillis(), "retour echoue", false));
                long delay = registerFailure(w, reasonText, deliberate);
                event.setResult(KickedFromServerEvent.Notify.create(mm.deserialize(cfg.returnFailedMessage,
                        Placeholder.unparsed("reason", shorten(reasonText, 80)),
                        Placeholder.unparsed("seconds", Long.toString(Math.max(1, delay / 1000))))));
                return;
            }
            if (player.getCurrentServer().isEmpty()) {
                // initial connection to main failed
                if (deliberate) {
                    statDeliberateKicks.incrementAndGet();
                    event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason.orElse(Component.text(reasonText))));
                    return;
                }
                waiting.put(player.getUniqueId(), new Waiting(System.currentTimeMillis(), "connexion initiale: " + reasonText, true));
                statInitialToLimbo.incrementAndGet();
                event.setResult(KickedFromServerEvent.RedirectPlayer.create(limbo, redirectMessage(reasonText)));
                logger.info("{} n'a pas pu rejoindre '{}' ({}) -> limbo", player.getUsername(), cfg.mainServer, reasonText);
            }
            return; // switching from another server: Velocity default (notify)
        }

        if (deliberate) {
            statDeliberateKicks.incrementAndGet();
            logger.info("{} expulse volontairement de '{}' ({}) -> deconnexion normale", player.getUsername(), cfg.mainServer, reasonText);
            event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason.orElse(Component.text(reasonText))));
            return;
        }

        // crash / restart / connection lost: keep the player, park him in limbo
        waiting.put(player.getUniqueId(), new Waiting(System.currentTimeMillis(), reasonText, false));
        statRedirects.incrementAndGet();
        markMainSuspect();
        event.setResult(KickedFromServerEvent.RedirectPlayer.create(limbo, redirectMessage(reasonText)));
        logger.info("{} perd '{}' ({}) -> limbo", player.getUsername(), cfg.mainServer, reasonText);
    }

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        ReconnectConfig cfg = config;
        Optional<ServerConnection> cur = player.getCurrentServer();
        if (cur.isEmpty()) return;
        if (is(cur.get().getServer(), cfg.limboServer)) {
            Waiting w = waiting.computeIfAbsent(player.getUniqueId(),
                    id -> new Waiting(System.currentTimeMillis(), "arrive dans le limbo", !mainUp));
            player.showTitle(Title.title(mm.deserialize(cfg.title), mm.deserialize(cfg.subtitle),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMinutes(10), Duration.ofMillis(500))));
            player.sendActionBar(waitingBar(w));
            freezeGuard.detach(player.getUniqueId());
        } else if (is(cur.get().getServer(), cfg.mainServer)) {
            freezeGuard.attach(player, cur.get());
            Waiting w = waiting.remove(player.getUniqueId());
            boolean fromLimbo = event.getPreviousServer() != null && is(event.getPreviousServer(), cfg.limboServer);
            if (w != null || fromLimbo) {
                long secs = w == null ? 0 : (System.currentTimeMillis() - w.since) / 1000;
                player.clearTitle();
                player.showTitle(Title.title(mm.deserialize(cfg.backTitle),
                        mm.deserialize(cfg.backSubtitle, Placeholder.unparsed("seconds", Long.toString(secs))),
                        Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1800), Duration.ofMillis(600))));
                logger.info("{} est de retour sur '{}' apres {} s", player.getUsername(), cfg.mainServer, secs);
            }
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
        freezeGuard.detach(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ frozen main server

    /** Every second: if every player on main got nothing from it for freeze-threshold-ms -> limbo. */
    private void checkFreeze() {
        try {
            ReconnectConfig cfg = config;
            Optional<RegisteredServer> mainOpt = main();
            Optional<RegisteredServer> limboOpt = limbo();
            if (!cfg.freezeGuard || !freezeGuard.available() || mainOpt.isEmpty() || limboOpt.isEmpty()) return;
            List<Player> onMain = List.copyOf(mainOpt.get().getPlayersConnected());
            if (onMain.isEmpty()) {
                lastMainSilenceMs = -1;
                return;
            }
            long silence = freezeGuard.minSilenceMs(onMain);
            lastMainSilenceMs = silence;
            if (silence < cfg.freezeThresholdMs) return;
            long now = System.currentTimeMillis();
            if (now - lastEvacuationAt < cfg.freezeThresholdMs) return; // evacuation already running
            lastEvacuationAt = now;
            if (!limboUp) {
                logger.warn("Serveur principal fige depuis {} s mais le limbo est hors ligne : evacuation impossible", silence / 1000);
                return;
            }
            lastFreezeAt = now;
            statFreezeEvacuations.incrementAndGet();
            markMainSuspect();
            logger.warn("Serveur principal '{}' FIGE : aucun paquet depuis {} s -> evacuation de {} joueur(s) vers le limbo",
                    cfg.mainServer, silence / 1000, onMain.size());
            RegisteredServer limbo = limboOpt.get();
            int i = 0;
            for (Player p : onMain) {
                waiting.put(p.getUniqueId(), new Waiting(now, "serveur principal fige", false));
                freezeGuard.detach(p.getUniqueId());
                Runnable move = () -> p.createConnectionRequest(limbo).connect().whenComplete((r, err) -> {
                    if (err == null && r != null && r.isSuccessful()) {
                        statRedirects.incrementAndGet();
                        p.sendMessage(redirectMessage("serveur principal fige"));
                    } else {
                        logger.warn("Evacuation de {} vers le limbo impossible : {}", p.getUsername(),
                                err != null ? describe(err) : r == null ? "?" : r.getStatus());
                    }
                });
                long delay = i++ * 100L;
                if (delay == 0) move.run();
                else proxy.getScheduler().buildTask(this, move).delay(delay, TimeUnit.MILLISECONDS).schedule();
            }
        } catch (Throwable t) {
            logger.error("Erreur dans la detection de gel", t);
        }
    }

    // ------------------------------------------------------------------ health loop

    private void markMainSuspect() {
        synchronized (this) {
            consecutiveOk = 0;
        }
    }

    private void tick() {
        ReconnectConfig cfg = config;
        Optional<RegisteredServer> mainOpt = main();
        if (mainOpt.isEmpty()) return;
        if ((tickCount++ % 3) == 0) pingLimbo(cfg);
        if (!pinging.compareAndSet(false, true)) return; // previous ping still running
        long t0 = System.nanoTime();
        PingOptions opts = PingOptions.builder().timeout(cfg.pingTimeoutMs, TimeUnit.MILLISECONDS).build();
        try {
            mainOpt.get().ping(opts)
                    .orTimeout(cfg.pingTimeoutMs + 1000, TimeUnit.MILLISECONDS)
                    .whenComplete((ping, err) -> {
                        try {
                            long latency = (System.nanoTime() - t0) / 1_000_000;
                            onMainPing(err == null && ping != null, latency, err);
                        } catch (Throwable t) {
                            logger.error("Erreur dans la boucle de surveillance", t);
                        } finally {
                            pinging.set(false);
                        }
                    });
        } catch (Throwable t) {
            pinging.set(false);
            logger.error("Ping impossible", t);
        }
    }

    private void pingLimbo(ReconnectConfig cfg) {
        limbo().ifPresent(l -> l.ping(PingOptions.builder().timeout(cfg.pingTimeoutMs, TimeUnit.MILLISECONDS).build())
                .orTimeout(cfg.pingTimeoutMs + 1000, TimeUnit.MILLISECONDS)
                .whenComplete((ping, err) -> {
                    boolean up = err == null && ping != null;
                    if (up != limboUp) {
                        if (up) logger.info("Limbo '{}' EN LIGNE", cfg.limboServer);
                        else logger.warn("Limbo '{}' HORS LIGNE : sans lui, un crash du principal deconnectera les joueurs !", cfg.limboServer);
                    }
                    limboUp = up;
                    limboLastPingAt = System.currentTimeMillis();
                }));
    }

    private void onMainPing(boolean ok, long latencyMs, Throwable err) {
        ReconnectConfig cfg = config;
        long now = System.currentTimeMillis();
        boolean becameUp = false, becameDown = false;
        synchronized (this) {
            boolean first = !mainEverPinged;
            lastPingAt = now;
            mainEverPinged = true;
            if (ok) {
                lastLatencyMs = latencyMs;
                lastPingError = null;
                consecutiveFail = 0;
                consecutiveOk++;
                if (!mainUp && consecutiveOk >= cfg.requiredSuccessfulPings) {
                    mainUp = true;
                    mainStateSince = now;
                    becameUp = true;
                }
            } else {
                lastPingError = describe(err);
                consecutiveOk = 0;
                consecutiveFail++;
                if (mainUp || first) {
                    mainUp = false;
                    mainStateSince = now;
                    becameDown = true;
                }
            }
        }
        if (!ok) {
            for (Waiting w : waiting.values()) w.mainSeenDown = true;
        }
        if (becameUp) logger.info("Serveur principal '{}' EN LIGNE (ping {} ms)", cfg.mainServer, latencyMs);
        if (becameDown) logger.warn("Serveur principal '{}' HORS LIGNE ({})", cfg.mainServer, lastPingError);
        processLimbo(now);
        writeStatus();
    }

    /** Updates the action bar of limbo players and sends eligible ones back (staggered). */
    private void processLimbo(long now) {
        ReconnectConfig cfg = config;
        Optional<RegisteredServer> limboOpt = limbo();
        Optional<RegisteredServer> mainOpt = main();
        if (limboOpt.isEmpty() || mainOpt.isEmpty()) return;
        // forget players that are not in limbo any more
        waiting.entrySet().removeIf(e -> proxy.getPlayer(e.getKey()).map(p -> !p.isActive()).orElse(true));
        int slot = 0;
        for (Player p : limboOpt.get().getPlayersConnected()) {
            Waiting w = waiting.computeIfAbsent(p.getUniqueId(),
                    id -> new Waiting(now - cfg.forceReturnAfterMs, "dans le limbo", true));
            boolean eligible = mainUp && !w.inFlight && now >= w.nextAttemptAt
                    && now - w.since >= cfg.minLimboMs
                    && (w.mainSeenDown || now - w.since >= cfg.forceReturnAfterMs);
            if (eligible) {
                w.inFlight = true;
                long delay = slot++ * cfg.returnStaggerMs;
                if (delay == 0) sendBack(p, w, mainOpt.get());
                else proxy.getScheduler().buildTask(this, () -> sendBack(p, w, mainOpt.get()))
                        .delay(delay, TimeUnit.MILLISECONDS).schedule();
            } else if (!w.inFlight) {
                p.sendActionBar(mainUp ? mm.deserialize(cfg.actionbarReturning) : waitingBar(w));
            }
        }
    }

    /** Sends one player back to the main server. {@code force} ignores the eligibility rules. */
    void sendBack(Player p, Waiting w, RegisteredServer main) {
        if (!p.isActive() || !isOnLimbo(p)) {
            w.inFlight = false;
            return;
        }
        w.inFlight = true;
        w.attemptStartedAt = System.currentTimeMillis();
        p.sendActionBar(mm.deserialize(config.actionbarReturning));
        if (config.debug) logger.info("Retour de {} vers '{}' (tentative {})", p.getUsername(), config.mainServer, w.attempts + 1);
        try {
            p.createConnectionRequest(main).connect()
                    .orTimeout(60, TimeUnit.SECONDS)
                    .whenComplete((result, err) -> {
                        w.inFlight = false;
                        if (err == null && result != null && result.isSuccessful()) {
                            statReturns.incrementAndGet();
                            return;
                        }
                        String why = err != null ? describe(err)
                                : result == null ? "?" : result.getStatus() + result.getReasonComponent().map(c -> ": " + plain(c)).orElse("");
                        // the kick handler may already have registered this failure (Notify path)
                        if (w.lastFailureAt < w.attemptStartedAt) registerFailure(w, why, false);
                        logger.info("Retour de {} vers '{}' impossible : {}", p.getUsername(), config.mainServer, why);
                    });
        } catch (Throwable t) {
            w.inFlight = false;
            registerFailure(w, describe(t), false);
        }
    }

    /** Records a failed return attempt; returns the delay before the next one (ms). */
    private long registerFailure(Waiting w, String why, boolean deliberate) {
        ReconnectConfig cfg = config;
        w.attempts++;
        w.lastFailure = shorten(why, 120);
        w.lastFailureAt = System.currentTimeMillis();
        statReturnFailures.incrementAndGet();
        long delay = deliberate ? cfg.returnBackoffMaxMs
                : Math.min(cfg.returnBackoffMaxMs, cfg.pingIntervalMs * (1L << Math.min(5, w.attempts - 1)));
        w.nextAttemptAt = System.currentTimeMillis() + delay;
        return delay;
    }

    /**
     * Chat message shown on arrival in limbo. Velocity sends its own "You were kicked from main: ..."
     * when the message is null, and nothing only for the exact Component.empty() instance.
     */
    private Component redirectMessage(String reasonText) {
        String tpl = config.redirectMessage;
        if (tpl == null || tpl.isBlank()) return Component.empty();
        return mm.deserialize(tpl, Placeholder.unparsed("reason", shorten(reasonText, 60)));
    }

    private Component waitingBar(Waiting w) {
        long secs = (System.currentTimeMillis() - w.since) / 1000;
        return mm.deserialize(config.actionbarWaiting, Placeholder.unparsed("seconds", Long.toString(Math.max(0, secs))));
    }

    // ------------------------------------------------------------------ status

    Map<String, Object> statusMap() {
        ReconnectConfig cfg = config;
        long now = System.currentTimeMillis();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", Instant.ofEpochMilli(now).toString());
        m.put("uptimeMs", now - startedAt);
        Map<String, Object> mainM = new LinkedHashMap<>();
        mainM.put("name", cfg.mainServer);
        mainM.put("address", main().map(s -> addr(s)).orElse(null));
        mainM.put("up", mainUp);
        mainM.put("sinceMs", now - mainStateSince);
        mainM.put("consecutiveOk", consecutiveOk);
        mainM.put("consecutiveFail", consecutiveFail);
        mainM.put("lastLatencyMs", lastLatencyMs);
        mainM.put("lastError", lastPingError);
        mainM.put("silenceMs", lastMainSilenceMs);
        mainM.put("lastFreezeAt", lastFreezeAt == 0 ? null : Instant.ofEpochMilli(lastFreezeAt).toString());
        mainM.put("players", main().map(s -> s.getPlayersConnected().size()).orElse(0));
        m.put("main", mainM);
        Map<String, Object> limboM = new LinkedHashMap<>();
        limboM.put("name", cfg.limboServer);
        limboM.put("address", limbo().map(s -> addr(s)).orElse(null));
        limboM.put("up", limboUp);
        limboM.put("players", limbo().map(s -> s.getPlayersConnected().size()).orElse(0));
        m.put("limbo", limboM);
        List<Map<String, Object>> players = new ArrayList<>();
        for (Player p : proxy.getAllPlayers()) {
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("name", p.getUsername());
            pm.put("uuid", p.getUniqueId().toString());
            pm.put("server", p.getCurrentServer().map(c -> c.getServerInfo().getName()).orElse(null));
            Waiting w = waiting.get(p.getUniqueId());
            if (w != null) {
                pm.put("waitingMs", now - w.since);
                pm.put("waitingReason", w.reason);
                pm.put("returnAttempts", w.attempts);
                pm.put("lastFailure", w.lastFailure);
            }
            players.add(pm);
        }
        m.put("players", players);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("redirectsToLimbo", statRedirects.get());
        stats.put("initialToLimbo", statInitialToLimbo.get());
        stats.put("returns", statReturns.get());
        stats.put("returnFailures", statReturnFailures.get());
        stats.put("deliberateKicks", statDeliberateKicks.get());
        stats.put("freezeEvacuations", statFreezeEvacuations.get());
        m.put("stats", stats);
        return m;
    }

    private void writeStatus() {
        ReconnectConfig cfg = config;
        if (cfg.statusFile == null || cfg.statusFile.isBlank()) return;
        try {
            Path target = dataDir.resolve(cfg.statusFile);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, gson.toJson(statusMap()), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            if (cfg.debug) logger.warn("status.json non ecrit : {}", e.toString());
        }
    }

    // ------------------------------------------------------------------ helpers

    static String addr(RegisteredServer s) {
        java.net.InetSocketAddress a = s.getServerInfo().getAddress();
        return a.getHostString() + ":" + a.getPort();
    }

    static String plain(Component c) {
        try {
            return PlainTextComponentSerializer.plainText().serialize(c);
        } catch (Throwable t) {
            return String.valueOf(c);
        }
    }

    /** True when the kick reason looks like a voluntary kick (ban, /kick, whitelist...). */
    static boolean isDeliberate(Component reason, ReconnectConfig cfg) {
        if (reason == null || cfg.deliberateKickPatterns.isEmpty()) return false;
        StringBuilder sb = new StringBuilder(plain(reason));
        collectKeys(reason, sb);
        String text = sb.toString().toLowerCase(Locale.ROOT);
        for (String pattern : cfg.deliberateKickPatterns) {
            if (text.contains(pattern)) return true;
        }
        return false;
    }

    private static void collectKeys(Component c, StringBuilder sb) {
        if (c instanceof TranslatableComponent tc) {
            sb.append(' ').append(tc.key());
            for (var arg : tc.arguments()) {
                if (arg.value() instanceof Component ac) collectKeys(ac, sb);
            }
        }
        for (Component child : c.children()) collectKeys(child, sb);
    }

    static String describe(Throwable t) {
        if (t == null) return "pas de reponse";
        Throwable root = t;
        while ((root instanceof java.util.concurrent.CompletionException || root instanceof java.util.concurrent.ExecutionException)
                && root.getCause() != null) {
            root = root.getCause();
        }
        String msg = root.getMessage();
        String name = root.getClass().getSimpleName();
        return msg == null || msg.isBlank() ? name : name + ": " + shorten(msg, 100);
    }

    static String shorten(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

}
