package vibe.e2e_ctxres;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapelessRecipe;

/**
 * e2e fixture "ctxres": every kind of resource a ModuleContext tracks, and how they are undone on unload.
 * %V% version marker, %P% stat command, %MODE% = "full" (registers everything) or "probe" (stat command only),
 * %TARGET% = module id whose resources the stat command reports (the module itself in "full" mode), %BOT% = bot name.
 *
 * Shared state (JDK types only), keys suffixed with the run id so runs never interfere:
 *   e2e-ctxres.journal.<RUN>.<target>   CopyOnWriteArrayList<String>: enable / disable / cleanup order
 *   e2e-ctxres.recipe.<RUN>.<target>    String: recipe key
 *   e2e-ctxres.entity.<RUN>.<target>    Map<String version, String uuid>
 *   e2e-ctxres.lambda.<RUN>.<target>    Map<String version, Runnable>: lambdas of older generations
 *   e2e-ctxres.staleRuns.<RUN>.<target> AtomicLong: how many times an old generation's lambda really ran
 */
public class CtxresModule implements VibeModule {
    static final String V = "%V%";
    static final String P = "%P%";
    static final String MODE = "%MODE%";
    static final String TARGET = "%TARGET%";
    /** the e2e bot: the entity is spawned next to it (never next to a real player) */
    static final String BOT = "%BOT%";
    static final String RUN = V.contains(".") ? V.substring(0, V.indexOf('.')) : V;

    @Override
    @SuppressWarnings("unchecked")
    public void enable(ModuleContext ctx) {
        Map<String, Object> shared = ctx.shared();
        List<String> journal = (List<String>) shared.computeIfAbsent("e2e-ctxres.journal." + RUN + "." + TARGET, k -> new CopyOnWriteArrayList<String>());
        Map<String, String> entities = (Map<String, String>) shared.computeIfAbsent("e2e-ctxres.entity." + RUN + "." + TARGET, k -> new java.util.concurrent.ConcurrentHashMap<String, String>());
        Map<String, Runnable> lambdas = (Map<String, Runnable>) shared.computeIfAbsent("e2e-ctxres.lambda." + RUN + "." + TARGET, k -> new java.util.concurrent.ConcurrentHashMap<String, Runnable>());
        AtomicLong staleRuns = (AtomicLong) shared.computeIfAbsent("e2e-ctxres.staleRuns." + RUN + "." + TARGET, k -> new AtomicLong());

        if (MODE.equals("full")) {
            journal.add("enable " + V);
            // 1. recipe
            NamespacedKey rk = ctx.key("e2e_recipe");
            ShapelessRecipe recipe = new ShapelessRecipe(rk, new ItemStack(Material.DIAMOND, 1));
            recipe.addIngredient(Material.DEAD_BUSH);
            recipe.addIngredient(Material.COBWEB);
            ctx.recipe(recipe);
            shared.put("e2e-ctxres.recipe." + RUN + "." + TARGET, rk.toString());
            // 2. entity, spawned above the e2e bot so its chunk is loaded
            Player near = Bukkit.getPlayerExact(BOT);
            if (near != null) {
                Location at = near.getLocation().add(0, 3, 0);
                ArmorStand stand = at.getWorld().spawn(at, ArmorStand.class, a -> {
                    a.setMarker(true);
                    a.setInvisible(true);
                    a.setGravity(false);
                    a.setPersistent(false);
                });
                ctx.entity(stand);
                entities.put(V, stand.getUniqueId().toString());
            }
            // 3. custom resources, cleaned up in reverse registration order AFTER disable()
            ctx.track("res-" + V, res -> journal.add("track " + res));
            ctx.onCleanup(() -> journal.add("onCleanup " + V));
            // 4. classic Bukkit listener through ctx.listen()
            ctx.listen(new Lis());
            // 5. a lambda that outlives its generation (careless code keeps it in a global map)
            lambdas.put(V, () -> staleRuns.incrementAndGet());
        }

        ctx.command(P, (s, args) -> {
            StringBuilder sb = new StringBuilder("E2E-CTXRES " + V);
            if (args.length > 0 && args[0].equals("stale")) {
                // run the lambdas left behind by OTHER (unloaded) generations of the target
                for (Map.Entry<String, Runnable> e : new TreeMap<>(lambdas).entrySet()) {
                    if (e.getKey().equals(V)) {
                        continue;
                    }
                    String outcome;
                    try {
                        e.getValue().run();
                        outcome = "RAN";
                    } catch (Throwable t) {
                        outcome = "KILLED:" + t.getClass().getSimpleName();
                    }
                    sb.append(" stale.").append(e.getKey()).append('=').append(outcome);
                }
                sb.append(" staleRuns=").append(staleRuns.get());
                s.sendMessage(sb.toString());
                return;
            }
            Object rk = shared.get("e2e-ctxres.recipe." + RUN + "." + TARGET);
            sb.append(" recipe=").append(rk == null ? "none" : (Bukkit.getRecipe(NamespacedKey.fromString((String) rk)) != null ? "present" : "absent"));
            for (Map.Entry<String, String> e : new TreeMap<>(entities).entrySet()) {
                Entity ent = Bukkit.getEntity(UUID.fromString(e.getValue()));
                sb.append(" entity.").append(e.getKey()).append('=').append(ent != null && ent.isValid() ? "alive" : "gone");
            }
            sb.append(" journal=").append(String.join(",", new ArrayList<>(journal)).replace(' ', ':'));
            s.sendMessage(sb.toString());
        });
    }

    @Override
    @SuppressWarnings("unchecked")
    public void disable(ModuleContext ctx) {
        if (MODE.equals("full")) {
            ((List<String>) ctx.shared().get("e2e-ctxres.journal." + RUN + "." + TARGET)).add("disable " + V);
        }
    }

    public static class Lis implements Listener {
        @EventHandler
        public void onCommand(PlayerCommandPreprocessEvent e) {
            String m = e.getMessage().toLowerCase();
            if (m.equals("/" + P) || m.startsWith("/" + P + " ")) {
                e.getPlayer().sendMessage("E2E-CTXRES-LISTEN " + V);
            }
        }
    }
}
