package dev.vibecraft.proxy;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Detects a FROZEN main server (JVM alive, main thread stuck): such a server still answers status
 * pings, but stops sending anything to its players (a healthy server sends at least the time/keep-alive
 * packets every second or so). After ~30 s Velocity's read timeout would DISCONNECT the players
 * ("Your connection to main encountered a problem"), so we watch the bytes coming from the main
 * backend for each player and evacuate everybody to the limbo when ALL of them are silent for
 * {@code freeze-threshold-ms}.
 *
 * <p>Uses Velocity internals (VelocityServerConnection#getConnection().getChannel()) through
 * reflection; if they are not available the guard disables itself with one warning.</p>
 */
final class FreezeGuard {

    static final String HANDLER = "vibereconnect-activity";

    /** Netty handler counting inbound activity on one backend connection. */
    static final class Activity extends ChannelInboundHandlerAdapter {
        final Channel channel;
        final long attachedNanos = System.nanoTime();
        volatile long lastReadNanos = System.nanoTime();

        Activity(Channel channel) {
            this.channel = channel;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            lastReadNanos = System.nanoTime();
            ctx.fireChannelRead(msg);
        }

        long silenceMs() {
            return (System.nanoTime() - lastReadNanos) / 1_000_000;
        }
    }

    private final Logger logger;
    private final Map<UUID, Activity> activity = new ConcurrentHashMap<>();
    private volatile boolean unavailable;

    FreezeGuard(Logger logger) {
        this.logger = logger;
    }

    void attach(Player player, ServerConnection connection) {
        if (unavailable) return;
        try {
            Object mc = connection.getClass().getMethod("getConnection").invoke(connection);
            Channel ch = (Channel) mc.getClass().getMethod("getChannel").invoke(mc);
            Activity a = new Activity(ch);
            ch.eventLoop().execute(() -> {
                try {
                    if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
                    ch.pipeline().addFirst(HANDLER, a);
                } catch (Throwable t) {
                    logger.debug("freeze guard: pipeline error", t);
                }
            });
            activity.put(player.getUniqueId(), a);
        } catch (Throwable t) {
            unavailable = true;
            logger.warn("Detection de gel du serveur principal indisponible (API interne Velocity) : {}", t.toString());
        }
    }

    void detach(UUID player) {
        activity.remove(player);
    }

    /**
     * Returns the smallest silence (ms) among the given players' main connections, or -1 when no
     * connection is tracked. "All silent" = result >= threshold.
     */
    long minSilenceMs(Iterable<Player> playersOnMain) {
        long min = Long.MAX_VALUE;
        boolean any = false;
        for (Player p : playersOnMain) {
            Activity a = activity.get(p.getUniqueId());
            if (a == null || !a.channel.isActive()) continue;
            // ignore brand new connections (joining players are quiet for a moment)
            if ((System.nanoTime() - a.attachedNanos) / 1_000_000 < 3000) return -1;
            any = true;
            min = Math.min(min, a.silenceMs());
        }
        return any ? min : -1;
    }

    boolean available() {
        return !unavailable;
    }
}
