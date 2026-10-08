package vibe.e2e_par;

import java.util.ArrayList;
import java.util.List;

import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;

/** e2e fixture "par": used for several modules reloaded in parallel. %V% version marker, %P% command name. */
public class ParModule implements VibeModule {
    static final String V = "%V%";
    static final String P = "%P%";

    record Item(String name, int weight) {
    }

    @Override
    public void enable(ModuleContext ctx) {
        // a bit of modern Java (record, streams, switch expression) so each compile does some real work
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            items.add(new Item("item" + i, i * 3 % 7));
        }
        int total = items.stream().mapToInt(Item::weight).sum();
        String kind = switch (total % 3) {
            case 0 -> "zero";
            case 1 -> "one";
            default -> "two";
        };
        ctx.command(P, (s, a) -> s.sendMessage("E2E-PAR " + P + " " + V + " " + kind));
    }
}
