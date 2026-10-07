package dev.vibecraft;

import java.io.IOException;
import java.util.Objects;

import dev.vibecraft.ai.AiJobService;
import dev.vibecraft.ai.ClaudeJobService;
import dev.vibecraft.command.IdeaCommand;
import dev.vibecraft.command.VibeCommand;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.core.ModuleManagerImpl;
import dev.vibecraft.ideas.IdeaQueue;
import dev.vibecraft.ideas.IdeaQueueImpl;
import dev.vibecraft.notify.Notifier;
import dev.vibecraft.web.ApiServer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/** Host plugin: wires the module manager, the AI job service, the idea queue, commands and the HTTP API. */
public final class VibeCraftPlugin extends JavaPlugin {

    private ModuleManager modules;
    private AiJobService ai;
    private IdeaQueue ideas;
    private ApiServer api;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        modules = new ModuleManagerImpl(this);
        ideas = new IdeaQueueImpl(this);
        ai = new ClaudeJobService(this, modules);
        new Notifier(this, modules, ai).register();

        VibeCommand vibe = new VibeCommand(this, modules, ai, ideas);
        PluginCommand vibeCmd = Objects.requireNonNull(getCommand("vibe"));
        vibeCmd.setExecutor(vibe);
        vibeCmd.setTabCompleter(vibe);

        IdeaCommand idea = new IdeaCommand(this, ideas);
        PluginCommand ideaCmd = Objects.requireNonNull(getCommand("idea"));
        ideaCmd.setExecutor(idea);
        ideaCmd.setTabCompleter(idea);

        modules.start();

        if (getConfig().getBoolean("api.enabled", true)) {
            api = new ApiServer(this, modules, ai, ideas);
            try {
                api.start();
            } catch (IOException e) {
                getLogger().severe("HTTP API failed to start: " + e);
                api = null;
            }
        }
    }

    @Override
    public void onDisable() {
        if (api != null) {
            api.stop();
        }
        if (ai != null) {
            ai.shutdown();
        }
        if (modules != null) {
            modules.shutdown();
        }
    }

    public ModuleManager modules() {
        return modules;
    }

    public AiJobService ai() {
        return ai;
    }

    public IdeaQueue ideas() {
        return ideas;
    }
}
