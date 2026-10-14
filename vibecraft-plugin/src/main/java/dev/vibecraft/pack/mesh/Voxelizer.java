package dev.vibecraft.pack.mesh;

import java.util.ArrayList;
import java.util.List;

import dev.vibecraft.pack.TextureSources.SourceException;

/**
 * Voxel mode: rasterises elements (solid boxes and surface plates) into a grid, fills closed volumes
 * (flood fill of the outside), then greedy-meshes the visible voxel faces per direction and slice into
 * axis-aligned flat elements. Rectangles are merged by geometry only (not by colour): their per-voxel
 * colours are baked into an atlas patch (1 texel per voxel face) unless uniform.
 */
final class Voxelizer {

    static final int MAX_VOXELS = 6_000_000;

    private Voxelizer() {
    }

    /** Colour lookup for a voxel face patch. */
    record VoxelSource(int[] color, int nx, int ny, int nz, V3 origin, double vs) implements MeshCompiler.TexelSource {
        @Override
        public int argb(V3 p, V3 n) {
            V3 q = p.sub(n.mul(vs * 0.5)).sub(origin);
            int x = clamp((int) Math.floor(q.x() / vs), nx);
            int y = clamp((int) Math.floor(q.y() / vs), ny);
            int z = clamp((int) Math.floor(q.z() / vs), nz);
            int c = color[(x * ny + y) * nz + z];
            return c == 0 ? 0xff9a9a9a : c;
        }

        private static int clamp(int v, int n) {
            return Math.max(0, Math.min(n - 1, v));
        }
    }

    static List<Elem> voxelize(List<Elem> elems, double vs) throws SourceException {
        if (elems.isEmpty()) {
            return List.of();
        }
        V3 min = new V3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        V3 max = min.mul(-1);
        for (Elem e : elems) {
            for (V3 c : e.corners()) {
                min = V3.min(min, c);
                max = V3.max(max, c);
            }
        }
        V3 origin = min.sub(new V3(vs, vs, vs));
        int nx = (int) Math.ceil((max.x() - min.x()) / vs) + 3;
        int ny = (int) Math.ceil((max.y() - min.y()) / vs) + 3;
        int nz = (int) Math.ceil((max.z() - min.z()) / vs) + 3;
        if ((long) nx * ny * nz > MAX_VOXELS) {
            throw new SourceException("voxel grid too large (" + nx + "x" + ny + "x" + nz + "): lower \"resolution\"");
        }
        int[] color = new int[nx * ny * nz];
        byte[] glow = new byte[nx * ny * nz];
        boolean[] filled = new boolean[nx * ny * nz];
        for (Elem e : elems) {
            if (e.solid) {
                rasterSolid(e, color, glow, filled, nx, ny, nz, origin, vs);
            } else {
                rasterSurface(e, color, glow, filled, nx, ny, nz, origin, vs);
            }
        }
        // outside = flood fill from the padded border through empty voxels
        boolean[] outside = new boolean[nx * ny * nz];
        int[] queue = new int[nx * ny * nz];
        int qh = 0;
        int qt = 0;
        for (int x = 0; x < nx; x++) {
            for (int y = 0; y < ny; y++) {
                for (int z = 0; z < nz; z++) {
                    if (x == 0 || y == 0 || z == 0 || x == nx - 1 || y == ny - 1 || z == nz - 1) {
                        int i = (x * ny + y) * nz + z;
                        if (!filled[i] && !outside[i]) {
                            outside[i] = true;
                            queue[qt++] = i;
                        }
                    }
                }
            }
        }
        while (qh < qt) {
            int i = queue[qh++];
            int z = i % nz;
            int y = (i / nz) % ny;
            int x = i / (nz * ny);
            for (int d = 0; d < 6; d++) {
                int ax = d / 2;
                int sg = d % 2 == 0 ? -1 : 1;
                int xx = x + (ax == 0 ? sg : 0);
                int yy = y + (ax == 1 ? sg : 0);
                int zz = z + (ax == 2 ? sg : 0);
                if (xx < 0 || yy < 0 || zz < 0 || xx >= nx || yy >= ny || zz >= nz) {
                    continue;
                }
                int j = (xx * ny + yy) * nz + zz;
                if (!filled[j] && !outside[j]) {
                    outside[j] = true;
                    queue[qt++] = j;
                }
            }
        }
        VoxelSource src = new VoxelSource(color, nx, ny, nz, origin, vs);
        List<Elem> out = new ArrayList<>();
        int[] dims = {nx, ny, nz};
        for (int ax = 0; ax < 3; ax++) {
            int a1 = (ax + 1) % 3;
            int a2 = (ax + 2) % 3;
            for (int sg = -1; sg <= 1; sg += 2) {
                int dir = Elem.dirOf(ax, sg);
                for (int s = 0; s < dims[ax]; s++) {
                    int w = dims[a1];
                    int h = dims[a2];
                    int[] mask = new int[w * h]; // glow+1, 0 = no face
                    boolean any = false;
                    for (int i = 0; i < w; i++) {
                        for (int j = 0; j < h; j++) {
                            int[] p = new int[3];
                            p[ax] = s;
                            p[a1] = i;
                            p[a2] = j;
                            int idx = (p[0] * ny + p[1]) * nz + p[2];
                            if (outside[idx]) {
                                continue; // empty
                            }
                            p[ax] = s + sg;
                            if (p[ax] < 0 || p[ax] >= dims[ax]) {
                                continue;
                            }
                            int nidx = (p[0] * ny + p[1]) * nz + p[2];
                            if (outside[nidx]) {
                                mask[i * h + j] = glow[idx] + 1;
                                any = true;
                            }
                        }
                    }
                    if (!any) {
                        continue;
                    }
                    // greedy rectangles
                    for (int i = 0; i < w; i++) {
                        for (int j = 0; j < h; j++) {
                            int m = mask[i * h + j];
                            if (m == 0) {
                                continue;
                            }
                            int rh = 1;
                            while (j + rh < h && mask[i * h + j + rh] == m) {
                                rh++;
                            }
                            int rw = 1;
                            outer:
                            while (i + rw < w) {
                                for (int k = 0; k < rh; k++) {
                                    if (mask[(i + rw) * h + j + k] != m) {
                                        break outer;
                                    }
                                }
                                rw++;
                            }
                            for (int a = 0; a < rw; a++) {
                                for (int b = 0; b < rh; b++) {
                                    mask[(i + a) * h + j + b] = 0;
                                }
                            }
                            double plane = origin.get(ax) + (s + (sg > 0 ? 1 : 0)) * vs;
                            V3 c = V3.ZERO.with(ax, plane)
                                    .with(a1, origin.get(a1) + (i + rw / 2.0) * vs)
                                    .with(a2, origin.get(a2) + (j + rh / 2.0) * vs);
                            V3 hh = V3.ZERO.with(a1, rw * vs / 2).with(a2, rh * vs / 2);
                            Elem e = new Elem(c, M3.I, hh);
                            Material fm = new Material("voxel", 0xffffffff, null, null, "box", 1, 1 / vs, m - 1, false, true);
                            e.faces[dir] = fm;
                            e.texels[dir] = src;
                            out.add(e);
                        }
                    }
                }
            }
        }
        return out;
    }

