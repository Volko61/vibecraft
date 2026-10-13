package vibe.shop_bis;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/** Second AI session that also picked /shop (name collision with module "shop"). */
public class ShopBisModule implements VibeModule {
    @Override
    public void enable(ModuleContext ctx) {
        ctx.command("shop", (sender, args) -> sender.sendRichMessage("<yellow>shop-bis repond"));
        ctx.command("boutique", (sender, args) -> sender.sendRichMessage("<yellow>shop-bis boutique"));
    }
}
