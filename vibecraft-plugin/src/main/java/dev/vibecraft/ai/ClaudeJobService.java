package dev.vibecraft.ai;

import java.util.List;
import java.util.Optional;

import dev.vibecraft.core.ModuleManager;
import org.bukkit.plugin.java.JavaPlugin;

/** STUB - to be implemented (AI agent). */
public final class ClaudeJobService implements AiJobService {
    public ClaudeJobService(JavaPlugin plugin, ModuleManager modules) { }
    @Override public boolean available() { return false; }
    @Override public String unavailableReason() { return "not implemented"; }
    @Override public AiJob start(String moduleId, String prompt, String requestedBy) { throw new IllegalStateException("not implemented"); }
    @Override public boolean stop(String moduleId) { return false; }
    @Override public List<AiJob> jobs() { return List.of(); }
    @Override public Optional<AiJob> job(String moduleId) { return Optional.empty(); }
    @Override public void resetConversation(String moduleId) { }
    @Override public void addListener(AiJobListener listener) { }
    @Override public void shutdown() { }
}
