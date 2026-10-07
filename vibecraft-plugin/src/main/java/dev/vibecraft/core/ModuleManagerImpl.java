package dev.vibecraft.core;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.bukkit.plugin.java.JavaPlugin;

/** STUB - to be implemented (engine agent). */
public final class ModuleManagerImpl implements ModuleManager {
    public ModuleManagerImpl(JavaPlugin plugin) { }
    @Override public void start() { throw new UnsupportedOperationException("TODO"); }
    @Override public void shutdown() { }
    @Override public List<ModuleInfo> list() { return List.of(); }
    @Override public Optional<ModuleInfo> info(String id) { return Optional.empty(); }
    @Override public CompletableFuture<ReloadResult> reload(String id) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<ReloadResult> enable(String id) { throw new UnsupportedOperationException("TODO"); }
    @Override public CompletableFuture<Boolean> disable(String id) { throw new UnsupportedOperationException("TODO"); }
    @Override public boolean create(String id, String description) throws IOException { throw new UnsupportedOperationException("TODO"); }
    @Override public boolean isValidId(String id) { return id != null && id.matches("[a-z0-9][a-z0-9_-]{0,39}"); }
    @Override public List<String> logs(String id, int limit) { return List.of(); }
    @Override public File modulesDir() { throw new UnsupportedOperationException("TODO"); }
    @Override public File dataDir() { throw new UnsupportedOperationException("TODO"); }
    @Override public void addListener(ModuleEventListener listener) { }
}
