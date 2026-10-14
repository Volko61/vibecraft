package dev.vibecraft.pack.mesh;

/**
 * Intermediate element: an oriented box (centre {@code c}, rotation {@code r} whose columns are the local
 * axes, half extents {@code h} along them; a zero half extent = flat plate) with a material per face.
 * Faces are indexed like Minecraft directions: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east
 * (local -y, +y, -z, +z, -x, +x). A null face is not emitted.
 */
final class Elem {

    static final String[] DIR = {"down", "up", "north", "south", "west", "east"};
    static final int DOWN = 0;
    static final int UP = 1;
    static final int NORTH = 2;
    static final int SOUTH = 3;
    static final int WEST = 4;
    static final int EAST = 5;

    V3 c;
    M3 r;
    V3 h;
    final Material[] faces = new Material[6];
    /** Optional per-texel colour source of a face (voxel mode), baked into an atlas patch. */
    final MeshCompiler.TexelSource[] texels = new MeshCompiler.TexelSource[6];
    /** Solid volume (can hide faces of other elements / be filled by the voxeliser), false for plates. */
    boolean solid;

    Elem(V3 c, M3 r, V3 h) {
        this.c = c;
        this.r = r;
        this.h = h;
    }

    static int axisOf(int dir) {
        return switch (dir) {
            case DOWN, UP -> 1;
            case NORTH, SOUTH -> 2;
            default -> 0;
        };
    }

    static int signOf(int dir) {
        return dir == UP || dir == SOUTH || dir == EAST ? 1 : -1;
    }

    static int dirOf(int axis, int sign) {
        return switch (axis) {
            case 0 -> sign > 0 ? EAST : WEST;
            case 1 -> sign > 0 ? UP : DOWN;
            default -> sign > 0 ? SOUTH : NORTH;
        };
    }

    static V3 localNormal(int dir) {
        return V3.axis(axisOf(dir)).mul(signOf(dir));
    }

    /** World-space (object) normal of a face. */
    V3 normal(int dir) {
        return r.apply(localNormal(dir));
    }

    boolean hasFaces() {
        for (Material m : faces) {
            if (m != null) {
                return true;
            }
        }
        return false;
    }

    int faceCount() {
        int n = 0;
        for (Material m : faces) {
            if (m != null) {
                n++;
            }
        }
        return n;
    }

    /** Copy transformed by p -> t + m*p (m a rotation, possibly with a mirror handled by the caller). */
    Elem transformed(M3 m, V3 t) {
        Elem e = new Elem(t.add(m.apply(c)), m.mul(r), h);
        System.arraycopy(faces, 0, e.faces, 0, 6);
        System.arraycopy(texels, 0, e.texels, 0, 6);
        e.solid = solid;
        return e;
    }

    /**
     * Mirror across the plane through the origin normal to world axis {@code axis}. The rotation becomes
     * M*R*M (proper), which flips the local axis with the same index: its two faces swap.
     */
    Elem mirrored(int axis) {
        M3 mm = M3.diag(axis == 0 ? -1 : 1, axis == 1 ? -1 : 1, axis == 2 ? -1 : 1);
        Elem e = new Elem(mm.apply(c), mm.mul(r).mul(mm), h);
        System.arraycopy(faces, 0, e.faces, 0, 6);
        System.arraycopy(texels, 0, e.texels, 0, 6);
        int a = dirOf(axis, -1);
        int b = dirOf(axis, 1);
        e.faces[a] = faces[b];
        e.faces[b] = faces[a];
        e.texels[a] = texels[b];
        e.texels[b] = texels[a];
        e.solid = solid;
        return e;
    }

    /** Point in the element given local coordinates (relative to the centre). */
    V3 toWorld(V3 local) {
        return c.add(r.apply(local));
    }

    /** The 8 corners in the parent frame. */
    V3[] corners() {
        V3[] out = new V3[8];
        int i = 0;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    out[i++] = toWorld(new V3(sx * h.x(), sy * h.y(), sz * h.z()));
                }
            }
        }
        return out;
    }

    /** True if p is inside this (solid) box shrunk by {@code margin}. */
    boolean containsStrict(V3 p, double margin) {
        V3 l = r.transpose().apply(p.sub(c));
        return Math.abs(l.x()) < h.x() - margin && Math.abs(l.y()) < h.y() - margin && Math.abs(l.z()) < h.z() - margin;
    }
}
