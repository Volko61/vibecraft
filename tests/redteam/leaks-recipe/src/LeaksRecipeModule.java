package vibe.leaks_recipe;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;

/**
 * REDTEAM-LEAKS: the "tutorial" way of adding a recipe (Bukkit.addRecipe) instead of ctx.recipe(...).
 * Also unlocks it in the player's recipe book.
 */
public class LeaksRecipeModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ShapedRecipe r = new ShapedRecipe(ctx.key("raw_emerald"), ItemStack.of(Material.EMERALD));
        r.shape("GG", "GG");
        r.setIngredient('G', Material.GRAVEL);
        Bukkit.addRecipe(r);
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.discoverRecipe(r.getKey());
        }
        ctx.logger().info("recipe added by v" + ctx.generation());
    }
}
