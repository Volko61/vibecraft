package vibe.kick;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;
/**
 * REDTEAM-CRASH / crash-kick (severity: HIGH)
 * The command policy (Policy.forbiddenCommandLine) only blocks the command LINES stop/restart/reload
 * /rl. It does NOT cover the Bukkit API: Player.kick(), Player.ban(), BanList, World unload, etc.
 * A module can disconnect any/all players directly. Observed: p.kick(...) disconnected the bot
 * (reason "direct-api-kick"); no error, policy not triggered.
 * Likely accidental: an "afk kicker" / "kick griefers" feature.
 * Suggested fix: redirect Player.kick / kickPlayer / ban / BanList.addBan / World unload (as other
 * tracked calls) and either forbid them for modules or require op confirmation; at minimum forbid
 * mass-kick of all players.
 */
public class KickModule implements VibeModule {
    @Override public void enable(ModuleContext ctx) {
        ctx.command("kickall", (s,a) -> {
            for (Player p : Bukkit.getOnlinePlayers()) p.kick(Component.text("direct-api-kick"));
        });
    }
}
