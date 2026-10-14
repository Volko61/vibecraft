package dev.vibecraft.pack.mesh;

/** Immutable 3D vector (doubles). */
public record V3(double x, double y, double z) {

    public static final V3 ZERO = new V3(0, 0, 0);
    public static final V3 X = new V3(1, 0, 0);
    public static final V3 Y = new V3(0, 1, 0);
    public static final V3 Z = new V3(0, 0, 1);

    public V3 add(V3 o) {
        return new V3(x + o.x, y + o.y, z + o.z);
    }

    public V3 sub(V3 o) {
        return new V3(x - o.x, y - o.y, z - o.z);
    }

    public V3 mul(double k) {
        return new V3(x * k, y * k, z * k);
    }

    public V3 mul(V3 o) {
        return new V3(x * o.x, y * o.y, z * o.z);
    }

    public double dot(V3 o) {
        return x * o.x + y * o.y + z * o.z;
    }

    public V3 cross(V3 o) {
        return new V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);
    }

    public double len() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public V3 norm() {
        double l = len();
        return l < 1e-12 ? ZERO : mul(1 / l);
    }

    public double get(int i) {
        return i == 0 ? x : i == 1 ? y : z;
    }

    public V3 with(int i, double v) {
        return i == 0 ? new V3(v, y, z) : i == 1 ? new V3(x, v, z) : new V3(x, y, v);
    }

    public V3 abs() {
        return new V3(Math.abs(x), Math.abs(y), Math.abs(z));
    }

    public double max() {
        return Math.max(x, Math.max(y, z));
    }

    public static V3 min(V3 a, V3 b) {
        return new V3(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.min(a.z, b.z));
    }

    public static V3 max(V3 a, V3 b) {
        return new V3(Math.max(a.x, b.x), Math.max(a.y, b.y), Math.max(a.z, b.z));
    }

    public static V3 axis(int i) {
        return i == 0 ? X : i == 1 ? Y : Z;
    }
}
