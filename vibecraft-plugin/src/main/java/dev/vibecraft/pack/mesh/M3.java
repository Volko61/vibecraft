package dev.vibecraft.pack.mesh;

/** Immutable 3x3 matrix (row major), used as rotation (columns = local axes in the parent frame). */
public final class M3 {

    public static final M3 I = new M3(new double[] {1, 0, 0, 0, 1, 0, 0, 0, 1});

    final double[] m;

    private M3(double[] m) {
        this.m = m;
    }

    public static M3 cols(V3 a, V3 b, V3 c) {
        return new M3(new double[] {a.x(), b.x(), c.x(), a.y(), b.y(), c.y(), a.z(), b.z(), c.z()});
    }

    public static M3 diag(double a, double b, double c) {
        return new M3(new double[] {a, 0, 0, 0, b, 0, 0, 0, c});
    }

    public static M3 rotX(double r) {
        double c = StrictMath.cos(r);
        double s = StrictMath.sin(r);
        return new M3(new double[] {1, 0, 0, 0, c, -s, 0, s, c});
    }

    public static M3 rotY(double r) {
        double c = StrictMath.cos(r);
        double s = StrictMath.sin(r);
        return new M3(new double[] {c, 0, s, 0, 1, 0, -s, 0, c});
    }

    public static M3 rotZ(double r) {
        double c = StrictMath.cos(r);
        double s = StrictMath.sin(r);
        return new M3(new double[] {c, -s, 0, s, c, 0, 0, 0, 1});
    }

    /**
     * Rotation of the Minecraft model format's Euler form {@code {"x":..,"y":..,"z":..}} (degrees):
     * the client builds {@code Matrix4f.rotationZYX(z, y, x)} = Rz * Ry * Rx (X applied first).
     */
    public static M3 euler(double xDeg, double yDeg, double zDeg) {
        return rotZ(Math.toRadians(zDeg)).mul(rotY(Math.toRadians(yDeg))).mul(rotX(Math.toRadians(xDeg)));
    }

    /** Inverse of {@link #euler}: {x, y, z} in degrees. */
    public double[] toEuler() {
        double r20 = m[6];
        double cb = StrictMath.hypot(m[0], m[3]);
        double beta = StrictMath.atan2(-r20, cb);
        double alpha;
        double gamma;
        if (cb > 1e-9) {
            alpha = StrictMath.atan2(m[7], m[8]);
            gamma = StrictMath.atan2(m[3], m[0]);
        } else {
            // gimbal lock: put everything in z
            alpha = 0;
            gamma = StrictMath.atan2(-m[1], m[4]);
        }
        return new double[] {Math.toDegrees(alpha), Math.toDegrees(beta), Math.toDegrees(gamma)};
    }

    public M3 mul(M3 o) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = m[i * 3] * o.m[j] + m[i * 3 + 1] * o.m[3 + j] + m[i * 3 + 2] * o.m[6 + j];
            }
        }
        return new M3(r);
    }

    public V3 apply(V3 v) {
        return new V3(m[0] * v.x() + m[1] * v.y() + m[2] * v.z(),
                m[3] * v.x() + m[4] * v.y() + m[5] * v.z(),
                m[6] * v.x() + m[7] * v.y() + m[8] * v.z());
    }

    public V3 col(int j) {
        return new V3(m[j], m[3 + j], m[6 + j]);
    }

    public double get(int i, int j) {
        return m[i * 3 + j];
    }

    public M3 transpose() {
        return new M3(new double[] {m[0], m[3], m[6], m[1], m[4], m[7], m[2], m[5], m[8]});
    }

    public double det() {
        return m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    /** True if every entry is (almost) -1, 0 or 1: an axis permutation, expressible without rotation. */
    public boolean isSignedPermutation() {
        for (double v : m) {
            double a = Math.abs(v);
            if (a > 1e-7 && Math.abs(a - 1) > 1e-7) {
                return false;
            }
        }
        return true;
    }

    public boolean isIdentity() {
        for (int i = 0; i < 9; i++) {
            if (Math.abs(m[i] - I.m[i]) > 1e-9) {
                return false;
            }
        }
        return true;
    }
}
