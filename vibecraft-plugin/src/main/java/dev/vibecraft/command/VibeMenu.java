package dev.vibecraft.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import dev.vibecraft.ai.AiJob;
import dev.vibecraft.ai.AiJobService;
import dev.vibecraft.ai.AiJobState;
import dev.vibecraft.core.ModuleInfo;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.core.ModuleState;
import dev.vibecraft.ideas.Idea;
import dev.vibecraft.ideas.IdeaQueue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * In-game chest GUI for {@code /vibe menu}: one icon per module (colour = state), the AI jobs and
 * the audience ideas. Clicks run the matching {@code /vibe} subcommand as the player, so permissions,
 * messages and async handling stay those of {@link VibeCommand}. Open menus refresh every second.
 */
public final class VibeMenu implements Listener {

    private static final int SIZE = 54;
    private static final int MODULE_SLOTS = 45;
    private static final int SLOT_IDEAS = 45;
    private static final int SLOT_JOBS = 47;
    private static final int SLOT_CLAUDE = 48;
    private static final int SLOT_RELOAD_ALL = 49;
    private static final int SLOT_DASHBOARD = 51;
    private static final int SLOT_CLOSE = 53;
    private static final int SLOT_BACK = 49;

    private enum Page { MODULES, IDEAS }

    /** Marks our inventories and remembers what each slot means. */
    private static final class Holder implements InventoryHolder {
        final Page page;
        final String[] actions = new String[SIZE];
        Inventory inventory;

        Holder(Page page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final JavaPlugin plugin;
    private final ModuleManager modules;
    private final AiJobService ai;
    private final IdeaQueue ideas;
    private volatile java.util.function.Consumer<Player> claudeAction;

    /** "Claude" button of the modules page (opens the conversations dialog). */
    public void setClaudeAction(java.util.function.Consumer<Player> action) {
        this.claudeAction = action;
    }

    public VibeMenu(JavaPlugin plugin, ModuleManager modules, AiJobService ai, IdeaQueue ideas) {
        this.plugin = plugin;
        this.modules = modules;
        this.ai = ai;
        this.ideas = ideas;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshOpen, 20L, 20L);
    }

    public void open(Player player) {
        open(player, Page.MODULES);
    }

    private void open(Player player, Page page) {
        Holder holder = new Holder(page);
        Component title = Msg.mm(page == Page.MODULES
                ? "<gradient:#c084fc:#22d3ee><b>VibeCraft</b></gradient> <dark_gray>— modules"
                : "<gradient:#c084fc:#22d3ee><b>VibeCraft</b></gradient> <dark_gray>— idées du public");
        holder.inventory = Bukkit.createInventory(holder, SIZE, title);
        render(holder);
        player.openInventory(holder.inventory);
    }

