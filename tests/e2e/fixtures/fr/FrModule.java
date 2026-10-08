package vibe.e2e_fr;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.ModuleStore;
import dev.vibecraft.api.VibeModule;

/**
 * e2e fixture "fr": French text everywhere (source literals, config.yml, module.yml, ModuleStore, logger), like
 * every module the AI writes for a French audience. Nothing may turn into mojibake on a Windows server.
 * %V% version marker.
 */
public class FrModule implements VibeModule {
    static final String V = "%V%";

    @Override
    public void enable(ModuleContext ctx) {
        String fromConfig = ctx.config().getString("texte", "absent");
        ModuleStore st = ctx.store();
        String stored = st.getString("mot", "vide");
        st.set("mot", "été-" + V);
        ctx.command("e2efr", (s, a) -> {
            s.sendMessage("E2E-FR source=Éclair à la crème brûlée ✔ ça marche");
            s.sendMessage("E2E-FR config=" + fromConfig);
            s.sendMessage("E2E-FR store=" + stored);
        });
        ctx.logger().info("E2E-FR log=déjà vu " + V);
        /*@ENABLE@*/
    }
}
