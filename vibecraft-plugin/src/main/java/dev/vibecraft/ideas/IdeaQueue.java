package dev.vibecraft.ideas;

import java.util.List;
import java.util.Optional;

/** Audience idea queue (persisted to plugins/VibeCraft/ideas.yml). Thread-safe. */
public interface IdeaQueue {
    Idea add(String author, String text);

    /** All ideas, newest first. */
    List<Idea> list();

    Optional<Idea> get(int id);

    /** Mark as ACCEPTED for the given module. Returns the updated idea. */
    Optional<Idea> accept(int id, String moduleId);

    Optional<Idea> dismiss(int id);
}