    private static void rasterSolid(Elem e, int[] color, byte[] glow, boolean[] filled, int nx, int ny, int nz, V3 origin, double vs) {
        V3 mn = new V3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE);
        V3 mx = mn.mul(-1);
        for (V3 c : e.corners()) {
            mn = V3.min(mn, c);
            mx = V3.max(mx, c);
        }
        int x0 = Math.max(0, (int) Math.floor((mn.x() - origin.x()) / vs));
        int y0 = Math.max(0, (int) Math.floor((mn.y() - origin.y()) / vs));
        int z0 = Math.max(0, (int) Math.floor((mn.z() - origin.z()) / vs));
        int x1 = Math.min(nx - 1, (int) Math.floor((mx.x() - origin.x()) / vs));
        int y1 = Math.min(ny - 1, (int) Math.floor((mx.y() - origin.y()) / vs));
        int z1 = Math.min(nz - 1, (int) Math.floor((mx.z() - origin.z()) / vs));
        M3 rt = e.r.transpose();
        double tol = vs * 0.5;
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    V3 p = origin.add(new V3((x + 0.5) * vs, (y + 0.5) * vs, (z + 0.5) * vs));
                    V3 l = rt.apply(p.sub(e.c));
                    // thin boxes (thinner than a voxel) still get one voxel layer
                    if (Math.abs(l.x()) > Math.max(e.h.x(), tol) || Math.abs(l.y()) > Math.max(e.h.y(), tol)
                            || Math.abs(l.z()) > Math.max(e.h.z(), tol)) {
                        continue;
                    }
                    int i = (x * ny + y) * nz + z;
                    filled[i] = true;
                    int best = -1;
                    double bd = Double.MAX_VALUE;
                    for (int d = 0; d < 6; d++) {
                        if (e.faces[d] == null) {
                            continue;
                        }
                        int ax = Elem.axisOf(d);
                        double dist = e.h.get(ax) - Elem.signOf(d) * l.get(ax);
                        if (dist < bd) {
                            bd = dist;
                            best = d;
                        }
                    }
                    if (best >= 0) {
                        Material m = e.faces[best];
                        color[i] = Baker.sample(m, p, e.normal(best), 0.5, 0.5);
                        glow[i] = (byte) m.glow;
                    } else if (color[i] == 0) {
                        color[i] = 0xff9a9a9a;
                    }
                }
            }
        }
    }

    private static void rasterSurface(Elem e, int[] color, byte[] glow, boolean[] filled, int nx, int ny, int nz, V3 origin, double vs) {
        for (int d = 0; d < 6; d++) {
            Material m = e.faces[d];
            if (m == null) {
                continue;
            }
            int ax = Elem.axisOf(d);
            int a1 = (ax + 1) % 3;
            int a2 = (ax + 2) % 3;
            double w = 2 * e.h.get(a1);
            double h = 2 * e.h.get(a2);
            int nu = Math.max(1, (int) Math.ceil(w / (vs * 0.5)));
            int nv = Math.max(1, (int) Math.ceil(h / (vs * 0.5)));
            V3 n = e.normal(d);
            for (int i = 0; i <= nu; i++) {
                for (int j = 0; j <= nv; j++) {
                    V3 l = V3.ZERO.with(ax, Elem.signOf(d) * e.h.get(ax))
                            .with(a1, -e.h.get(a1) + w * i / nu)
                            .with(a2, -e.h.get(a2) + h * j / nv);
                    // nudge inside so that a closed shell's voxels sit on the inner side of the surface
                    V3 p = e.toWorld(l).sub(n.mul(vs * 0.25));
                    V3 q = p.sub(origin);
                    int x = (int) Math.floor(q.x() / vs);
                    int y = (int) Math.floor(q.y() / vs);
                    int z = (int) Math.floor(q.z() / vs);
                    if (x < 0 || y < 0 || z < 0 || x >= nx || y >= ny || z >= nz) {
                        continue;
                    }
                    int idx = (x * ny + y) * nz + z;
                    filled[idx] = true;
                    color[idx] = Baker.sample(m, p, n, (double) i / nu, (double) j / nv);
                    glow[idx] = (byte) m.glow;
                }
            }
        }
    }
}
