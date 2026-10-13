package vibe.leaks_threads;

import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/**
 * REDTEAM-LEAKS: thread-creating APIs an AI coder may use instead of ctx.async/every (none cleaned up):
 * java.util.Timer, a hand-made ThreadPoolExecutor, Executors.newSingleThreadScheduledExecutor,
 * a raw Thread that swallows interrupts, a virtual thread.
 */
public class LeaksThreadsModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        int g = ctx.generation();
        Timer timer = new Timer("leaks-timer-v" + g);
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                ctx.logger().info("timer v" + g + " fired");
            }
        }, 600_000L);

        ThreadPoolExecutor tpe = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> new Thread(r, "leaks-tpe-v" + g));
        tpe.prestartAllCoreThreads();

        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "leaks-ses-v" + g));
        ses.scheduleAtFixedRate(() -> { }, 1, 1, TimeUnit.SECONDS);

        new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    // swallowed on purpose
                }
            }
        }, "leaks-raw-v" + g).start();

        Thread.ofVirtual().name("leaks-vt-v" + g).start(() -> {
            while (true) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    // swallowed
                }
            }
        });
        ctx.logger().info("threads v" + g + " started");
    }
}
