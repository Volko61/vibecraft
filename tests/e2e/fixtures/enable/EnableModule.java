package vibe.e2e_enable;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/**
 * e2e fixture "enable": enable() misbehaves depending on %MODE%:
 * "ok", "loop" (infinite loop), "loopcatch" (infinite loop that swallows Throwable), "throw", "recurse".
 * The command is registered BEFORE misbehaving: a failed attempt must clean it up. %V%, %P% as usual.
 */
public class EnableModule implements VibeModule {
    static final String V = "%V%";
    static final String P = "%P%";
    static final String MODE = "%MODE%";
    static volatile boolean spin = true;

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command(P, (s, a) -> s.sendMessage("E2E-ENABLE " + V));
        if (MODE.equals("loop")) {
            while (spin) {
            }
        }
        if (MODE.equals("loopcatch")) {
            while (spin) {
                try {
                    while (spin) {
                    }
                } catch (Throwable t) {
                    // swallow and retry
                }
            }
        }
        if (MODE.equals("throw")) {
            throw new IllegalStateException("E2E-ENABLE-THROW " + V);
        }
        if (MODE.equals("recurse")) {
            ctx.logger().info("depth " + recurse(0));
        }
    }

    static int recurse(int n) {
        return recurse(n + 1) + 1;
    }
}
