package vibe.e2e_breaker;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/**
 * e2e fixture "breaker": %MODE% = "throw" (a task throws every tick), "spin" (a task loops forever every tick)
 * or "calm" (no task). %V% version marker, %P% command name.
 */
public class BreakerModule implements VibeModule {
    static final String V = "%V%";
    static final String P = "%P%";
    static final String MODE = "%MODE%";
    static volatile boolean spin = true;

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command(P, (s, a) -> s.sendMessage("E2E-BREAKER-CMD " + V));
        if (MODE.equals("spin")) {
            ctx.every(1, () -> {
                while (spin) {
                }
            });
        } else if (!MODE.equals("calm")) {
            ctx.every(1, () -> {
                throw new IllegalStateException("E2E-BREAKER tick " + V);
            });
        }
    }
}
