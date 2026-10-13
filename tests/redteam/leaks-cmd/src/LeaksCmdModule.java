package vibe.leaks_cmd;

import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/**
 * REDTEAM-LEAKS: command rename between generations. Version A registers /lkold (alias /lkoldalias, tab
 * completion); version B (CMD=lknew below, see README) registers /lknew instead.
 */
public class LeaksCmdModule implements VibeModule {
    static final String CMD = "lkold";

    @Override
    public void enable(ModuleContext ctx) {
        int g = ctx.generation();
        ctx.command(CMD, "rename test", (sender, args) -> sender.sendMessage(CMD + " v" + g),
                (sender, args) -> List.of("opt_" + CMD + "_v" + g), CMD + "alias");
    }
}
