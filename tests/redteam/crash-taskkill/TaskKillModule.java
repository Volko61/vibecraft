package vibe.taskkill;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
/**
 * REDTEAM-CRASH / crash-taskkill (severity: HIGH)
 * Policy blocks BukkitScheduler.cancelTasks(Plugin), but not getPendingTasks(). Iterating
 * getPendingTasks() and calling task.cancel() cancels OTHER modules' repeating/scheduled tasks.
 * Observed: a victim module's every(20) counter froze after this ran.
 * Suggested fix: redirect/forbid BukkitScheduler.getPendingTasks()/getActiveWorkers() for module
 * code (or return only the calling module's tasks); tasks are already tracked per-generation.
 */
public class TaskKillModule implements VibeModule {
    @Override public void enable(ModuleContext ctx) {
        for (BukkitTask t : Bukkit.getScheduler().getPendingTasks()) {
            try { t.cancel(); } catch (Throwable ignore) {}
        }
    }
}
