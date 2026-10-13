package vibe.leaks_shared;

import java.util.ArrayList;
import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/**
 * REDTEAM-LEAKS: ctx.shared() holding a JDK container (ArrayList) of module objects. The engine only drops
 * shared values whose own class comes from the unloaded generation, so this one survives the reload.
 */
public class LeaksSharedModule implements VibeModule {
    record Marker(int gen) { }

    @Override
    public void enable(ModuleContext ctx) {
        Object prev = ctx.shared().get("lk.shared.list");
        String r = "prev=" + (prev == null ? "null" : prev.getClass().getName());
        if (prev instanceof List<?> l && !l.isEmpty()) {
            Object first = l.get(0);
            r += " element=" + first.getClass().getName() + "@" + first.getClass().getClassLoader().getName();
            try {
                Marker m = (Marker) first;
                r += " cast ok gen=" + m.gen();
            } catch (ClassCastException e) {
                r += " ClassCastException";
            }
        }
        List<Object> list = new ArrayList<>();
        list.add(new Marker(ctx.generation()));
        ctx.shared().put("lk.shared.list", list);
        ctx.shared().put("lk.shared.record", new Marker(ctx.generation()));
        ctx.logger().info("v" + ctx.generation() + " " + r + " ; record survived=" + ctx.shared().containsKey("lk.shared.record"));
    }
}
