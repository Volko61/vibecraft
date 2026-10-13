package vibe.leaks_cfgloop;

import java.io.File;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * REDTEAM-LEAKS: the classic "saveDefaultConfig" pattern: write config.yml with defaults in enable().
 * config.yml is part of the module fingerprint, so this re-triggers a reload by itself.
 */
public class LeaksCfgloopModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) throws Exception {
        YamlConfiguration c = ctx.config();
        c.addDefault("power", 3);
        c.addDefault("message", "Salut");
        c.options().copyDefaults(true);
        c.save(new File(ctx.sourceFolder(), "config.yml"));
        ctx.logger().info("cfgloop v" + ctx.generation() + " power=" + c.getInt("power"));
    }
}
