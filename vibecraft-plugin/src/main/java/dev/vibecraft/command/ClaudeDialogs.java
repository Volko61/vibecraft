package dev.vibecraft.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import dev.vibecraft.ai.AiJob;
import dev.vibecraft.ai.ConversationStore;
import dev.vibecraft.ai.Conversations;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * In-game "Claude" interface built on the native dialog screens of the 26.3 client (Paper dialog API, no mod):
 * <ul>
 *   <li>list: one button per conversation (= module: state icon, id, title; last line in the tooltip),
 *       "Nouvelle conversation", "Modules" (the chest menu);</li>
 *   <li>conversation: the recent messages rendered compactly (user / assistant text, thinking collapsed as
 *       "💭 réflexion…" with a button to expand, tool steps as short lines, status lines), a multiline text input
 *       and an image provider selector (Auto / OpenRouter / Meshy), buttons Envoyer / Arrêter / Rafraîchir /
 *       Retour. While a job runs the dialog is re-sent every ~2 s when the conversation changed (no text input then,
 *       so nothing typed is lost), and once more when it ends.</li>
 * </ul>
 * Buttons send a custom click {@code vibecraft:claude} with {a: action, c: conversation} plus the input values;
 * handled in {@link PlayerCustomClickEvent} (hopped to the main thread). Escape = the exit action (close).
 */
public final class ClaudeDialogs implements Listener {

    static final Key KEY = Key.key("vibecraft", "claude");
    private static final int MAX_LIST = 24;
    private static final int MAX_MESSAGES = 8;
    private static final int MAX_LINES_PER_MESSAGE = 24;
    private static final long LIVE_MAX_MS = 20 * 60_000L;

    /** What a player currently sees. */
    private static final class View {
        String conv;          // null = list / new
        long rev;
        boolean live;         // shown while a job ran: refresh on change
        boolean thinking;     // thinking blocks expanded
        String provider = "auto";
        String error;
        String draft = "";
        long openedAt = System.currentTimeMillis();
        long shownAt;
    }

    private final JavaPlugin plugin;
    private final Conversations conv;
    private final Consumer<Player> openModulesMenu;
    private final Map<UUID, View> views = new ConcurrentHashMap<>();

