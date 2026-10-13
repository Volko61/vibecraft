package dev.vibecraft.core.ctx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.vibecraft.core.guard.Gen;
import dev.vibecraft.core.guard.Guard;
import dev.vibecraft.core.guard.ModuleKilledError;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

/**
 * Wraps a module's own {@link Command} subclass registered through the raw CommandMap API, so
 * that its execute / tabComplete run under the guard (handler budget, errors recorded) instead of
 * reaching Bukkit unguarded.
 */
final class GuardedRawCommand extends Command {

    private final Command inner;
    private final ModuleContextImpl ctx;
    private final Gen gen;
    private final String where;

    GuardedRawCommand(Command inner, ModuleContextImpl ctx, Gen gen) {
        super(inner.getName(), inner.getDescription(), inner.getUsage(), new ArrayList<>(inner.getAliases()));
        this.inner = inner;
        this.ctx = ctx;
        this.gen = gen;
        this.where = "command /" + inner.getName();
        setPermission(inner.getPermission());
    }

    Gen gen() {
        return gen;
    }

    Command inner() {
        return inner;
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        AtomicBoolean result = new AtomicBoolean(true);
        Throwable t = Guard.invoke(gen, where, ctx.handlerBudget(), () -> result.set(inner.execute(sender, label, args)));
        if (t != null) {
            if (!(t instanceof ModuleKilledError)) {
                ctx.report(where, t);
            }
            ModuleCommand.tellSender(sender, label, gen, t);
            return true;
        }
        return result.get();
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return complete(() -> inner.tabComplete(sender, alias, args));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args, Location location) {
        return complete(() -> inner.tabComplete(sender, alias, args, location));
    }

    private interface Completion {
        List<String> get() throws Throwable;
    }

    private List<String> complete(Completion c) {
        AtomicReference<List<String>> out = new AtomicReference<>();
        Throwable t = Guard.invoke(gen, "tab-complete /" + getName(), ctx.handlerBudget(), () -> out.set(c.get()));
        if (t != null) {
            if (!(t instanceof ModuleKilledError)) {
                ctx.report("tab-complete /" + getName(), t);
            }
            return List.of();
        }
        List<String> r = out.get();
        return r == null ? List.of() : new ArrayList<>(r);
    }

    @Override
    public boolean testPermissionSilent(CommandSender target) {
        try {
            return inner.testPermissionSilent(target);
        } catch (Throwable t) {
            return false;
        }
    }
}
