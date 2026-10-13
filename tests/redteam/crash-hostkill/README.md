# crash-hostkill — disable the whole VibeCraft host via PluginLoader.disablePlugin (CRITICAL)

Policy blocks `PluginManager.disablePlugin` (and Bukkit/Server lifecycle, MinecraftServer lifecycle)
but not `PluginLoader.disablePlugin`. `ctx.plugin().getPluginLoader().disablePlugin(ctx.plugin())`
disables VibeCraft itself.

## Repro
Deploy to `modules/hostkill/`, reload. enable() runs it.

## Observed
Console: `Disabling VibeCraft v0.1.0`. HTTP API/dashboard unreachable, watcher gone, all modules
unloaded, `/vibe` dead. Paper server itself stays up. No recovery without restart / re-enable.

## Fix
Add `PluginLoader` (by subtype) to the plugin-lifecycle check in `Policy.forbiddenCall`, or forbid
the `disablePlugin`/`enablePlugin` method names regardless of declaring type.

## After the fix (re-verified 2026-10-14 on a private sandbox, MC 25640 / API 8840)

Rejected at load: `forbidden: PluginLoader.disablePlugin() is not allowed: it would break the server's plugins (or VibeCraft itself)`. Also forbidden: Plugin.onEnable/onDisable/onLoad/setEnabled, JavaPlugin.getCommand.
