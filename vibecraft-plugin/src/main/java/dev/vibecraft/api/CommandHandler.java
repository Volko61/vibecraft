package dev.vibecraft.api;

import org.bukkit.command.CommandSender;

/** Executes a module command. {@code args} excludes the label. Exceptions are caught and reported. */
@FunctionalInterface
public interface CommandHandler {
    void handle(CommandSender sender, String[] args) throws Exception;
}
