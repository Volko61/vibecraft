package vibe.boss_bar;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

/** /countdown <seconds> [text...] : a boss bar countdown for everyone. Shows ctx.track() for cleanup. */
public class BossBarModule implements VibeModule {

    private BossBar bar;        // running countdown bar, or null
    private BukkitTask timer;

    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("countdown", "Compte à rebours en boss bar", (sender, args) -> {
            if (args.length == 0) {
                sender.sendRichMessage("<red>Usage : /countdown <secondes|stop> [texte]");
                return;
            }
            if (args[0].equalsIgnoreCase("stop")) {
                stop();
                return;
            }
            int seconds;
            try {
                seconds = Math.clamp(Integer.parseInt(args[0]), 3, 600);
            } catch (NumberFormatException ex) {
                sender.sendRichMessage("<red>Ce n'est pas un nombre : " + MiniMessage.miniMessage().escapeTags(args[0]));
                return;
            }
            String text = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "Compte à rebours";
            start(ctx, seconds, text);
        }, (sender, args) -> args.length == 1 ? List.of("10", "30", "60", "stop") : List.of());

        // Players who join during a countdown see it too.
        ctx.on(PlayerJoinEvent.class, e -> {
            if (bar != null) {
                e.getPlayer().showBossBar(bar);
            }
        });
    }

    private void start(ModuleContext ctx, int total, String text) {
        stop();
        BossBar b = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.NOTCHED_10);
        // track(): if the module is reloaded/disabled mid-countdown, the bar is hidden from everyone.
        bar = ctx.track(b, x -> Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(x)));
        Bukkit.getOnlinePlayers().forEach(p -> p.showBossBar(b));

        int[] left = {total};
        update(b, text, left[0], total);
        timer = ctx.every(20, () -> {
            left[0]--;
            if (left[0] <= 0) {
                stop();
                Title go = Title.title(ctx.mm("<gold><bold>GO !"), Component.empty(),
                        Title.Times.times(Duration.ZERO, Duration.ofSeconds(2), Duration.ofMillis(500)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(go);
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
                }
                return;
            }
            update(b, text, left[0], total);
            if (left[0] <= 5) {
                Bukkit.getOnlinePlayers().forEach(p -> p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.5f));
            }
        });
    }

    private static void update(BossBar b, String text, int left, int total) {
        // Player-typed text goes through an unparsed placeholder (no MiniMessage injection).
        b.name(MiniMessage.miniMessage().deserialize("<yellow><text></yellow> <white><bold><left> s",
                Placeholder.unparsed("text", text), Placeholder.unparsed("left", String.valueOf(left))));
        b.progress(Math.clamp((float) left / total, 0f, 1f));
        b.color(left <= 5 ? BossBar.Color.RED : left * 2 <= total ? BossBar.Color.YELLOW : BossBar.Color.GREEN);
    }

    private void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        if (bar != null) {
            BossBar b = bar;
            Bukkit.getOnlinePlayers().forEach(p -> p.hideBossBar(b));
            bar = null;
        }
    }
}
