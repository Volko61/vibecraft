package vibe.e2e_watch;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/** e2e fixture "watch": reads config.yml (live-editable). %V% version marker. */
public class WatchModule implements VibeModule {
    static final String V = "%V%";

    @Override
    public void enable(ModuleContext ctx) {
        String msg = ctx.config().getString("msg", "none");
        ctx.command("e2ewatch", (s, a) -> s.sendMessage("E2E-WATCH " + V + " msg=" + msg));
    }
}