    private void refreshOpen() {
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h) {
                render(h);
            }
        }
    }

    // ------------------------------------------------------------------ rendering

    private void render(Holder h) {
        h.inventory.clear();
        java.util.Arrays.fill(h.actions, null);
        if (h.page == Page.MODULES) {
            renderModules(h);
        } else {
            renderIdeas(h);
        }
    }

    private void renderModules(Holder h) {
        List<ModuleInfo> list = modules.list();
        int slot = 0;
        for (ModuleInfo m : list) {
            if (slot >= MODULE_SLOTS) {
                break;
            }
            List<String> lore = new ArrayList<>();
            lore.add("<gray>État :</gray> " + "<" + Msg.stateColor(m.state()) + ">" + esc(Msg.stateLabel(m.state())));
            if (m.rolledBack()) {
                lore.add("<gold>↩ dernière version refusée, ancienne conservée");
            }
            if (!m.description().isBlank()) {
                lore.add("<white>" + esc(Msg.cut(m.description(), 60)));
            }
            if (m.lastReloadAt() > 0) {
                lore.add("<gray>Rechargé " + esc(Msg.ago(m.lastReloadAt())));
            }
            if (!m.commands().isEmpty()) {
                lore.add("<gray>Commandes :</gray> <aqua>/" + esc(String.join(" /", m.commands())));
            }
            lore.add(String.format("<gray>Charge :</gray> <white>%.2f ms/tick", m.msPerTick()));
            if (m.runtimeErrorCount() > 0) {
                lore.add("<red>" + m.runtimeErrorCount() + " erreur(s) à l'exécution");
            }
            if (m.lastError() != null) {
                lore.add("<red>✘ " + esc(Msg.cut(m.lastError(), 60)));
            }
            Optional<AiJob> job = ai.job(m.id());
            if (job.isPresent() && isActive(job.get().state())) {
                lore.add("<light_purple>🤖 l'IA travaille dessus…");
            }
            lore.add("");
            boolean disabled = m.state() == ModuleState.DISABLED;
            lore.add("<yellow>Clic gauche</yellow> <gray>: " + (disabled ? "activer" : "recharger"));
            lore.add("<yellow>Clic droit</yellow> <gray>: " + (disabled ? "activer" : "désactiver"));
            lore.add("<yellow>Shift + clic</yellow> <gray>: erreurs dans le chat");
            h.inventory.setItem(slot, icon(materialFor(m.state()), "<white><b>" + esc(m.id()) + "</b> <gray>v" + m.generation(), lore));
            // encoded as "<left>|<right>|<shift>"
            h.actions[slot] = (disabled ? "enable " : "reload ") + m.id() + "|"
                    + (disabled ? "enable " : "disable ") + m.id() + "|errors " + m.id();
            slot++;
        }
        if (list.isEmpty()) {
            h.inventory.setItem(22, icon(Material.BOOK, "<gray>Aucun module pour l'instant",
                    List.of("<white>Demande une fonctionnalité à l'IA :", "<aqua>/vibe ai <id> <demande>")));
        }

        long newIdeas = ideas.list().stream().filter(i -> "NEW".equals(i.status())).count();
        h.inventory.setItem(SLOT_IDEAS, icon(Material.WRITABLE_BOOK, "<yellow><b>💡 Idées du public",
                List.of("<white>" + newIdeas + " nouvelle(s) idée(s)", "", "<yellow>Clic</yellow> <gray>: ouvrir")));
        h.actions[SLOT_IDEAS] = "#ideas";

        List<String> jobLore = new ArrayList<>();
        if (!ai.available()) {
            jobLore.add("<red>IA indisponible :");
            jobLore.add("<gray>" + esc(Msg.cut(ai.unavailableReason(), 60)));
        } else {
            List<AiJob> active = ai.jobs().stream().filter(j -> isActive(j.state())).toList();
            jobLore.add("<white>" + active.size() + " tâche(s) en cours");
            for (AiJob j : active) {
                String last = j.progress().isEmpty() ? "" : j.progress().get(j.progress().size() - 1);
                jobLore.add("<light_purple>" + esc(j.moduleId()) + "</light_purple> <gray>" + esc(Msg.cut(last, 45)));
            }
        }
        h.inventory.setItem(SLOT_JOBS, icon(Material.AMETHYST_SHARD, "<light_purple><b>🤖 Tâches IA", jobLore));
        h.actions[SLOT_JOBS] = "jobs||";

        if (claudeAction != null) {
            h.inventory.setItem(SLOT_CLAUDE, icon(Material.ENCHANTED_BOOK, "<light_purple><b>💬 Claude",
                    List.of("<gray>Conversations avec l'IA :", "<gray>lire, écrire, arrêter", "", "<yellow>Clic</yellow> <gray>: ouvrir")));
            h.actions[SLOT_CLAUDE] = "#claude";
        }

        h.inventory.setItem(SLOT_RELOAD_ALL, icon(Material.CLOCK, "<green><b>⟳ Tout recharger",
                List.of("<gray>Recompile tous les modules")));
        h.actions[SLOT_RELOAD_ALL] = "reload all||";

        h.inventory.setItem(SLOT_DASHBOARD, icon(Material.MAP, "<aqua><b>🌐 Dashboard",
                List.of("<gray>Envoie le lien dans le chat")));
        h.actions[SLOT_DASHBOARD] = "#dashboard";

        h.inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER, "<red>Fermer", List.of()));
        h.actions[SLOT_CLOSE] = "#close";
    }

    private void renderIdeas(Holder h) {
        int slot = 0;
        for (Idea idea : ideas.list()) {
            if (slot >= MODULE_SLOTS) {
                break;
            }
            boolean fresh = "NEW".equals(idea.status());
            List<String> lore = new ArrayList<>();
            lore.add("<gray>de</gray> <white>" + esc(idea.author()) + "</white> <dark_gray>" + esc(Msg.ago(idea.at())));
            for (String line : wrap(idea.text(), 40)) {
                lore.add("<white>" + esc(line));
            }
            lore.add("");
            if (fresh) {
                lore.add("<yellow>Clic gauche</yellow> <gray>: envoyer à l'IA");
                lore.add("<yellow>Clic droit</yellow> <gray>: ignorer");
                h.actions[slot] = "accept " + idea.id() + "|dismiss " + idea.id() + "|accept " + idea.id();
            } else {
                lore.add("<gray>" + ("ACCEPTED".equals(idea.status())
                        ? "envoyée à l'IA → " + esc(String.valueOf(idea.moduleId())) : "ignorée"));
            }
            Material mat = fresh ? Material.PAPER : ("ACCEPTED".equals(idea.status()) ? Material.LIME_DYE : Material.GRAY_DYE);
            h.inventory.setItem(slot, icon(mat, "<yellow>#" + idea.id() + "</yellow> " + esc(Msg.cut(idea.text(), 30)), lore));
            slot++;
        }
        if (slot == 0) {
            h.inventory.setItem(22, icon(Material.BOOK, "<gray>Pas encore d'idée",
                    List.of("<white>Les joueurs proposent avec", "<aqua>/idea <leur idée>")));
        }
        h.inventory.setItem(SLOT_BACK, icon(Material.ARROW, "<white>← Retour aux modules", List.of()));
        h.actions[SLOT_BACK] = "#back";
        h.inventory.setItem(SLOT_CLOSE, icon(Material.BARRIER, "<red>Fermer", List.of()));
        h.actions[SLOT_CLOSE] = "#close";
    }

    // ------------------------------------------------------------------ clicks

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getClickedInventory() != h.inventory) {
            return;
        }
        int slot = event.getRawSlot();
        String action = slot >= 0 && slot < SIZE ? h.actions[slot] : null;
        if (action == null) {
            return;
        }
        switch (action) {
            case "#close" -> player.closeInventory();
            case "#ideas" -> open(player, Page.IDEAS);
            case "#claude" -> {
                java.util.function.Consumer<Player> c = claudeAction;
                player.closeInventory();
                if (c != null) {
                    c.accept(player);
                }
            }
            case "#back" -> open(player, Page.MODULES);
            case "#dashboard" -> {
                player.closeInventory();
                player.performCommand("vibe dashboard");
            }
            default -> {
                String[] parts = action.split("\\|", -1);
                ClickType click = event.getClick();
                String cmd = click.isShiftClick() && parts.length > 2 && !parts[2].isEmpty() ? parts[2]
                        : click.isRightClick() && parts.length > 1 && !parts[1].isEmpty() ? parts[1]
                        : parts[0];
                if (cmd.startsWith("errors") || cmd.startsWith("jobs") || cmd.startsWith("accept")) {
                    player.closeInventory(); // the answer is in the chat
                }
                player.performCommand("vibe " + cmd);
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.getOpenInventory().getTopInventory() == h.inventory) {
                        render(h);
                    }
                }, 2L);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static boolean isActive(AiJobState s) {
        return s == AiJobState.RUNNING || s == AiJobState.QUEUED;
    }

    private static Material materialFor(ModuleState s) {
        return switch (s) {
            case ENABLED -> Material.LIME_CONCRETE;
            case COMPILING -> Material.LIGHT_BLUE_CONCRETE;
            case FAILED -> Material.RED_CONCRETE;
            case TRIPPED -> Material.ORANGE_CONCRETE;
            case DISABLED -> Material.GRAY_CONCRETE;
            case DISCOVERED -> Material.WHITE_CONCRETE;
        };
    }

    private static ItemStack icon(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(noItalic(Msg.mm(name)));
        List<Component> lines = new ArrayList<>(lore.size());
        for (String l : lore) {
            lines.add(noItalic(Msg.mm(l)));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private static Component noItalic(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static String esc(String s) {
        return s == null ? "" : Msg.MM.escapeTags(s);
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() > 0 && line.length() + word.length() + 1 > width) {
                out.add(line.toString());
                line.setLength(0);
                if (out.size() == 4) {
                    out.add("…");
                    return out;
                }
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }
}