    public ClaudeDialogs(JavaPlugin plugin, Conversations conv, Consumer<Player> openModulesMenu) {
        this.plugin = plugin;
        this.conv = conv;
        this.openModulesMenu = openModulesMenu;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::refreshLive, 40L, 40L);
    }

    // ================================================================== entry points (main thread)

    public void openList(Player p) {
        View v = view(p);
        v.conv = null;
        v.live = false;
        v.error = null;
        p.showDialog(listDialog());
    }

    public void openConversation(Player p, String id) {
        View v = view(p);
        if (!id.equals(v.conv)) {
            v.thinking = false;
            v.draft = "";
            v.error = null;
            v.provider = conv.imageProvider(id);
        }
        v.conv = id;
        show(p, v);
    }

    private View view(Player p) {
        return views.computeIfAbsent(p.getUniqueId(), u -> new View());
    }

    private void show(Player p, View v) {
        String st = conv.state(v.conv);
        v.live = st.equals("running") || st.equals("queued");
        v.rev = conv.store().rev(v.conv);
        v.shownAt = System.currentTimeMillis();
        if (v.live && System.currentTimeMillis() - v.openedAt > LIVE_MAX_MS) {
            v.openedAt = System.currentTimeMillis();
        }
        p.showDialog(conversationDialog(v, st));
    }

    /** Every 2 s: re-send live conversation dialogs whose conversation changed. */
    private void refreshLive() {
        long now = System.currentTimeMillis();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            View v = views.get(p.getUniqueId());
            if (v == null || v.conv == null || !v.live) {
                continue;
            }
            if (now - v.openedAt > LIVE_MAX_MS) {
                v.live = false;
                continue;
            }
            if (now - v.shownAt < 1800) {
                continue; // just shown (button click): no flicker
            }
            if (conv.store().rev(v.conv) != v.rev || !conv.state(v.conv).equals("running") && !conv.state(v.conv).equals("queued")) {
                try {
                    show(p, v);
                } catch (RuntimeException e) {
                    v.live = false;
                }
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        views.remove(e.getPlayer().getUniqueId());
    }

    // ================================================================== clicks

    @EventHandler
    public void onCustomClick(PlayerCustomClickEvent e) {
        if (!KEY.equals(e.getIdentifier())) {
            return;
        }
        if (!(e.getCommonConnection() instanceof PlayerGameConnection gc)) {
            return;
        }
        Player p = gc.getPlayer();
        DialogResponseView r = e.getDialogResponseView();
        String a = get(r, "a");
        String c = get(r, "c");
        String text = get(r, "text");
        String id = get(r, "id");
        String provider = get(r, "provider");
        Runnable task = () -> handle(p, a, c, text, id, provider);
        if (plugin.getServer().isPrimaryThread()) {
            task.run();
        } else {
            plugin.getServer().getScheduler().runTask(plugin, task);
        }
    }

    private static String get(DialogResponseView r, String k) {
        if (r == null) {
            return null;
        }
        try {
            return r.getText(k);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void handle(Player p, String a, String c, String text, String newId, String provider) {
        if (!p.isOnline()) {
            return;
        }
        if (a == null || a.equals("close")) {
            View v = views.get(p.getUniqueId());
            if (v != null) {
                v.live = false;
                v.conv = null;
            }
            if (a != null) {
                p.closeDialog();
            }
            return;
        }
        if (!p.hasPermission("vibecraft.admin")) {
            p.closeDialog();
            p.sendRichMessage("<red>Réservé aux organisateurs (vibecraft.admin).");
            return;
        }
        View v = view(p);
        if (provider != null && provider.matches("auto|openrouter|meshy")) {
            v.provider = provider;
        }
        boolean validConv = c != null && conv.validId(c);
        switch (a) {
            case "list" -> openList(p);
            case "new" -> {
                v.conv = null;
                v.live = false;
                p.showDialog(newDialog(v, null, "", text == null ? "" : text));
            }
            case "modules" -> {
                v.live = false;
                v.conv = null;
                p.closeDialog();
                openModulesMenu.accept(p);
            }
            case "open" -> {
                if (validConv) {
                    openConversation(p, c);
                } else {
                    openList(p);
                }
            }
            case "think" -> {
                if (validConv) {
                    v.thinking = !v.thinking;
                    if (text != null) {
                        v.draft = text;
                    }
                    openConversation(p, c);
                }
            }
            case "stop" -> {
                if (validConv) {
                    boolean ok = conv.stop(c);
                    v.error = ok ? null : "Aucune tâche en cours.";
                    openConversation(p, c);
                }
            }
            case "send" -> {
                if (validConv) {
                    send(p, v, c, text, false);
                }
            }
            case "create" -> {
                String id = newId == null ? "" : newId.strip().toLowerCase(Locale.ROOT).replace(' ', '-');
                if (id.isEmpty()) {
                    id = Slug.of(text == null ? "" : text, "conversation");
                }
                if (!conv.validId(id)) {
                    p.showDialog(newDialog(v, "Identifiant invalide : minuscules, chiffres, - ou _ (40 max).", id, text == null ? "" : text));
                    return;
                }
                send(p, v, id, text, true);
            }
            default -> openList(p);
        }
    }

    private void send(Player p, View v, String id, String text, boolean fromNew) {
        String t = text == null ? "" : text.strip();
        String provider = v.provider;
        if (t.isEmpty()) {
            if (fromNew) {
                p.showDialog(newDialog(v, "Écris d'abord ta demande.", id, ""));
            } else {
                v.error = "Écris d'abord ta demande.";
                openConversation(p, id);
            }
            return;
        }
        String by = p.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            AiJob job = null;
            String err = null;
            try {
                job = conv.send(id, t, provider, "", by);
            } catch (IllegalArgumentException | IllegalStateException e) {
                err = e.getMessage();
            } catch (RuntimeException e) {
                err = "erreur interne : " + e;
            }
            String error = err;
            AiJob started = job;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) {
                    return;
                }
                if (error != null && fromNew) {
                    p.showDialog(newDialog(v, error, id, t));
                    return;
                }
                v.conv = id;
                v.error = error;
                v.draft = error == null ? "" : t;
                if (started != null) {
                    v.openedAt = System.currentTimeMillis();
                }
                show(p, v);
            });
        });
    }

    // ================================================================== dialogs

    private Dialog listDialog() {
        List<ActionButton> buttons = new ArrayList<>();
        List<Conversations.Entry> list = conv.list();
        int n = 0;
        for (Conversations.Entry e : list) {
            if (n++ >= MAX_LIST) {
                break;
            }
            String t = e.title().equals(e.id()) ? "" : " — " + Msg.cut(e.title(), 26);
            Component label = Component.text(icon(e.state()) + " ", stateColor(e.state()))
                    .append(Component.text(e.id(), NamedTextColor.WHITE))
                    .append(Component.text(t, NamedTextColor.GRAY));
            Component tip = Component.text(e.title(), NamedTextColor.WHITE)
                    .append(Component.newline())
                    .append(Component.text(stateLabel(e.state()) + (e.generation() > 0 ? " · v" + e.generation() : ""), stateColor(e.state())))
                    .append(Component.newline())
                    .append(Component.text(Msg.cut(e.lastLine(), 120), NamedTextColor.GRAY));
            buttons.add(button(label, tip, 310, "open", e.id()));
        }
        buttons.add(button(Component.text("➕ Nouvelle conversation", NamedTextColor.LIGHT_PURPLE),
                Component.text("Demander une nouvelle fonctionnalité à Claude"), 150, "new", null));
        buttons.add(button(Component.text("📦 Modules", NamedTextColor.AQUA),
                Component.text("Le menu des modules (recharger, activer, erreurs…)"), 150, "modules", null));
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Une conversation par module. Clique pour l'ouvrir.", NamedTextColor.GRAY), 320));
        if (!conv.ai().available()) {
            body.add(DialogBody.plainMessage(Component.text("IA indisponible : " + Msg.cut(conv.ai().unavailableReason(), 160),
                    NamedTextColor.RED), 320));
        }
        if (list.size() > MAX_LIST) {
            body.add(DialogBody.plainMessage(Component.text("… et " + (list.size() - MAX_LIST) + " autre(s) (voir le dashboard)",
                    NamedTextColor.DARK_GRAY), 320));
        }
        DialogBase base = DialogBase.builder(title("Claude — conversations"))
                .externalTitle(Component.text("Claude"))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .build();
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons)
                .exitAction(button(Component.text("Fermer"), null, 100, "close", null)).columns(2).build()));
    }

    private Dialog newDialog(View v, String error, String id, String text) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Décris la fonctionnalité : Claude crée le module et le code en direct.",
                NamedTextColor.GRAY), 320));
        if (error != null) {
            body.add(DialogBody.plainMessage(Component.text("⚠ " + error, NamedTextColor.RED), 320));
        }
        List<DialogInput> inputs = List.of(
                DialogInput.text("id", Component.text("Identifiant du module (vide = d'après la demande)"))
                        .width(300).maxLength(40).initial(id == null ? "" : id).build(),
                DialogInput.text("text", Component.text("Ta demande"))
                        .width(300).maxLength(4000).initial(text == null ? "" : text)
                        .multiline(TextDialogInput.MultilineOptions.create(12, 110)).build(),
                providerInput(v.provider));
        DialogBase base = DialogBase.builder(title("Nouvelle conversation"))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(inputs)
                .build();
        List<ActionButton> buttons = List.of(
                button(Component.text("🚀 Envoyer à Claude", NamedTextColor.GREEN), null, 150, "create", null),
                button(Component.text("← Retour"), null, 150, "list", null));
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons)
                .exitAction(button(Component.text("Fermer"), null, 100, "close", null)).columns(2).build()));
    }

    private Dialog conversationDialog(View v, String state) {
        String id = v.conv;
        Conversations.Entry e = conv.entry(id).orElse(null);
        List<DialogBody> body = new ArrayList<>();
        Component head = Component.text(icon(state) + " " + stateLabel(state), stateColor(state));
        if (e != null && e.moduleState() != null) {
            head = head.append(Component.text("   module " + Msg.stateLabel(e.moduleState()) + (e.generation() > 0 ? " v" + e.generation() : ""),
                    NamedTextColor.GRAY));
        }
        body.add(DialogBody.plainMessage(head, 400));
        if (v.error != null) {
            body.add(DialogBody.plainMessage(Component.text("⚠ " + v.error, NamedTextColor.RED), 400));
        }
        List<ConversationStore.MessageView> msgs = conv.store().messages(id);
        if (msgs.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("Pas encore de message : écris ta demande ci-dessous.", NamedTextColor.DARK_GRAY), 400));
        } else if (msgs.size() > MAX_MESSAGES) {
            body.add(DialogBody.plainMessage(Component.text("… " + (msgs.size() - MAX_MESSAGES) + " message(s) plus ancien(s) (dashboard)",
                    NamedTextColor.DARK_GRAY), 400));
        }
        for (ConversationStore.MessageView m : msgs.subList(Math.max(0, msgs.size() - MAX_MESSAGES), msgs.size())) {
            body.add(DialogBody.plainMessage(render(m, v.thinking), 400));
        }
        boolean busy = state.equals("running") || state.equals("queued");
        List<DialogInput> inputs = new ArrayList<>();
        if (!busy) {
            inputs.add(DialogInput.text("text", Component.text("Nouveau message à Claude"))
                    .width(400).maxLength(4000).initial(v.draft == null ? "" : v.draft)
                    .multiline(TextDialogInput.MultilineOptions.create(10, 80)).build());
            inputs.add(providerInput(v.provider));
        }
        DialogBase base = DialogBase.builder(title("Claude — " + id))
                .externalTitle(Component.text("Claude"))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(inputs)
                .build();
        List<ActionButton> buttons = new ArrayList<>();
        if (busy) {
            buttons.add(button(Component.text("⏹ Arrêter", NamedTextColor.GOLD), Component.text("Arrêter la tâche de Claude"), 130, "stop", id));
        } else {
            buttons.add(button(Component.text("📨 Envoyer", NamedTextColor.GREEN), Component.text("Envoyer le message à Claude"), 130, "send", id));
        }
        buttons.add(button(Component.text("🔄 Rafraîchir"), null, 130, "open", id));
        buttons.add(button(Component.text(v.thinking ? "💭 Masquer les réflexions" : "💭 Voir les réflexions", NamedTextColor.GRAY),
                null, 130, "think", id));
        buttons.add(button(Component.text("← Retour"), Component.text("Toutes les conversations"), 130, "list", null));
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons)
                .exitAction(button(Component.text("Fermer"), null, 100, "close", null)).columns(2).build()));
    }

    private static SingleOptionDialogInput providerInput(String current) {
        String cur = current == null ? "auto" : current;
        return DialogInput.singleOption("provider", Component.text("Images / 3D"), List.of(
                        SingleOptionDialogInput.OptionEntry.create("auto", Component.text("Auto"), cur.equals("auto")),
                        SingleOptionDialogInput.OptionEntry.create("openrouter", Component.text("OpenRouter"), cur.equals("openrouter")),
                        SingleOptionDialogInput.OptionEntry.create("meshy", Component.text("Meshy"), cur.equals("meshy"))))
                .width(200).build();
    }

    /** One message as a compact multi-line component. */
    static Component render(ConversationStore.MessageView m, boolean thinking) {
        List<Component> lines = new ArrayList<>();
        int hidden = 0;
        List<ConversationStore.BlockView> blocks = m.blocks();
        int from = Math.max(0, blocks.size() - MAX_LINES_PER_MESSAGE);
        hidden = from;
        if (hidden > 0) {
            lines.add(Component.text("… " + hidden + " étape(s) plus tôt", NamedTextColor.DARK_GRAY));
        }
        for (ConversationStore.BlockView b : blocks.subList(from, blocks.size())) {
            Component line = switch (b.type()) {
                case "text" -> m.role().equals("user")
                        ? Component.text("👤 ", NamedTextColor.AQUA).append(Component.text(Msg.cut(b.text(), 400), NamedTextColor.WHITE))
                        : Component.text("🤖 ", NamedTextColor.LIGHT_PURPLE)
                        .append(Component.text(Msg.cut(clean(b.text()), 600), NamedTextColor.WHITE));
                case "thinking" -> thinking
                        ? Component.text("💭 " + Msg.cut(b.text(), 900), NamedTextColor.GRAY).decorate(TextDecoration.ITALIC)
                        : Component.text("💭 réflexion… (" + (b.text() == null ? 0 : b.text().length()) + " car.)", NamedTextColor.DARK_GRAY)
                        .decorate(TextDecoration.ITALIC);
                case "tool_use" -> Component.text("  🔧 " + b.name() + " ", NamedTextColor.DARK_AQUA)
                        .append(Component.text(Msg.cut(b.input() == null ? "" : b.input(), 100), NamedTextColor.GRAY));
                case "tool_result" -> Component.text("     ↳ " + (Boolean.FALSE.equals(b.ok()) ? "⚠ " : "") + Msg.cut(b.text() == null ? "" : b.text(), 90),
                        Boolean.FALSE.equals(b.ok()) ? NamedTextColor.RED : NamedTextColor.DARK_GRAY);
                case "status" -> Component.text(Msg.cut(b.text() == null ? "" : b.text(), 300),
                        Boolean.FALSE.equals(b.ok()) ? NamedTextColor.GOLD : NamedTextColor.GREEN);
                default -> Component.text(Msg.cut(String.valueOf(b.text()), 200), NamedTextColor.GRAY);
            };
            lines.add(line);
        }
        if (m.open()) {
            lines.add(Component.text("⏳ …", NamedTextColor.YELLOW));
        }
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace("**", "").replace("`", "");
    }

    private static ActionButton button(Component label, Component tooltip, int width, String action, String conversation) {
        StringBuilder snbt = new StringBuilder("{a:").append(quote(action));
        if (conversation != null) {
            snbt.append(",c:").append(quote(conversation));
        }
        snbt.append('}');
        ActionButton.Builder b = ActionButton.builder(label).width(width)
                .action(DialogAction.customClick(KEY, BinaryTagHolder.binaryTagHolder(snbt.toString())));
        if (tooltip != null) {
            b.tooltip(tooltip);
        }
        return b.build();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static Component title(String s) {
        return Component.text(s, TextColor.color(0xc084fc)).decorate(TextDecoration.BOLD);
    }

    static String icon(String state) {
        return switch (state) {
            case "running" -> "⏳";
            case "queued" -> "⌛";
            case "failed" -> "✘";
            default -> "●";
        };
    }

    static String stateLabel(String state) {
        return switch (state) {
            case "running" -> "Claude travaille…";
            case "queued" -> "en file d'attente";
            case "failed" -> "dernière tâche échouée";
            default -> "prêt";
        };
    }

    private static TextColor stateColor(String state) {
        return switch (state) {
            case "running" -> NamedTextColor.YELLOW;
            case "queued" -> NamedTextColor.GOLD;
            case "failed" -> NamedTextColor.RED;
            default -> NamedTextColor.GREEN;
        };
    }
}
