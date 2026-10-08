package dev.vibecraft.core.ctx;

import java.util.ArrayList;
import java.util.List;

import dev.vibecraft.api.CommandHandler;
import dev.vibecraft.api.TabHandler;
import dev.vibecraft.core.guard.Gen;
import dev.vibecraft.core.guard.Guard;
import dev.vibecraft.core.guard.ModuleKilledError;
import dev.vibecraft.core.guard.ModuleTimeoutError;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/** Bukkit command whose executor / tab completer run module code under the guard. */
final class ModuleCommand extends Command {

    private final ModuleContextImpl ctx;
    private final Gen gen;
    private final CommandHandler handler;
    private final TabHandler tab;
    private final String where;
    private final String tabWhere;

    ModuleCommand(String name, String description, List<String> aliases, ModuleContextImpl ctx, Gen gen,
                  CommandHandler handler, TabHandler tab) {
        super(name, description == null ? "" : description, "/" + name, new ArrayList<>(aliases));
        this.ctx = ctx;
        this.gen = gen;
        this.handler = handler;
        this.tab = tab;
        this.where = "command /" + name;
        this.tabWhere = "tab-complete /" + name;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (gen.killed()) {
            sender.sendMessage(Component.text("⚠ /" + label + " n'est plus disponible (module " + gen.moduleId
                    + " rechargé).", NamedTextColor.RED));
            return true;
        }
        Throwable t = Guard.invoke(gen, where, ctx.handlerBudget(), () -> handler.handle(sender, args));
        if (t != null && !(t instanceof ModuleKilledError)) {
            ctx.report(where, t);
            tellSender(sender, label, gen, t);
        }
        return true;
    }

    /** Short red message for the command sender after a guarded command failed. */
    static void tellSender(CommandSender sender, String label, Gen gen, Throwable t) {
        String msg;
        if (t instanceof ModuleKilledError) {
            msg = "⚠ /" + label + " n'est plus disponible (module " + gen.moduleId + " rechargé).";
        } else if (t instanceof ModuleTimeoutError mte && mte.budgetExceeded()) {
            msg = "⚠ /" + label + " a été interrompue : trop long (boucle infinie ?) - module " + gen.moduleId;
        } else if (t instanceof ModuleTimeoutError) {
            msg = "⚠ /" + label + " a été bloquée : elle aurait gelé le serveur (module " + gen.moduleId + ") - "
                    + abbreviate(String.valueOf(t.getMessage()), 100);
        } else {
            String m = t.getMessage();
            msg = "⚠ Erreur dans /" + label + " (module " + gen.moduleId + ") : " + t.getClass().getSimpleName()
                    + (m == null || m.isBlank() ? "" : ": " + abbreviate(m, 120));
        }
        try {
            sender.sendMessage(Component.text(msg, NamedTextColor.RED));
        } catch (Throwable ignored) {
            // sender gone
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (tab == null) {
            return super.tabComplete(sender, alias, args);
        }
        java.util.concurrent.atomic.AtomicReference<List<String>> out = new java.util.concurrent.atomic.AtomicReference<>();
        Throwable t = Guard.invoke(gen, tabWhere, ctx.handlerBudget(), () -> out.set(tab.complete(sender, args)));
        if (t != null) {
            if (!(t instanceof ModuleKilledError)) {
                ctx.report(tabWhere, t);
            }
            return List.of();
        }
        List<String> r = out.get();
        if (r == null) {
            return List.of();
        }
        List<String> copy = new ArrayList<>(Math.min(r.size(), 200));
        for (String s : r) {
            if (s != null) {
                copy.add(s);
            }
            if (copy.size() >= 200) {
                break;
            }
        }
        return copy;
    }

    static String abbreviate(String s, int max) {
        s = s.replace('\n', ' ').replace('\r', ' ');
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
