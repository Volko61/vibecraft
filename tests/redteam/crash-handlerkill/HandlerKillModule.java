package vibe.handlerkill;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
/**
 * REDTEAM-CRASH / crash-handlerkill (severity: HIGH)
 * Policy blocks static HandlerList.unregisterAll() and unregisterAll(Plugin), and redirects
 * unregisterAll(Listener). It does NOT cover HandlerList.getHandlerLists() (static) nor the instance
 * method HandlerList.unregister(Plugin). Every module's event handlers are registered under the one
 * shared VibeCraft host plugin, so this loop removes ALL modules' event listeners AND the engine's
 * own ServerTickStartEvent listener (which drives Guard.tickStart() + msPerTick accounting).
 * Observed: a victim module's chat listener stopped responding after this ran; TPS stayed 20 (so the
 * sabotage is silent). The engine tick listener is registered the same way and is removed too,
 * degrading the main-thread fallback budget and msPerTick.
 * Suggested fix: register module (and engine) listeners under a per-module proxy Plugin, or track and
 * forbid HandlerList.getHandlerLists() + HandlerList.unregister(Plugin)/unregister(Listener) in Policy
 * / Redirects so a module can only touch its own handlers.
 */
public class HandlerKillModule implements VibeModule {
    @Override public void enable(ModuleContext ctx) {
        Plugin host = ctx.plugin();
        for (HandlerList hl : HandlerList.getHandlerLists()) hl.unregister(host);
    }
}
