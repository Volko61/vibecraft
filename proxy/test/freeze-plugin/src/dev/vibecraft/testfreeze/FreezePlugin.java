package dev.vibecraft.testfreeze;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** TEST ONLY: "/vibefreeze <seconds>" blocks the server main thread (simulates a frozen server). */
public final class FreezePlugin extends JavaPlugin {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        long secs = args.length > 0 ? Long.parseLong(args[0]) : 40;
        getLogger().warning("Freezing the main thread for " + secs + " s (test)");
        long end = System.currentTimeMillis() + secs * 1000;
        while (System.currentTimeMillis() < end) {
            try { Thread.sleep(100); } catch (InterruptedException e) { break; }
        }
        getLogger().warning("Unfrozen");
        return true;
    }
}
