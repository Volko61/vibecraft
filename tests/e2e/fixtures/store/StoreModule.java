package vibe.e2e_store;

import java.util.concurrent.atomic.AtomicInteger;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.ModuleStore;
import dev.vibecraft.api.VibeModule;

/** e2e fixture "store": persistent ModuleStore + in-memory shared map across reloads. %V% version marker. */
public class StoreModule implements VibeModule {
    static final String V = "%V%";

    @Override
    public void enable(ModuleContext ctx) {
        ModuleStore st = ctx.store();
        st.set("boots", st.getInt("boots", 0) + 1);
        AtomicInteger sharedHits = (AtomicInteger) ctx.shared()
                .computeIfAbsent("e2e-store.hits." + ctx.id() + "." + V.split("\\.")[0], k -> new AtomicInteger());

        ctx.command("e2estore", (s, args) -> {
            String op = args.length > 0 ? args[0] : "get";
            if (op.equals("inc")) {
                st.set("count", st.getInt("count", 0) + 1);
            } else if (op.equals("put")) {
                st.set("notes." + s.getName(), args.length > 1 ? args[1] : "x");
            }
            int hits = sharedHits.incrementAndGet();
            s.sendMessage("E2E-STORE count=" + st.getInt("count", 0)
                    + " boots=" + st.getInt("boots", 0)
                    + " note=" + st.getString("notes." + s.getName(), "-")
                    + " hits=" + hits
                    + " v=" + V);
        });
    }
}
