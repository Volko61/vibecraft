package vibe.scoreboard;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;

/** Live sidebar "blocks mined" on the main scoreboard, counts persisted in the store. Shows ctx.track(). */
public class ScoreboardModule implements VibeModule {

    private static final String OBJECTIVE = "vibe_mined";

    @Override
    public void enable(ModuleContext ctx) {
        Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
        Objective leftover = main.getObjective(OBJECTIVE); // e.g. after a server crash
        if (leftover != null) {
            leftover.unregister();
        }
        // track(): the objective is unregistered automatically when the module reloads or is disabled.
        Objective obj = ctx.track(main.registerNewObjective(OBJECTIVE, Criteria.DUMMY, ctx.mm("<gold><bold>⛏ Blocs minés")),
                Objective::unregister);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        for (Player p : Bukkit.getOnlinePlayers()) {
            obj.getScore(p.getName()).setScore(mined(ctx, p));
        }
        ctx.on(PlayerJoinEvent.class, e -> obj.getScore(e.getPlayer().getName()).setScore(mined(ctx, e.getPlayer())));

        // MONITOR + ignoreCancelled: only count blocks that were really broken.
        ctx.on(BlockBreakEvent.class, EventPriority.MONITOR, true, e -> {
            Player p = e.getPlayer();
            int n = mined(ctx, p) + 1;
            ctx.store().set("mined." + p.getUniqueId(), n);
            obj.getScore(p.getName()).setScore(n);
        });
    }

    private static int mined(ModuleContext ctx, Player p) {
        return ctx.store().getInt("mined." + p.getUniqueId(), 0);
    }
}
