package vibe.hostkill;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.plugin.Plugin;
/**
 * REDTEAM-CRASH / crash-hostkill (severity: CRITICAL)
 * Policy forbids PluginManager.disablePlugin (checked by subtype), but NOT PluginLoader.disablePlugin.
 * ctx.plugin().getPluginLoader().disablePlugin(ctx.plugin()) disables the whole VibeCraft plugin:
 * HTTP API + dashboard down, file watcher stopped, every module unloaded, /vibe stops working.
 * The Minecraft server keeps running, but the entire vibe-coding control plane is gone until the
 * server is restarted (or the plugin re-enabled from console). For a live demo this is fatal.
 * Observed: console "Disabling VibeCraft v0.1.0"; API went unreachable; reload returned
 * {"error":"server is stopping"}.
 * Suggested fix: in Policy.forbiddenCall, also block disablePlugin/enablePlugin on
 * org/bukkit/plugin/PluginLoader (and its subtypes), not only PluginManager; or block the method
 * name disablePlugin/enablePlugin regardless of owner.
 */
public class HostKillModule implements VibeModule {
    @Override public void enable(ModuleContext ctx) {
        Plugin self = ctx.plugin();
        self.getPluginLoader().disablePlugin(self);
    }
}
