package dev.vibecraft.web;

import java.io.IOException;

import dev.vibecraft.ai.AiJobService;
import dev.vibecraft.core.ModuleManager;
import dev.vibecraft.ideas.IdeaQueue;
import org.bukkit.plugin.java.JavaPlugin;

/** STUB - to be implemented (interface agent). Local HTTP API + dashboard. */
public final class ApiServer {
    public ApiServer(JavaPlugin plugin, ModuleManager modules, AiJobService ai, IdeaQueue ideas) { }
    public void start() throws IOException { }
    public void stop() { }
}
