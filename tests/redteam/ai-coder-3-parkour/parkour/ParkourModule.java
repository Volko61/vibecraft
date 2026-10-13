package vibe.parkour;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Mini-jeu parkour : /parkour [joueur] construit le parcours (une fois) dans le ciel et y téléporte le joueur.
 * Chrono dans la barre d'action, meilleur temps par joueur dans le store, classement /parkour top + hologramme.
 */
public class ParkourModule implements VibeModule {

    private static final Material[] COLORS = {Material.LIME_WOOL, Material.LIGHT_BLUE_WOOL, Material.PINK_WOOL,
            Material.YELLOW_WOOL, Material.ORANGE_WOOL, Material.MAGENTA_WOOL};
    private static final int JUMPS = 12;

    private ModuleContext ctx;
    private Location origin;                 // center of the start platform (block coords)
    private final List<Location> jumps = new ArrayList<>();
    private Location finish;
    private final Map<UUID, Long> running = new HashMap<>(); // player -> start time (ms)
    private TextDisplay board;

    @Override
    public void enable(ModuleContext ctx) {
        this.ctx = ctx;
        if (ctx.store().get("origin") instanceof Location saved && saved.getWorld() != null) {
            layout(saved);
            showBoard();
        }

        ctx.command("parkour", "Mini-jeu parkour chronométré", (sender, args) -> {
            String sub = args.length > 0 ? args[0] : "";
            if (sub.equalsIgnoreCase("top")) {
                sender.sendMessage(ctx.mm(leaderboardText()));
                return;
            }
            if (sub.equalsIgnoreCase("reset") && (sender.isOp() || !(sender instanceof Player))) {
                ctx.store().set("best", null);
                ctx.store().set("names", null);
                showBoard();
                sender.sendRichMessage("<gray>Classement remis à zéro.");
                return;
            }
            Player p = !sub.isEmpty() ? ctx.server().getPlayerExact(sub) : sender instanceof Player pl ? pl : null;
            if (p == null) {
                sender.sendRichMessage("<red>Usage : /parkour [joueur] | /parkour top");
                return;
            }
            if (origin == null) {
                Location o = p.getLocation().getBlock().getLocation().add(0, 15, 0);
                build(o);
                ctx.store().set("origin", o);
                ctx.broadcast("<aqua>✦ Un parcours de parkour est apparu dans le ciel ! <white>/parkour <aqua>pour jouer.");
            }
            start(p);
            if (sender != p) sender.sendRichMessage("<aqua>" + p.getName() + " démarre le parkour.");
        }, (sender, args) -> args.length == 1 ? List.of("top") : List.of(), "pk");

        ctx.every(2, this::tick); // chrono, fall and finish detection (only for running players)
        ctx.on(PlayerQuitEvent.class, e -> running.remove(e.getPlayer().getUniqueId()));
        ctx.logger().info("enabled");
    }

    /** Computes the course positions from the origin (deterministic, same course after every reload). */
    private void layout(Location o) {
        origin = o.clone();
        jumps.clear();
        Random r = new Random(42);
        Location cur = o.clone();
        for (int i = 0; i < JUMPS; i++) {
            int dy = r.nextInt(3) == 0 ? 1 : 0;
            int dx = dy == 1 ? 2 : 2 + r.nextInt(2);
            int dz = r.nextInt(3) - 1;
            cur = cur.clone().add(dx, dy, dz);
            jumps.add(cur);
        }
        finish = cur.clone().add(3, 0, 0);
    }

    private void build(Location o) {
        layout(o);
        World w = o.getWorld();
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                o.clone().add(x, -1, z).getBlock().setType(Material.QUARTZ_BLOCK);
        for (int i = 0; i < jumps.size(); i++)
            jumps.get(i).clone().add(0, -1, 0).getBlock().setType(COLORS[i % COLORS.length]);
        finish.clone().add(0, -1, 0).getBlock().setType(Material.GOLD_BLOCK);
        w.spawnParticle(Particle.END_ROD, o.clone().add(0.5, 0, 0.5), 60, 2, 1, 2, 0.05);
        showBoard();
    }

    private void start(Player p) {
        p.teleport(origin.clone().add(0.5, 0, 0.5).setDirection(jumps.get(0).toVector().subtract(origin.toVector())));
        running.put(p.getUniqueId(), System.currentTimeMillis());
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f);
        p.sendRichMessage("<aqua>⏱ C'est parti ! Atteins le <gold>bloc d'or</gold> le plus vite possible.");
    }

    private void tick() {
        if (running.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (var it = running.entrySet().iterator(); it.hasNext(); ) {
            var entry = it.next();
            Player p = ctx.server().getPlayer(entry.getKey());
            if (p == null || !p.isOnline()) { it.remove(); continue; }
            long ms = now - entry.getValue();
            Location loc = p.getLocation();
            if (!loc.getWorld().equals(origin.getWorld()) || loc.getY() < origin.getY() - 4) {
                p.sendRichMessage("<red>✖ Chute ! Retour au départ.");
                p.playSound(loc, Sound.ENTITY_VILLAGER_NO, 1f, 1f);
                start(p);
                continue;
            }
            Block below = loc.clone().add(0, -0.2, 0).getBlock();
            if (below.getType() == Material.GOLD_BLOCK && below.getLocation().distanceSquared(finish.clone().add(0, -1, 0)) < 0.5) {
                it.remove();
                finish(p, ms);
                continue;
            }
            p.sendActionBar(ctx.mm("<yellow>⏱ " + format(ms)));
        }
    }

    private void finish(Player p, long ms) {
        String path = "best." + p.getUniqueId();
        long best = ctx.store().getLong(path, Long.MAX_VALUE);
        boolean record = ms < best;
        if (record) {
            ctx.store().set(path, ms);
            ctx.store().set("names." + p.getUniqueId(), p.getName());
        }
        ctx.broadcast("<gold>★ " + p.getName() + " termine le parkour en <yellow>" + format(ms) + "</yellow>"
                + (record ? " <green>(record perso !)" : ""));
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation(), 60, 0.5, 1, 0.5, 0.3);
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        showBoard();
    }

    private String leaderboardText() {
        List<Map.Entry<String, Long>> top = new ArrayList<>();
        for (String uuid : ctx.store().keys("best")) {
            top.add(Map.entry(ctx.store().getString("names." + uuid, "?"), ctx.store().getLong("best." + uuid, 0)));
        }
        top.sort(Comparator.comparingLong(Map.Entry::getValue));
        StringBuilder sb = new StringBuilder("<gold><bold>★ Classement parkour</bold>");
        if (top.isEmpty()) sb.append("<newline><gray>Personne encore... à toi !");
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            sb.append("<newline><yellow>").append(i + 1).append(". <white>").append(top.get(i).getKey())
                    .append(" <gray>- <aqua>").append(format(top.get(i).getValue()));
        }
        return sb.toString();
    }

    private void showBoard() {
        if (origin == null) return;
        if (board != null && board.isValid()) {
            board.text(ctx.mm(leaderboardText()));
            return;
        }
        board = ctx.entity(origin.getWorld().spawn(origin.clone().add(0.5, 2.5, -2.5), TextDisplay.class, t -> {
            t.text(ctx.mm(leaderboardText()));
            t.setBillboard(Display.Billboard.CENTER);
        }));
    }

    private static String format(long ms) {
        return String.format("%d:%02d.%02d", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10);
    }
}
