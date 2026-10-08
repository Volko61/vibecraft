package dev.vibecraft.command;

import java.util.Locale;

import dev.vibecraft.ai.AiJobState;
import dev.vibecraft.core.ModuleState;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

/** French, colourful (MiniMessage) message helpers shared by the commands and the notifier. */
public final class Msg {

    public static final MiniMessage MM = MiniMessage.miniMessage();

    /** Chat prefix. */
    public static final String PREFIX = "<gradient:#c084fc:#22d3ee><b>VibeCraft</b></gradient> <dark_gray>»</dark_gray> ";

    private Msg() {
    }

    /** Parse a MiniMessage template. User-provided text must be passed through {@link #text(String, String)} placeholders. */
    public static Component mm(String template, TagResolver... resolvers) {
        return MM.deserialize(template, resolvers);
    }

    /** Same as {@link #mm} with the VibeCraft prefix. */
    public static Component pmm(String template, TagResolver... resolvers) {
        return MM.deserialize(PREFIX + template, resolvers);
    }

    /** Placeholder inserting raw (unparsed, tag-safe) user text. */
    public static TagResolver text(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    /** Placeholder inserting a component. */
    public static TagResolver comp(String key, Component value) {
        return Placeholder.component(key, value);
    }

    /** A clickable "[label]" that runs {@code command} on click. */
    public static Component button(String label, TextColor color, String command, String hover) {
        return Component.text("[" + label + "]", color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    /** A clickable "[label]" that puts {@code command} in the chat box. */
    public static Component suggest(String label, TextColor color, String command, String hover) {
        return Component.text("[" + label + "]", color)
                .clickEvent(ClickEvent.suggestCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(hover, NamedTextColor.GRAY)));
    }

    public static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    /** Shorten to {@code max} chars (single line). */
    public static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        String one = s.replace('\r', ' ').replace('\n', ' ').replace('\t', ' ').strip();
        if (one.length() <= max) {
            return one;
        }
        return one.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }

    /** "il y a 5 s", "il y a 3 min", "il y a 2 h", "il y a 4 j"; "jamais" for 0. */
    public static String ago(long epochMs) {
        if (epochMs <= 0) {
            return "jamais";
        }
        long s = Math.max(0, (System.currentTimeMillis() - epochMs) / 1000);
        if (s < 60) {
            return "il y a " + s + " s";
        }
        if (s < 3600) {
            return "il y a " + (s / 60) + " min";
        }
        if (s < 86400) {
            return "il y a " + (s / 3600) + " h";
        }
        return "il y a " + (s / 86400) + " j";
    }

    /** "0:42", "12:05", "1:02:03". */
    public static String duration(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s >= 3600) {
            return String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        }
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    public static String stateColor(ModuleState state) {
        if (state == null) {
            return "gray";
        }
        return switch (state) {
            case ENABLED -> "green";
            case COMPILING -> "yellow";
            case DISCOVERED -> "gray";
            case DISABLED -> "dark_gray";
            case FAILED -> "red";
            case TRIPPED -> "gold";
        };
    }

    public static String stateLabel(ModuleState state) {
        if (state == null) {
            return "inconnu";
        }
        return switch (state) {
            case ENABLED -> "actif";
            case COMPILING -> "compilation…";
            case DISCOVERED -> "découvert";
            case DISABLED -> "désactivé";
            case FAILED -> "en échec";
            case TRIPPED -> "disjoncté";
        };
    }

    public static String jobColor(AiJobState state) {
        if (state == null) {
            return "gray";
        }
        return switch (state) {
            case QUEUED -> "gray";
            case RUNNING -> "aqua";
            case SUCCEEDED -> "green";
            case FAILED -> "red";
            case STOPPED -> "gold";
        };
    }

    public static String jobLabel(AiJobState state) {
        if (state == null) {
            return "?";
        }
        return switch (state) {
            case QUEUED -> "en file";
            case RUNNING -> "en cours";
            case SUCCEEDED -> "terminé";
            case FAILED -> "échoué";
            case STOPPED -> "arrêté";
        };
    }
}
