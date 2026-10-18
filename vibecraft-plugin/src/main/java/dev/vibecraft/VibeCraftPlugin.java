package dev.vibecraft;

import java.io.IOException;
import java.util.Objects;

import dev.vibecraft.ai.AiJobService;
import dev.vibecraft.ai.ClaudeJobService;
import dev.vibecraft.ai.Conversations;
import dev.vibecraft.ai.JobOptions;
import dev.vibecraft.web.ConversationRoutes;
import dev.vibecraft.command.IdeaCommand;
import dev.vibecraft.command.VibeCommand;
import dev.vibecraft.core.ModuleEvent;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.core.ModuleManagerImpl;
import dev.vibecraft.ext.Extensions;
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
    private Extensions extensions;
    private ConversationRoutes conversationRoutes;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        modules = new ModuleManagerImpl(this);
        extensions = new Extensions(this, modules); // secrets (needed by the AI job service)
        ideas = new IdeaQueueImpl(this);
        ai = new ClaudeJobService(this, modules);
        new Notifier(this, modules, ai).register();
        // A module disabled by hand (API, /vibe, menu, or a .vibe/disabled marker created by hand) is an emergency
        // stop: the AI working on it is stopped too (it would reload or re-enable the module otherwise).
        AiJobService aiJobs = ai;
        modules.addListener(e -> {
            if (e != null && e.type() == ModuleEvent.Type.DISABLED && e.moduleId() != null) {
                aiJobs.stop(e.moduleId());
            }
        });

        Conversations conversations = null;
        if (ai instanceof ClaudeJobService cjs) {
            conversations = new Conversations(cjs, modules);
            extensions.setJobProvider(id -> {
                JobOptions o = cjs.engine().activeOptions(id);
                return o == null ? null : o.imageProvider();
            });
            conversationRoutes = new ConversationRoutes(conversations);
            conversationRoutes.register();
        }

        VibeCommand vibe = new VibeCommand(this, modules, ai, ideas, conversations);
        PluginCommand vibeCmd = Objects.requireNonNull(getCommand("vibe"));
        vibeCmd.setExecutor(vibe);
        vibeCmd.setTabCompleter(vibe);

        IdeaCommand idea = new IdeaCommand(this, ideas);
        PluginCommand ideaCmd = Objects.requireNonNull(getCommand("idea"));
        ideaCmd.setExecutor(idea);
        ideaCmd.setTabCompleter(idea);

        modules.start();
        extensions.start(); // hot resource pack, OpenRouter, Meshy, texture / 3D generation, /api/pack...
        if (extensions.pack() != null && getConfig().getBoolean("ui.compass-opens-claude", true)) {
            extensions.pack().setSneakAction(vibe::openClaude); // sneak + right click with a compass (ops)
        }

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
        if (conversationRoutes != null) {
            conversationRoutes.unregister();
        }
        if (extensions != null) {
            extensions.stop();
        }
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
