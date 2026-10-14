package dev.vibecraft.pack.mesh;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import dev.vibecraft.pack.PackBuilder;

/**
 * Offline check (no server): builds the resource pack of module folders exactly like the server does and
 * writes the zip, the previews and a report.
 * {@code java -cp VibeCraft.jar;<gson.jar> dev.vibecraft.pack.mesh.MeshTool <outDir> <moduleDir>...}
 */
public final class MeshTool {

    private MeshTool() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: MeshTool <outDir> <moduleDir>...");
            System.exit(2);
        }
        File out = new File(args[0]);
        Files.createDirectories(out.toPath());
        List<PackBuilder.Input> in = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            File d = new File(args[i]);
            in.add(new PackBuilder.Input(d.getName(), d));
        }
        long t0 = System.nanoTime();
        PackBuilder.Result r = PackBuilder.build(in, 97, 97, "MeshTool");
        long ms = (System.nanoTime() - t0) / 1_000_000;
        if (!r.empty()) {
            Files.write(new File(out, "pack.zip").toPath(), r.zip());
        }
        for (Map.Entry<String, byte[]> p : r.previews().entrySet()) {
            Files.write(new File(out, p.getKey().replace(':', '_').replace('/', '_') + ".png").toPath(), p.getValue());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("build ").append(ms).append(" ms, ").append(r.files()).append(" files, sha1 ").append(r.sha1()).append('\n');
        for (PackBuilder.Issue i : r.issues()) {
            sb.append(i.module()).append(": ").append(i).append('\n');
        }
        for (MeshCompiler.ObjectInfo o : r.models()) {
            sb.append(o.id()).append(": ").append(o.elements()).append(" elements, ").append(o.quads()).append(" quads, mode ")
                    .append(o.mode()).append(", atlas ").append(o.atlasWidth()).append('x').append(o.atlasHeight())
                    .append(", size ").append(String.format(java.util.Locale.ROOT, "%.2f x %.2f x %.2f", o.size()[0], o.size()[1], o.size()[2]))
                    .append('\n');
            for (MeshCompiler.PartInfo p : o.parts()) {
                sb.append("   part ").append(p.name()).append(": ").append(p.elements()).append(" elements, models ")
                        .append(p.models()).append(String.format(java.util.Locale.ROOT, ", unit %.4f", p.unit())).append('\n');
            }
        }
        Files.writeString(new File(out, "report.txt").toPath(), sb.toString());
        System.out.print(sb);
    }
}
