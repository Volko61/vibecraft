package vibe.shop_alias;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/** FIXER regression: own name is free, but the alias /boutique belongs to module "shop". */
public class ShopAliasModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("shopalias", "test", (s, a) -> s.sendMessage("shop-alias repond"), null, "boutique");
    }
}
