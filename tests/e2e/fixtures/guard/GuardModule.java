package vibe.e2e_guard;

import java.time.Duration;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * e2e fixture "guard": commands that misbehave on purpose. %V% version marker, %P% command prefix.
 * Every command prints a marker BEFORE misbehaving so the runner can measure how long the guard took.
 */
public class GuardModule implements VibeModule {
    static final String V = "%V%";
    static final String P = "%P%";
    static volatile boolean spin = true;

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command(P + "ok", (s, a) -> s.sendMessage("E2E-GUARD-OK " + V));

        // --- runtime exceptions
        ctx.command(P + "boom", (s, a) -> {
            throw new IllegalStateException("E2E-BOOM " + V);
        });
        ctx.command(P + "npe", (s, a) -> {
            String x = a.length > 100 ? "" : null;
            s.sendMessage("E2E-NPE-NOT-REACHED " + x.length());
        });
        ctx.command(P + "taskboom", (s, a) -> {
            ctx.later(1, () -> {
                throw new IllegalStateException("E2E-TASK-BOOM " + V);
            });
            s.sendMessage("E2E-TASKBOOM-SCHEDULED " + V);
        });
        ctx.on(PlayerCommandPreprocessEvent.class, e -> {
            if (e.getMessage().equalsIgnoreCase("/" + P + "ok listener-boom")) {
                throw new IllegalStateException("E2E-LISTENER-BOOM " + V);
            }
        });

        // --- infinite loops on the main thread
        ctx.command(P + "loop", (s, a) -> {
            s.sendMessage("E2E-LOOP-START " + V);
            while (spin) {
                // empty body: only the back-edge check can stop this
            }
            s.sendMessage("E2E-LOOP-END-NOT-REACHED");
        });
        ctx.command(P + "loopcatch", (s, a) -> {
            s.sendMessage("E2E-LOOPCATCH-START " + V);
            int caught = 0;
            while (spin) {
                try {
                    while (spin) {
                    }
                } catch (Throwable t) {
                    caught++; // a naive "catch everything" must not defeat the guard
                }
            }
            s.sendMessage("E2E-LOOPCATCH-END-NOT-REACHED " + caught);
        });
        ctx.command(P + "loopcall", (s, a) -> {
            s.sendMessage("E2E-LOOPCALL-START " + V);
            double acc = 0;
            while (spin) {
                acc += Math.sqrt(System.nanoTime() & 1023);
            }
            s.sendMessage("E2E-LOOPCALL-END-NOT-REACHED " + acc);
        });

        // --- recursion
        ctx.command(P + "recurse", (s, a) -> {
            s.sendMessage("E2E-RECURSE-START " + V);
            s.sendMessage("E2E-RECURSE-END-NOT-REACHED " + recurse(0));
        });

        // --- Thread.sleep on the main thread
        ctx.command(P + "sleep", (s, a) -> {
            s.sendMessage("E2E-SLEEP-START " + V);
            long t0 = System.nanoTime();
            Thread.sleep(10_000);
            s.sendMessage("E2E-SLEEP-END-NOT-REACHED " + (System.nanoTime() - t0) / 1_000_000);
        });
        ctx.command(P + "sleepd", (s, a) -> {
            s.sendMessage("E2E-SLEEPD-START " + V);
            Thread.sleep(Duration.ofSeconds(10));
            s.sendMessage("E2E-SLEEPD-END-NOT-REACHED");
        });

        ctx.logger().info("e2e guard enabled " + V);
    }

    static int recurse(int n) {
        return recurse(n + 1) + 1;
    }
}
