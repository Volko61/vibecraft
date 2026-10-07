package dev.vibecraft.api;

import java.util.List;

import org.bukkit.command.CommandSender;

/** Tab-completion for a module command. Return an empty list for no suggestions. */
@FunctionalInterface
public interface TabHandler {
    List<String> complete(CommandSender sender, String[] args) throws Exception;
}
