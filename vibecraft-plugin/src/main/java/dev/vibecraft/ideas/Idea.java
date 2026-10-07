package dev.vibecraft.ideas;

/**
 * A feature idea submitted by a player / the audience.
 *
 * @param status "NEW", "ACCEPTED" (sent to the AI, see moduleId) or "DISMISSED"
 */
public record Idea(int id, String author, String text, long at, String status, String moduleId) {
}
