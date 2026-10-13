package vibe.ai_npc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.OpenRouter;
import dev.vibecraft.api.VibeModule;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.entity.Villager;

/**
 * AI NPC through OpenRouter (the server's key, never visible to the module): Merlin answers players
 * who start a chat message with "Merlin". The request runs in the background (OpenRouter.chat returns
 * a CompletableFuture completed OFF the main thread), so the answer hops back with ctx.later(0, ...).
 * One short conversation history per player. /merlin [player] spawns him.
 */
public class AiNpcModule implements VibeModule {

    private static final String PERSONA = "Tu es Merlin, un vieil enchanteur farceur dans un monde Minecraft. "
            + "Réponds en français, en UNE ou DEUX phrases courtes (moins de 200 caractères), avec humour, sans emoji.";

    private Villager merlin;
    private final Map<UUID, List<OpenRouter.Message>> history = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("merlin", "Fait apparaître Merlin, le PNJ IA", (sender, args) -> {
            Player p = args.length > 0 ? Bukkit.getPlayerExact(args[0]) : sender instanceof Player pl ? pl : null;
            if (p == null) {
                sender.sendRichMessage("<red>Usage : /merlin [joueur]");
                return;
            }
            if (merlin != null && merlin.isValid()) {
                merlin.remove();
            }
            Location at = p.getLocation().add(p.getLocation().getDirection().setY(0).normalize().multiply(3));
            merlin = ctx.entity(p.getWorld().spawn(at, Villager.class, v -> {
                v.customName(ctx.mm("<light_purple>Merlin <gray>(écris « Merlin, ... » dans le chat)"));
                v.setCustomNameVisible(true);
                v.setAI(false);
                v.setInvulnerable(true);
                v.setPersistent(false);
                v.setProfession(Villager.Profession.LIBRARIAN);
            }));
            sender.sendRichMessage(OpenRouter.available() ? "<green>Merlin est là."
                    : "<yellow>Merlin est là, mais sans clé OpenRouter il ne pourra pas répondre (plugins/VibeCraft/secrets.yml).");
        }, (sender, args) -> args.length == 1 ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList() : List.of());

        // AsyncChatEvent runs OFF the main thread: only read data here, hop back with ctx.later(0, ...)
        ctx.on(AsyncChatEvent.class, e -> {
            String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).strip();
            if (!msg.toLowerCase().startsWith("merlin")) {
                return;
            }
            Player p = e.getPlayer();
            ctx.later(0, () -> ask(ctx, p, msg));
        });
    }

    /** Main thread. */
    private void ask(ModuleContext ctx, Player p, String question) {
        if (merlin == null || !merlin.isValid()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - cooldown.getOrDefault(p.getUniqueId(), 0L) < 3000) {
            p.sendRichMessage("<gray>Merlin réfléchit encore...");
            return;
        }
        cooldown.put(p.getUniqueId(), now);
        List<OpenRouter.Message> h = history.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        h.add(OpenRouter.Message.user(p.getName() + " : " + question));
        while (h.size() > 8) {
            h.remove(0);
        }
        List<OpenRouter.Message> conversation = new ArrayList<>();
        conversation.add(OpenRouter.Message.system(PERSONA));
        conversation.addAll(h);
        merlin.getWorld().spawnParticle(Particle.ENCHANT, merlin.getLocation().add(0, 2, 0), 30, 0.4, 0.4, 0.4, 0.5);

        OpenRouter.chat(conversation)
                .thenAccept(answer -> ctx.later(0, () -> {          // back on the main thread
                    String text = answer.replace('\n', ' ').strip();
                    if (text.length() > 220) {
                        text = text.substring(0, 220) + "...";
                    }
                    h.add(OpenRouter.Message.assistant(text));
                    say(ctx, text);
                }))
                .exceptionally(err -> {
                    ctx.later(0, () -> say(ctx, "Ma boule de cristal est voilée... (" + OpenRouter.errorMessage(err) + ")"));
                    return null;
                });
    }

    private void say(ModuleContext ctx, String text) {
        if (merlin == null || !merlin.isValid()) {
            return;
        }
        var line = MiniMessage.miniMessage().deserialize("<light_purple>[Merlin]</light_purple> <white><t>", Placeholder.unparsed("t", text));
        for (Player pl : merlin.getLocation().getNearbyPlayers(30)) {
            pl.sendMessage(line);
        }
        merlin.getWorld().playSound(merlin.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 1f, 0.8f);
        TextDisplay bubble = ctx.entity(merlin.getWorld().spawn(merlin.getLocation().add(0, 2.6, 0), TextDisplay.class, t -> {
            t.text(MiniMessage.miniMessage().deserialize("<white><t>", Placeholder.unparsed("t", text)));
            t.setBillboard(Display.Billboard.CENTER);
            t.setLineWidth(160);
            t.setPersistent(false);
        }));
        ctx.later(20 * 6, bubble::remove);
    }
}
