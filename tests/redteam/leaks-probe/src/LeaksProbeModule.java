package vibe.leaks_probe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.gson.Gson;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Keyed;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

/** Read-only probe used by the REDTEAM-LEAKS session: /lkprobe [player] prints one JSON line of server state. */
public class LeaksProbeModule implements VibeModule {

    static String loaderOf(Object o) {
        if (o == null) return "null";
        ClassLoader l = o.getClass().getClassLoader();
        return o.getClass().getName() + "@" + (l == null ? "boot" : l.getName());
    }

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("lkprobe", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0])
                    : Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            Map<String, Object> m = new LinkedHashMap<>();
            World w = Bukkit.getWorlds().get(0);
            Map<String, Integer> tags = new TreeMap<>();
            for (Entity e : w.getEntities()) {
                for (String t : e.getScoreboardTags()) {
                    if (t.startsWith("leaks")) tags.merge(t, 1, Integer::sum);
                }
            }
            m.put("entityTags", tags);
            List<String> bars = new ArrayList<>();
            Bukkit.getBossBars().forEachRemaining(b -> bars.add(b.getKey().toString() + (b.isVisible() ? "" : "(hidden)") + b.getPlayers().size()));
            m.put("keyedBossBars", bars);
            Scoreboard main = Bukkit.getScoreboardManager().getMainScoreboard();
            m.put("objectives", main.getObjectives().stream().map(Objective::getName).sorted().toList());
            m.put("teams", main.getTeams().stream().map(Team::getName).sorted().toList());
            if (p != null) {
                m.put("player", p.getName());
                m.put("playerSbIsMain", p.getScoreboard() == main);
                m.put("playerSbObjectives", p.getScoreboard().getObjectives().stream().map(Objective::getName).toList());
                m.put("effects", p.getActivePotionEffects().stream().map(e -> e.getType().getKey().getKey() + ":" + e.getAmplifier()).toList());
                m.put("allowFlight", p.getAllowFlight());
                m.put("flying", p.isFlying());
                m.put("walkSpeed", p.getWalkSpeed());
                m.put("permLeaks", p.hasPermission("leaks.test"));
                m.put("permsGen", p.getEffectivePermissions().stream().map(i -> i.getPermission()).filter(s -> s.startsWith("leaks.gen")).sorted().toList());
                m.put("hostAttachments", p.getEffectivePermissions().stream()
                        .filter(i -> i.getAttachment() != null && i.getAttachment().getPlugin() == ctx.plugin())
                        .map(i -> System.identityHashCode(i.getAttachment())).distinct().count());
                m.put("metadata", p.getMetadata("leaks").stream().map(v -> loaderOf(v.value())).toList());
                var top = p.getOpenInventory().getTopInventory();
                m.put("openInv", top.getType().name() + " holder=" + loaderOf(top.getHolder(false)));
                m.put("playerBorder", p.getWorldBorder() == null ? null : p.getWorldBorder().getSize());
                m.put("discovered", p.getDiscoveredRecipes().stream().filter(k -> k.getKey().contains("leaks")).map(Object::toString).toList());
                m.put("gameMode", p.getGameMode().name());
            }
            m.put("border", w.getWorldBorder().getSize());
            m.put("daylightCycle", w.getGameRuleValue(GameRule.DO_DAYLIGHT_CYCLE));
            m.put("time", w.getTime());
            m.put("storm", w.hasStorm());
            m.put("block_0_m61_0", w.getBlockAt(0, -61, 0).getType().name());
            m.put("services", Bukkit.getServicesManager().getRegistrations(ctx.plugin()).stream()
                    .map(r -> r.getService().getSimpleName() + "=" + loaderOf(r.getProvider())).toList());
            m.put("outChannels", Bukkit.getMessenger().getOutgoingChannels(ctx.plugin()));
            m.put("inRegs", Bukkit.getMessenger().getIncomingChannelRegistrations(ctx.plugin()).stream()
                    .map(r -> r.getChannel() + "=" + loaderOf(r.getListener())).toList());
            List<String> rec = new ArrayList<>();
            Bukkit.recipeIterator().forEachRemaining(r -> {
                if (r instanceof Keyed k && k.getKey().getKey().contains("leaks")) rec.add(k.getKey().toString());
            });
            m.put("recipes", rec);
            m.put("commands", Bukkit.getCommandMap().getKnownCommands().keySet().stream().filter(k -> k.contains("lk")).sorted().toList());
            m.put("pendingTasks", Bukkit.getScheduler().getPendingTasks().stream().filter(t -> t.getOwner() == ctx.plugin()).count());
            m.put("hostListeners", HandlerList.getRegisteredListeners(ctx.plugin()).size());
            Map<String, Integer> threads = new TreeMap<>();
            for (Thread t : Thread.getAllStackTraces().keySet()) {
                String n = t.getName().replaceAll("[0-9]+", "#");
                threads.merge(n, 1, Integer::sum);
            }
            m.put("threadTotal", Thread.getAllStackTraces().size());
            m.put("threads", threads);
            Map<String, Object> sh = new TreeMap<>();
            ctx.shared().forEach((k, v) -> { if (k.startsWith("lk")) sh.put(k, v instanceof Number || v instanceof String ? v : loaderOf(v)); });
            m.put("shared", sh);
            sender.sendMessage(new Gson().toJson(m));
        });
    }
}
