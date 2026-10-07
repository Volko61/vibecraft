package dev.vibecraft.ideas;

import java.util.List;
import java.util.Optional;

import org.bukkit.plugin.java.JavaPlugin;

/** STUB - to be implemented (interface agent). */
public final class IdeaQueueImpl implements IdeaQueue {
    public IdeaQueueImpl(JavaPlugin plugin) { }
    @Override public Idea add(String author, String text) { throw new UnsupportedOperationException("TODO"); }
    @Override public List<Idea> list() { return List.of(); }
    @Override public Optional<Idea> get(int id) { return Optional.empty(); }
    @Override public Optional<Idea> accept(int id, String moduleId) { return Optional.empty(); }
    @Override public Optional<Idea> dismiss(int id) { return Optional.empty(); }
}
