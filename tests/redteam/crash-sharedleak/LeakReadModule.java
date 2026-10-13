package vibe.leakread;
import dev.vibecraft.api.ModuleContext;
import dev.vibecraft.api.VibeModule;
// reader half: /leakread reports whether shared() still holds leak.bytes after leakstore is gone.
public class LeakReadModule implements VibeModule {
    @Override public void enable(ModuleContext ctx) {
        ctx.command("leakread", (s,a) -> {
            Object v = ctx.shared().get("leak.bytes");
            s.sendRichMessage("<yellow>leak.bytes present=" + (v!=null) + (v instanceof byte[] b ? " size="+b.length : ""));
        });
    }
}
