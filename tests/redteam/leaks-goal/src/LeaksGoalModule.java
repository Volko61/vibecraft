package vibe.leaks_goal;

import java.util.EnumSet;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Cow;

/**
 * REDTEAM-LEAKS: custom mob AI with Paper's MobGoals API on a mob that is NOT removed on reload
 * (e.g. "make the cows dance" on existing cows). /lkgoal spawns a cow (raw) and adds the goal.
 */
public class LeaksGoalModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        int g = ctx.generation();
        ctx.command("lkgoal", (sender, args) -> {
            Location loc = Bukkit.getWorlds().get(0).getSpawnLocation().add(4, 0, 4);
            Cow cow = loc.getWorld().spawn(loc, Cow.class, c -> c.addScoreboardTag("leaks-goal-cow"));
            Bukkit.getMobGoals().addGoal(cow, 0, new Goal<Cow>() {
                int ticks;
                @Override
                public boolean shouldActivate() {
                    return true;
                }
                @Override
                public void tick() {
                    if (++ticks % 40 == 0) cow.setRotation(cow.getYaw() + 90, 0);
                }
                @Override
                public GoalKey<Cow> getKey() {
                    return GoalKey.of(Cow.class, ctx.key("dance_v" + g));
                }
                @Override
                public EnumSet<GoalType> getTypes() {
                    return EnumSet.of(GoalType.LOOK);
                }
            });
            sender.sendMessage("dancing cow spawned (goal v" + g + ")");
        });
    }
}
