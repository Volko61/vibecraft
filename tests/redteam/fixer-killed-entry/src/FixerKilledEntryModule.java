package vibe.fixer_killed_entry;

import java.util.function.Consumer;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/**
 * FIXER regression: code of an unloaded generation called DIRECTLY BY THE SERVER / JDK must do nothing
 * (generic "entry of killed code = no-op" safety net), while killed code called by module code keeps
 * throwing ModuleKilledError.
 *
 * The handler is added through a method reference (invokedynamic), which bypasses the Logger.addHandler
 * redirect, on a private logger that the unload sweep does not look at: so it really stays registered
 * after a reload, exactly like an untracked server callback.
 * /fxkilled -> logs "ping" on that logger: every generation's handler that still runs appends to a shared list.
 */
public class FixerKilledEntryModule implements VibeModule {

    static final class H extends Handler {
        final int gen;
        final java.util.List<String> out;

        H(int gen, java.util.List<String> out) {
            this.gen = gen;
            this.out = out;
        }

        @Override
        public void publish(LogRecord r) {
            out.add("v" + gen);
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void enable(ModuleContext ctx) {
        java.util.List<String> out = (java.util.List<String>) ctx.shared()
                .computeIfAbsent("fx.killed.out", k -> new java.util.concurrent.CopyOnWriteArrayList<String>());
        Logger l = Logger.getLogger("fx.killed.private");
        l.setUseParentHandlers(false);
        Consumer<Handler> add = l::addHandler; // not redirected: stays after unload on purpose
        add.accept(new H(ctx.generation(), out));
        ctx.command("fxkilled", (sender, args) -> {
            out.clear();
            l.warning("ping");
            sender.sendMessage("handlers=" + l.getHandlers().length + " ran=" + out);
        });
    }
}
