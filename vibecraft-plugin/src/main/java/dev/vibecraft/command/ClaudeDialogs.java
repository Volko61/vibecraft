package dev.vibecraft.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import dev.vibecraft.ai.AiJob;
import dev.vibecraft.ai.ConversationStore;
import dev.vibecraft.ai.Conversations;
import dev.vibecraft.core.ModuleState;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
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
 * In-game AI interface built on the native dialog screens of the 26.3 client (Paper dialog API, no mod). Every
 * screen is titled {@link #APP_TITLE}; the audience never sees a product / provider name (only "l'IA").
 * <ul>
 *   <li>list: legend (green = done, orange = running, red = error) and one button per conversation (= module:
 *       coloured dot, id, title; last line in the tooltip), then "Nouvelle conversation";</li>
 *   <li>new: the request (multiline) and the "Images/3D : mode avancé" toggle (off = automatic image generator,
 *       on = "meshy"); the module id is derived from the request ({@link Slug}, made unique by
 *       {@link Conversations#freeId});</li>
 *   <li>conversation: the recent messages rendered compactly (user / assistant text, thinking collapsed with a
 *       button to expand, tool steps as short lines, status lines). While idle: a "Votre message" input + Envoyer
 *       (follow-up in the same session). While a job runs: Arrêter, and the dialog is re-sent every ~2 s when the
 *       conversation changed (no text input then, so nothing typed is lost), and once more when it ends.</li>
 * </ul>
 * Buttons send a custom click {@code vibecraft:claude} with {a: action, c: conversation} plus the input values;
 * handled in {@link PlayerCustomClickEvent} (hopped to the main thread). Escape = the exit action (close).
 * <p>
 * Note: the small warning icon next to the title is drawn by the vanilla client on every server-provided dialog
 * ({@code DialogScreen.init} always adds it); servers cannot remove it.
 */
public final class ClaudeDialogs implements Listener {

    static final Key KEY = Key.key("vibecraft", "claude");
    static final String APP_TITLE = "Aywen recrée vos idées dans Minecraft";
    /** Title purple (same family as the web dashboard accent). */
    static final TextColor PURPLE = TextColor.color(0xc084fc);
    static final TextColor GREEN = TextColor.color(0x55e07a);
    static final TextColor ORANGE = TextColor.color(0xffa53d);
    static final TextColor RED = TextColor.color(0xff5555);

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
    private final Map<UUID, View> views = new ConcurrentHashMap<>();

    public ClaudeDialogs(JavaPlugin plugin, Conversations conv) {
        this.plugin = plugin;
        this.conv = conv;
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
        Boolean advanced = bool(r, "advanced");
        // Buttons use WAIT_FOR_RESPONSE: the client shows "En attente du serveur" until we send a dialog back,
        // so every path must answer quickly, even on errors.
        Runnable task = () -> {
            try {
                handle(p, a, c, text, advanced);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("dialog action " + a + " failed: " + ex);
                try {
                    openList(p);
                } catch (RuntimeException ignored) {
                    p.closeDialog();
                }
            }
        };
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

    /** A boolean input: a byte tag as the client sends it, or "true" / "false" text; null if absent. */
    private static Boolean bool(DialogResponseView r, String k) {
        if (r == null) {
            return null;
        }
        try {
            Boolean b = r.getBoolean(k);
            if (b != null) {
                return b;
            }
        } catch (RuntimeException ignored) {
            // not a byte tag
        }
        String t = get(r, k);
        return t == null ? null : t.equals("true") || t.equals("1");
    }

    private void handle(Player p, String a, String c, String text, Boolean advanced) {
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
                // The "Fermer" button left the client on its "En attente du serveur" screen, which a plain
                // clear-dialog packet does not dismiss: replace it with a dialog first, then clear that one.
                p.showDialog(waitDialog("Fermeture…"));
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
        if (advanced != null) {
            v.provider = advanced ? "meshy" : "auto"; // standard = automatic choice, advanced = the 3D-capable generator
        }
        boolean validConv = c != null && conv.validId(c);
        switch (a) {
            case "list" -> openList(p);
            case "new" -> {
                v.conv = null;
                v.live = false;
                if (advanced == null) {
                    v.provider = "auto";
                }
                p.showDialog(newDialog(v, null, text == null ? "" : text));
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
            case "delete" -> {
                if (validConv) {
                    v.live = false;
                    p.showDialog(deleteDialog(c));
                }
            }
            case "delete-confirm" -> {
                if (validConv) {
                    v.conv = null;
                    v.live = false;
                    p.showDialog(waitDialog("Suppression du module " + c + "…"));
                    plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                        String error = null;
                        try {
                            conv.deleteModule(c);
                        } catch (Exception ex) {
                            error = "Suppression incomplète : " + ex.getMessage();
                            plugin.getLogger().warning("delete module " + c + ": " + ex);
                        }
                        String msg = error;
                        plugin.getServer().getScheduler().runTask(plugin, () -> {
                            if (!p.isOnline()) {
                                return;
                            }
                            if (msg == null) {
                                p.sendRichMessage("<gray>🗑 Module <white>" + c + "</white> supprimé.");
                            } else {
                                p.sendRichMessage("<red>" + Msg.MM.escapeTags(msg));
                            }
                            openList(p);
                        });
                    });
                }
            }
            case "create" -> send(p, v, null, text, true);
            default -> openList(p);
        }
    }

    /** @param target the conversation, or null for a new one (id derived from the request, unique) */
    private void send(Player p, View v, String target, String text, boolean fromNew) {
        String t = text == null ? "" : text.strip();
        String provider = v.provider;
        if (t.isEmpty()) {
            if (fromNew) {
                p.showDialog(newDialog(v, "Écrivez d'abord votre demande.", ""));
            } else {
                v.error = "Écrivez d'abord votre message.";
                openConversation(p, target);
            }
            return;
        }
        String by = p.getName();
        p.showDialog(waitDialog("Envoi de votre demande à l'IA…"));
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String id = target;
            AiJob job = null;
            String err = null;
            try {
                if (id == null) {
                    id = conv.freeId(Slug.of(t, "demande"));
                }
                job = conv.send(id, t, provider, "", by);
            } catch (IllegalArgumentException | IllegalStateException e) {
                err = e.getMessage();
            } catch (RuntimeException e) {
                err = "erreur interne : " + e;
            }
            String error = err;
            String sent = id;
            AiJob started = job;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) {
                    return;
                }
                if (error != null && fromNew) {
                    p.showDialog(newDialog(v, error, t));
                    return;
                }
                v.conv = sent;
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
            TextColor col = entryColor(e.state(), e.moduleState());
            Component label = Component.text("● ", col)
                    .append(Component.text(e.id(), NamedTextColor.WHITE))
                    .append(Component.text(t, NamedTextColor.GRAY));
            Component tip = Component.text(e.title(), NamedTextColor.WHITE)
                    .append(Component.newline())
                    .append(Component.text(entryLabel(e.state(), e.moduleState()) + (e.generation() > 0 ? " · v" + e.generation() : ""), col))
                    .append(Component.newline())
                    .append(Component.text(Msg.cut(e.lastLine(), 120), NamedTextColor.GRAY));
            buttons.add(button(label, tip, 310, "open", e.id()));
        }
        buttons.add(button(Component.text("Nouvelle conversation", PURPLE).decorate(TextDecoration.BOLD),
                Component.text("Faire une nouvelle demande à l'IA"), 310, "new", null));
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Liste des demandes faites à l'IA :", NamedTextColor.GRAY), 320));
        body.add(DialogBody.plainMessage(legend(), 320));
        if (!conv.ai().available()) {
            body.add(DialogBody.plainMessage(Component.text("IA indisponible : " + Msg.cut(conv.ai().unavailableReason(), 160), RED), 320));
        }
        if (list.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("Aucune demande pour l'instant.", NamedTextColor.DARK_GRAY), 320));
        } else if (list.size() > MAX_LIST) {
            body.add(DialogBody.plainMessage(Component.text("… et " + (list.size() - MAX_LIST) + " autre(s) (voir le dashboard)",
                    NamedTextColor.DARK_GRAY), 320));
        }
        DialogBase base = base(body, List.of());
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons)
                .exitAction(button(Component.text("Fermer"), null, 100, "close", null)).columns(1).build()));
    }

    /** "● vert = terminé  ● orange = en cours  ● rouge = erreur" with coloured dots. */
    private static Component legend() {
        return Component.text()
                .append(Component.text("● ", GREEN)).append(Component.text("vert = terminé   ", NamedTextColor.GRAY))
                .append(Component.text("● ", ORANGE)).append(Component.text("orange = en cours   ", NamedTextColor.GRAY))
                .append(Component.text("● ", RED)).append(Component.text("rouge = erreur", NamedTextColor.GRAY))
                .build();
    }

    /** Shown instantly while a slow action runs (avoids the client's "En attente du serveur" screen). */
    private Dialog waitDialog(String message) {
        DialogBase base = base(List.of(DialogBody.plainMessage(Component.text("⏳ " + message, NamedTextColor.GRAY), 320)), List.of());
        List<ActionButton> buttons = List.of(button(Component.text("Retour"), Component.text("Liste des demandes"), 150, "list", null));
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons)
                .exitAction(button(Component.text("Fermer"), null, 100, "close", null)).columns(1).build()));
    }

    private Dialog deleteDialog(String id) {
        List<DialogBody> body = List.of(
                DialogBody.plainMessage(Component.text("Supprimer le module ", NamedTextColor.WHITE)
                        .append(Component.text(id, RED).decorate(TextDecoration.BOLD))
                        .append(Component.text(" ?", NamedTextColor.WHITE)), 320),
                DialogBody.plainMessage(Component.text("Sa fonctionnalité disparaît du jeu, et son code, ses données et cette "
                        + "conversation sont effacés définitivement.", NamedTextColor.GRAY), 320));
        DialogBase base = base(body, List.of());
        List<ActionButton> buttons = List.of(
                button(Component.text("Annuler"), Component.text("Revenir à la conversation"), 150, "open", id),
                button(Component.text("Supprimer", RED).decorate(TextDecoration.BOLD), Component.text("Supprimer définitivement"),
                        150, "delete-confirm", id));
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons)
                .exitAction(button(Component.text("Fermer"), null, 100, "close", null)).columns(2).build()));
    }

    private Dialog newDialog(View v, String error, String text) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("Décrivez votre demande d'ajout à l'IA", NamedTextColor.GRAY), 320));
        if (error != null) {
            body.add(DialogBody.plainMessage(Component.text(error, RED), 320));
        }
        List<DialogInput> inputs = List.of(
                DialogInput.text("text", Component.text("Votre demande"))
                        .width(300).maxLength(4000).initial(text == null ? "" : text)
                        .multiline(TextDialogInput.MultilineOptions.create(12, 110)).build(),
                advancedInput(v.provider));
        DialogBase base = base(body, inputs);
        List<ActionButton> buttons = List.of(
                button(Component.text("Retour"), Component.text("Liste des demandes"), 150, "list", null),
                button(Component.text("Envoyer", GREEN).decorate(TextDecoration.BOLD), Component.text("Envoyer la demande à l'IA"),
                        150, "create", null));
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons)
                .exitAction(button(Component.text("Fermer"), null, 100, "close", null)).columns(2).build()));
    }

    private Dialog conversationDialog(View v, String state) {
        String id = v.conv;
        Conversations.Entry e = conv.entry(id).orElse(null);
        ModuleState ms = e == null ? null : e.moduleState();
        TextColor col = entryColor(state, ms);
        List<DialogBody> body = new ArrayList<>();
        Component head = Component.text("● ", col)
                .append(Component.text(id, NamedTextColor.WHITE))
                .append(Component.text("   " + entryLabel(state, ms), col));
        if (ms != null) {
            head = head.append(Component.text("   module " + Msg.stateLabel(ms) + (e.generation() > 0 ? " v" + e.generation() : ""),
                    NamedTextColor.GRAY));
        }
        body.add(DialogBody.plainMessage(head, 400));
        if (v.error != null) {
            body.add(DialogBody.plainMessage(Component.text(v.error, RED), 400));
        }
        List<ConversationStore.MessageView> msgs = conv.store().messages(id);
        if (msgs.isEmpty()) {
            body.add(DialogBody.plainMessage(Component.text("Pas encore de message : écrivez votre demande ci-dessous.",
                    NamedTextColor.DARK_GRAY), 400));
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
            inputs.add(DialogInput.text("text", Component.text("Votre message"))
                    .width(400).maxLength(4000).initial(v.draft == null ? "" : v.draft)
                    .multiline(TextDialogInput.MultilineOptions.create(10, 80)).build());
            inputs.add(advancedInput(v.provider));
        }
        DialogBase base = base(body, inputs);
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button(Component.text("Retour"), Component.text("Liste des demandes"), 150, "list", null));
        if (busy) {
            buttons.add(button(Component.text("Arrêter", RED).decorate(TextDecoration.BOLD), Component.text("Arrêter le travail de l'IA"),
                    150, "stop", id));
        } else {
            buttons.add(button(Component.text("Envoyer", GREEN).decorate(TextDecoration.BOLD),
                    Component.text("Envoyer le message à l'IA (suite de la conversation)"), 150, "send", id));
        }
        buttons.add(button(Component.text(v.thinking ? "Masquer les réflexions" : "Voir les réflexions", NamedTextColor.WHITE),
                null, 150, "think", id));
        buttons.add(button(Component.text("Rafraîchir", NamedTextColor.GRAY), null, 150, "open", id));
        buttons.add(button(Component.text("Supprimer le module", RED), Component.text("Effacer ce module, son code et cette conversation"),
                150, "delete", id));
        return Dialog.create(b -> b.empty().base(base).type(DialogType.multiAction(buttons)
                .exitAction(button(Component.text("Fermer"), null, 100, "close", null)).columns(2).build()));
    }

    private static DialogBase base(List<DialogBody> body, List<DialogInput> inputs) {
        return DialogBase.builder(Component.text(APP_TITLE, PURPLE).decorate(TextDecoration.BOLD))
                .externalTitle(Component.text(APP_TITLE))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.WAIT_FOR_RESPONSE)
                .body(body)
                .inputs(inputs)
                .build();
    }

    /** Off = standard (automatic image generator), on = advanced (the generator that also makes 3D objects). */
    private static DialogInput advancedInput(String provider) {
        return DialogInput.bool("advanced", Component.text("Images/3D : mode avancé"))
                .initial("meshy".equals(provider)).onTrue("true").onFalse("false").build();
    }

    /** One message as a compact multi-line component. */
    static Component render(ConversationStore.MessageView m, boolean thinking) {
        List<Component> lines = new ArrayList<>();
        List<ConversationStore.BlockView> blocks = m.blocks();
        int from = Math.max(0, blocks.size() - MAX_LINES_PER_MESSAGE);
        if (from > 0) {
            lines.add(Component.text("… " + from + " étape(s) plus tôt", NamedTextColor.DARK_GRAY));
        }
        for (ConversationStore.BlockView b : blocks.subList(from, blocks.size())) {
            Component line = switch (b.type()) {
                case "text" -> m.role().equals("user")
                        ? Component.text("Vous : ", NamedTextColor.AQUA).append(Component.text(Msg.cut(b.text(), 400), NamedTextColor.WHITE))
                        : Component.text("IA : ", PURPLE)
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

    /** Red if the last job failed or the module failed to load, orange while running / queued, green otherwise. */
    static TextColor entryColor(String state, ModuleState moduleState) {
        return switch (state) {
            case "running", "queued" -> ORANGE;
            case "failed" -> RED;
            default -> moduleState == ModuleState.FAILED ? RED : GREEN;
        };
    }

    static String entryLabel(String state, ModuleState moduleState) {
        return switch (state) {
            case "running" -> "l'IA travaille…";
            case "queued" -> "en file d'attente";
            case "failed" -> "dernière tâche échouée";
            default -> moduleState == ModuleState.FAILED ? "erreur au chargement du module" : "terminé";
        };
    }
}
