package vibe.e2e_hot;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

/**
 * e2e fixture "hot": one command + one listener (+ a helper class in another file).
 * Tokens replaced by the runner: %V% (version marker), %P% (command name).
 * Injection points (comments) replaced by some scenarios: @CMD@ (inside the command handler), @ENABLE@.
 */
public class HotModule implements VibeModule {
    static final String V = "%V%";
    static final String P = "%P%";

    @Override
    public void enable(ModuleContext ctx) throws Exception {
        ctx.command(P, (sender, args) -> {
            /*@CMD@*/
            sender.sendMessage(Fmt.line("CMD", V));
        });
        ctx.on(PlayerCommandPreprocessEvent.class, e -> {
            String m = e.getMessage().toLowerCase();
            if (m.equals("/" + P) || m.startsWith("/" + P + " ")) {
                e.getPlayer().sendMessage(Fmt.line("LISTENER", V));
            }
        });
        /*@ENABLE@*/
        ctx.logger().info("e2e hot enabled " + V);
    }
}
