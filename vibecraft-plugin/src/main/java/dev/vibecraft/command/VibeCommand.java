package dev.vibecraft.command;

import java.util.List;

import dev.vibecraft.ai.AiJobService;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.ideas.IdeaQueue;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.plugin.java.JavaPlugin;

/** STUB - to be implemented (interface agent). /vibe admin command. */
public final class VibeCommand implements TabExecutor {
    public VibeCommand(JavaPlugin plugin, ModuleManager modules, AiJobService ai, IdeaQueue ideas) { }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) { return false; }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) { return List.of(); }
}
