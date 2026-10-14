package dev.vibecraft.pack.mesh;

import java.util.ArrayList;
import java.util.List;

/**
 * Exact geometry builders. Everything becomes oriented cuboids ({@link Elem}), which the 26.3 client
 * accepts at any angle (Euler x/y/z rotation of an element around an origin):
 * <ul>
 *   <li>box: one element;</li>
 *   <li>straight prism (cylinder) with an even number n of sides: n/2 overlapping boxes, each one spanning
 *       two opposite sides of the polygon (the union of these "side-to-side" rectangles is exactly the regular
 *       n-gon), so a 24-sided cylinder costs 12 elements;</li>
 *   <li>any planar polygon (cone/sphere facets, OBJ faces, extrusion caps, windows): coplanar zero-thickness
 *       plates, one rectangle when the polygon is a rectangle, else strips whose outline deviates by at most
 *       {@code tol} from the true edges (covering, so neighbouring facets never leave holes).</li>
 * </ul>
 */
final class Geometry {

    static final int MAX_STRIPS = 48;

    private Geometry() {
    }

    // ------------------------------------------------------------------ boxes & prisms

    static Elem box(V3 c, M3 r, V3 size, Material[] faces) {
        Elem e = new Elem(c, r, size.mul(0.5).abs());
        System.arraycopy(faces, 0, e.faces, 0, 6);
        e.solid = true;
        return e;
    }

    /**
     * Regular prism along the local y axis of {@code frame}, centred at {@code c}: polygon vertices on the
     * circle of radius {@code circumradius} at angles (2j+1)pi/n, sides facing 2*pi*j/n.
     */
    static void prism(List<Elem> out, V3 c, M3 frame, double circumradius, double height, int n,
                      Material side, Material top, Material bottom) {
        if (circumradius <= 0 || height <= 0) {
            return;
        }
        int k = n / 2;
        double a = circumradius * StrictMath.cos(Math.PI / n);
        double s = 2 * circumradius * StrictMath.sin(Math.PI / n);
        for (int i = 0; i < k; i++) {
            double th = 2 * Math.PI * i / n;
            V3 d = new V3(StrictMath.cos(th), 0, StrictMath.sin(th));
            V3 t = V3.Y.cross(d);
            Elem e = new Elem(c, frame.mul(M3.cols(t, V3.Y, d)), new V3(s / 2, height / 2, a));
            e.faces[Elem.SOUTH] = side;
            e.faces[Elem.NORTH] = side;
            e.faces[Elem.UP] = top;
            e.faces[Elem.DOWN] = bottom;
            e.solid = true;
            out.add(e);
        }
    }

    /** Regular n-gon disc in the local xz plane of frame at c, facing local +y (up) or -y: n/2 plates. */
    static void disc(List<Elem> out, V3 c, M3 frame, double circumradius, int n, Material m, boolean up) {
        if (circumradius <= 0 || m == null) {
            return;
        }
        int k = n / 2;
        double a = circumradius * StrictMath.cos(Math.PI / n);
        double s = 2 * circumradius * StrictMath.sin(Math.PI / n);
        for (int i = 0; i < k; i++) {
            double th = 2 * Math.PI * i / n;
            V3 d = new V3(StrictMath.cos(th), 0, StrictMath.sin(th));
            V3 t = V3.Y.cross(d);
            M3 local = up ? M3.cols(d, t, V3.Y) : M3.cols(d, t.mul(-1), V3.Y.mul(-1));
            Elem e = new Elem(c, frame.mul(local), new V3(a, s / 2, 0));
            e.faces[Elem.SOUTH] = m;
            if (m.doubleSided) {
                e.faces[Elem.NORTH] = m;
            }
            out.add(e);
        }
    }

    // ------------------------------------------------------------------ polygons

    /** Newell normal (not normalised; zero for degenerate polygons). */
    static V3 newell(List<V3> p) {
        double nx = 0;
        double ny = 0;
        double nz = 0;
        for (int i = 0; i < p.size(); i++) {
            V3 a = p.get(i);
            V3 b = p.get((i + 1) % p.size());
            nx += (a.y() - b.y()) * (a.z() + b.z());
            ny += (a.z() - b.z()) * (a.x() + b.x());
            nz += (a.x() - b.x()) * (a.y() + b.y());
        }
        return new V3(nx, ny, nz);
    }

    /**
     * Planar polygon (counter-clockwise seen from the front) -> plates. Non-planar input is projected on
     * its best-fit plane. Returns the number of plates added.
     */
    static int polygon(List<Elem> out, List<V3> pts, Material m, double tol) {
        List<V3> p = dedupe(pts);
        if (p.size() < 3 || m == null) {
            return 0;
        }
        V3 nRaw = newell(p);
        double area2 = nRaw.len();
        if (area2 < 1e-12) {
            return 0;
        }
        V3 n = nRaw.mul(1 / area2);
        // strip direction u: the edge direction needing the fewest strips (a rectangle -> 1, a trapezoid -> its
        // parallel sides, a triangle -> usually its longest edge)
        V3 u = null;
        double bestK = Double.MAX_VALUE;
        double bestLen = -1;
        for (int i = 0; i < p.size(); i++) {
            V3 e = p.get((i + 1) % p.size()).sub(p.get(i));
            V3 ep = e.sub(n.mul(e.dot(n)));
            double l = ep.len();
            if (l < 1e-9) {
                continue;
            }
            V3 cu = ep.mul(1 / l);
            V3 cv = n.cross(cu);
            double[][] cq = new double[p.size()][2];
            for (int j = 0; j < p.size(); j++) {
                V3 d = p.get(j).sub(p.get(0));
                cq[j][0] = d.dot(cu);
                cq[j][1] = d.dot(cv);
            }
            double k = stripCount(cq, tol);
            if (k < bestK - 1e-9 || (Math.abs(k - bestK) <= 1e-9 && l > bestLen)) {
                bestK = k;
                bestLen = l;
                u = cu;
            }
        }
        if (u == null) {
            return 0;
        }
        V3 v = n.cross(u);
        V3 o = p.get(0);
        double[][] q = new double[p.size()][2];
        double off = 0;
        for (int i = 0; i < p.size(); i++) {
            V3 d = p.get(i).sub(o);
            q[i][0] = d.dot(u);
            q[i][1] = d.dot(v);
            off += d.dot(n);
        }
        o = o.add(n.mul(off / p.size())); // best-fit plane through the mean offset
        M3 r = M3.cols(u, v, n);
        int added = 0;
        for (double[] rect : strips(q, tol)) {
            double w = rect[1] - rect[0];
            double h = rect[3] - rect[2];
            if (w < 1e-7 || h < 1e-7) {
                continue;
            }
            V3 c = o.add(u.mul((rect[0] + rect[1]) / 2)).add(v.mul((rect[2] + rect[3]) / 2));
            Elem e = new Elem(c, r, new V3(w / 2, h / 2, 0));
            e.faces[Elem.SOUTH] = m;
            if (m.doubleSided) {
                e.faces[Elem.NORTH] = m;
            }
            out.add(e);
            added++;
        }
        return added;
    }

    static List<V3> dedupe(List<V3> pts) {
        List<V3> p = new ArrayList<>();
        for (V3 x : pts) {
            if (p.isEmpty() || p.get(p.size() - 1).sub(x).len() > 1e-9) {
                p.add(x);
            }
        }
        while (p.size() > 1 && p.get(0).sub(p.get(p.size() - 1)).len() <= 1e-9) {
            p.remove(p.size() - 1);
        }
        return p;
    }

    /** Number of strips {@link #strips} would use (1..MAX_STRIPS), or 0 for a degenerate polygon. */
    static int stripCount(double[][] q, double tol) {
        double vmin = Double.MAX_VALUE;
        double vmax = -Double.MAX_VALUE;
        for (double[] p : q) {
            vmin = Math.min(vmin, p[1]);
            vmax = Math.max(vmax, p[1]);
        }
        double hgt = vmax - vmin;
        if (hgt < 1e-9) {
            return 0;
        }
        // A strip of height sh overhangs an edge by a sliver whose distance to the true edge is at most
        // min(sh, dv) * cos(edge angle): only edges where that can exceed tol constrain the strip height.
        double maxCos = 0;
        for (int i = 0; i < q.length; i++) {
            double[] a = q[i];
            double[] b = q[(i + 1) % q.length];
            double dv = Math.abs(b[1] - a[1]);
            double du = Math.abs(b[0] - a[0]);
            double len = StrictMath.hypot(du, dv);
            if (len < 1e-12) {
                continue;
            }
            double cos = du / len;
            if (dv * cos > tol) {
                maxCos = Math.max(maxCos, cos);
            }
        }
        int k = 1;
        if (maxCos > 1e-12 && tol > 0) {
            k = (int) Math.ceil(hgt * maxCos / tol - 1e-9);
        }
        return Math.max(1, Math.min(MAX_STRIPS, k));
    }

    /** Angle (radians) of the edge direction of a 2D polygon needing the fewest strips. */
    static double bestStripAngle(double[][] q, double tol) {
        double best = 0;
        double bestK = Double.MAX_VALUE;
        for (int i = 0; i < q.length; i++) {
            double[] a = q[i];
            double[] b = q[(i + 1) % q.length];
            double dx = b[0] - a[0];
            double dy = b[1] - a[1];
            if (StrictMath.hypot(dx, dy) < 1e-9) {
                continue;
            }
            double ang = StrictMath.atan2(dy, dx);
            int k = stripCount(rotate2d(q, -ang), tol);
            if (k > 0 && k < bestK) {
                bestK = k;
                best = ang;
            }
        }
        return best;
    }

    static double[][] rotate2d(double[][] q, double ang) {
        double c = StrictMath.cos(ang);
        double s = StrictMath.sin(ang);
        double[][] o = new double[q.length][];
        for (int i = 0; i < q.length; i++) {
            o[i] = new double[] {q[i][0] * c - q[i][1] * s, q[i][0] * s + q[i][1] * c};
        }
        return o;
    }

    /**
     * Even-odd fill of a 2D polygon (any shape) by horizontal strips: list of {u0, u1, v0, v1}. Each strip
     * covers the union of the polygon's cross-sections over its height (outer approximation): the outline
     * error (distance to the true edges) is at most {@code tol} (bounded by {@link #MAX_STRIPS}).
     */
    static List<double[]> strips(double[][] q, double tol) {
        double vmin = Double.MAX_VALUE;
        double vmax = -Double.MAX_VALUE;
        for (double[] p : q) {
            vmin = Math.min(vmin, p[1]);
            vmax = Math.max(vmax, p[1]);
        }
        double hgt = vmax - vmin;
        List<double[]> out = new ArrayList<>();
        if (hgt < 1e-9) {
            return out;
        }
        int k = stripCount(q, tol);
        double eps = hgt * 1e-7;
        for (int s = 0; s < k; s++) {
            double v0 = vmin + hgt * s / k;
            double v1 = s == k - 1 ? vmax : vmin + hgt * (s + 1) / k;
            List<Double> samples = new ArrayList<>();
            samples.add(v0 + eps);
            samples.add(v1 - eps);
            for (double[] p : q) {
                if (p[1] > v0 + eps && p[1] < v1 - eps) {
                    samples.add(p[1] - eps);
                    samples.add(p[1] + eps);
                }
            }
            List<double[]> merged = new ArrayList<>();
            for (double sv : samples) {
                for (double[] iv : crossings(q, sv)) {
                    merged.add(iv);
                }
            }
            for (double[] iv : union(merged)) {
                out.add(new double[] {iv[0], iv[1], v0, v1});
            }
        }
        return out;
    }

    private static List<double[]> crossings(double[][] q, double v) {
        List<Double> xs = new ArrayList<>();
        for (int i = 0; i < q.length; i++) {
            double[] a = q[i];
            double[] b = q[(i + 1) % q.length];
            if ((a[1] <= v && b[1] > v) || (b[1] <= v && a[1] > v)) {
                double t = (v - a[1]) / (b[1] - a[1]);
                xs.add(a[0] + t * (b[0] - a[0]));
            }
        }
        xs.sort(Double::compare);
        List<double[]> iv = new ArrayList<>();
        for (int i = 0; i + 1 < xs.size(); i += 2) {
            iv.add(new double[] {xs.get(i), xs.get(i + 1)});
        }
        return iv;
    }

    private static List<double[]> union(List<double[]> iv) {
        iv.sort((a, b) -> Double.compare(a[0], b[0]));
        List<double[]> out = new ArrayList<>();
        for (double[] x : iv) {
            if (!out.isEmpty() && x[0] <= out.get(out.size() - 1)[1] + 1e-9) {
                double[] last = out.get(out.size() - 1);
                last[1] = Math.max(last[1], x[1]);
            } else {
                out.add(new double[] {x[0], x[1]});
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ surfaces of revolution

    /**
     * Lathe around the local y axis: profile points {r, y} (bottom to top; reversed automatically when given
     * top to bottom). Bands of constant radius become solid prisms (n/2 boxes), slanted bands planar facets.
     * {@code scale} != (1,1,1) (ellipsoids) forces facets everywhere. Polygons are transformed by
     * {@code frame}/{@code c} after scaling.
     */
    static void lathe(List<Elem> out, V3 c, M3 frame, double[][] profileIn, int n, V3 scale,
                      Material side, Material top, Material bottom, double tol) {
        double[][] profile = profileIn;
        if (profile.length >= 2 && profile[profile.length - 1][1] < profile[0][1]) {
            profile = new double[profileIn.length][];
            for (int i = 0; i < profileIn.length; i++) {
                profile[i] = profileIn[profileIn.length - 1 - i];
            }
        }
        boolean uniform = Math.abs(scale.x() - 1) < 1e-9 && Math.abs(scale.y() - 1) < 1e-9 && Math.abs(scale.z() - 1) < 1e-9;
        int last = profile.length - 1;
        for (int i = 0; i < last; i++) {
            double r0 = Math.max(0, profile[i][0]);
            double y0 = profile[i][1];
            double r1 = Math.max(0, profile[i + 1][0]);
            double y1 = profile[i + 1][1];
            if (r0 < 1e-9 && r1 < 1e-9) {
                continue;
            }
            if (uniform && Math.abs(r0 - r1) < 1e-9 && y1 - y0 > 1e-9) {
                List<Elem> band = new ArrayList<>();
                prism(band, new V3(0, (y0 + y1) / 2, 0), M3.I, r0, y1 - y0, n, side,
                        i == last - 1 ? top : null, i == 0 ? bottom : null);
                for (Elem e : band) {
                    out.add(e.transformed(frame, c));
                }
                continue;
            }
            double dr = r1 - r0;
            double dy = y1 - y0;
            double ndr = dy; // outward normal in the (r, y) half plane: profile direction turned right
            double ndy = -dr;
            for (int j = 0; j < n; j++) {
                double pa = (2 * j + 1) * Math.PI / n;
                double pb = (2 * j + 3) * Math.PI / n;
                List<V3> poly = new ArrayList<>(List.of(
                        new V3(r0 * StrictMath.cos(pa), y0, r0 * StrictMath.sin(pa)),
                        new V3(r0 * StrictMath.cos(pb), y0, r0 * StrictMath.sin(pb)),
                        new V3(r1 * StrictMath.cos(pb), y1, r1 * StrictMath.sin(pb)),
                        new V3(r1 * StrictMath.cos(pa), y1, r1 * StrictMath.sin(pa))));
                double pm = (pa + pb) / 2;
                V3 expected = new V3(StrictMath.cos(pm) * ndr, ndy, StrictMath.sin(pm) * ndr);
                if (newell(poly).dot(expected) < 0) {
                    java.util.Collections.reverse(poly);
                }
                polygon(out, transform(poly, scale, frame, c), side, tol);
            }
        }
        // end caps
        double rb = profile[0][0];
        double rt = profile[last][0];
        boolean firstIsPrism = uniform && last >= 1 && Math.abs(profile[0][0] - profile[1][0]) < 1e-9 && profile[1][1] - profile[0][1] > 1e-9;
        boolean lastIsPrism = uniform && last >= 1 && Math.abs(profile[last][0] - profile[last - 1][0]) < 1e-9
                && profile[last][1] - profile[last - 1][1] > 1e-9;
        if (bottom != null && rb > 1e-9 && !firstIsPrism) {
            cap(out, c, frame, rb, profile[0][1], n, scale, uniform, bottom, false, tol);
        }
        if (top != null && rt > 1e-9 && !lastIsPrism) {
            cap(out, c, frame, rt, profile[last][1], n, scale, uniform, top, true, tol);
        }
    }

    private static void cap(List<Elem> out, V3 c, M3 frame, double r, double y, int n, V3 scale, boolean uniform,
                            Material m, boolean up, double tol) {
        if (uniform) {
            List<Elem> d = new ArrayList<>();
            disc(d, new V3(0, y, 0), M3.I, r, n, m, up);
            for (Elem e : d) {
                out.add(e.transformed(frame, c));
            }
            return;
        }
        List<V3> poly = new ArrayList<>();
        for (int j = 0; j < n; j++) {
            double a = (2 * j + 1) * Math.PI / n;
            poly.add(new V3(r * StrictMath.cos(a), y, r * StrictMath.sin(a)));
        }
        if (newell(poly).y() * (up ? 1 : -1) < 0) {
            java.util.Collections.reverse(poly);
        }
        polygon(out, transform(poly, scale, frame, c), m, tol);
    }

    static List<V3> transform(List<V3> pts, V3 scale, M3 frame, V3 c) {
        List<V3> o = new ArrayList<>(pts.size());
        for (V3 p : pts) {
            o.add(c.add(frame.apply(p.mul(scale))));
        }
        return o;
    }

    /** Torus in the local xz plane: major radius R, tube radius r; each of the n segments is a straight prism. */
    static void torus(List<Elem> out, V3 c, M3 frame, double major, double tube, int n, int tubeSides, Material m) {
        if (major <= 0 || tube <= 0) {
            return;
        }
        double len = 2 * (major + tube) * StrictMath.tan(Math.PI / n);
        for (int i = 0; i < n; i++) {
            double psi = 2 * Math.PI * i / n;
            V3 rad = new V3(StrictMath.cos(psi), 0, StrictMath.sin(psi));
            V3 tan = new V3(-StrictMath.sin(psi), 0, StrictMath.cos(psi));
            M3 seg = M3.cols(rad, tan, rad.cross(tan));
            List<Elem> p = new ArrayList<>();
            prism(p, rad.mul(major), seg, tube / StrictMath.cos(Math.PI / tubeSides), len, tubeSides, m, null, null);
            for (Elem e : p) {
                out.add(e.transformed(frame, c));
            }
        }
    }

    // ------------------------------------------------------------------ extrusion

    /**
     * 2D polygon (local x/y, any shape, any winding) extruded along local z over {@code depth} (centred):
     * caps = solid strip boxes through the whole depth, walls = one plate per edge.
     */
    static void extrude(List<Elem> out, V3 c, M3 frame, double[][] pts, double depth, Material capMat, Material sideMat,
                        double tol) {
        int n = pts.length;
        if (n < 3 || depth <= 0) {
            return;
        }
        double area = 0;
        for (int i = 0; i < n; i++) {
            double[] a = pts[i];
            double[] b = pts[(i + 1) % n];
            area += a[0] * b[1] - b[0] * a[1];
        }
        double[][] q = pts;
        if (area < 0) {
            q = new double[n][];
            for (int i = 0; i < n; i++) {
                q[i] = pts[n - 1 - i];
            }
        }
        // walls
        if (sideMat != null) {
            for (int i = 0; i < n; i++) {
                double[] a = q[i];
                double[] b = q[(i + 1) % n];
                double dx = b[0] - a[0];
                double dy = b[1] - a[1];
                double l = StrictMath.hypot(dx, dy);
                if (l < 1e-9) {
                    continue;
                }
                V3 u = new V3(dx / l, dy / l, 0);
                V3 nrm = u.cross(V3.Z);
                Elem e = new Elem(new V3((a[0] + b[0]) / 2, (a[1] + b[1]) / 2, 0), M3.cols(u, V3.Z, nrm),
                        new V3(l / 2, depth / 2, 0));
                e.faces[Elem.SOUTH] = sideMat;
                if (sideMat.doubleSided) {
                    e.faces[Elem.NORTH] = sideMat;
                }
                out.add(e.transformed(frame, c));
            }
        }
        // caps
        if (capMat != null) {
            double ang = bestStripAngle(q, tol);
            M3 rz = M3.rotZ(ang);
            for (double[] r : strips(rotate2d(q, -ang), tol)) {
                double w = r[1] - r[0];
                double h = r[3] - r[2];
                if (w < 1e-7 || h < 1e-7) {
                    continue;
                }
                V3 cc = rz.apply(new V3((r[0] + r[1]) / 2, (r[2] + r[3]) / 2, 0));
                Elem e = new Elem(cc, rz, new V3(w / 2, h / 2, depth / 2));
                e.faces[Elem.SOUTH] = capMat;
                e.faces[Elem.NORTH] = capMat;
                e.solid = true;
                out.add(e.transformed(frame, c));
            }
        }
    }

    // ------------------------------------------------------------------ rounded box

    /**
     * Box with rounded edges of radius {@code round}: 3 inner boxes + 12 edge prisms (n sides, flush with the
     * faces). Corners are closed by the prisms' end caps.
     */
    static void roundedBox(List<Elem> out, V3 c, M3 frame, V3 size, double round, int n, Material[] faces) {
        V3 h = size.mul(0.5).abs();
        double rr = Math.min(round, Math.min(h.x(), Math.min(h.y(), h.z())) * 0.999);
        if (rr <= 1e-9) {
            out.add(box(c, frame, size, faces));
            return;
        }
        // three slabs, each reduced by rr on two axes
        for (int ax = 0; ax < 3; ax++) {
            V3 hs = new V3(ax == 0 ? h.x() : h.x() - rr, ax == 1 ? h.y() : h.y() - rr, ax == 2 ? h.z() : h.z() - rr);
            Elem e = new Elem(V3.ZERO, M3.I, hs);
            e.faces[Elem.dirOf(ax, -1)] = faces[Elem.dirOf(ax, -1)];
            e.faces[Elem.dirOf(ax, 1)] = faces[Elem.dirOf(ax, 1)];
            e.solid = true;
            out.add(e.transformed(frame, c));
        }
        double circ = rr / StrictMath.cos(Math.PI / n);
        Material side = faces[Elem.UP] != null ? faces[Elem.UP] : faces[Elem.SOUTH];
        for (int ax = 0; ax < 3; ax++) {
            int a1 = (ax + 1) % 3;
            int a2 = (ax + 2) % 3;
            double len = 2 * (h.get(ax) - rr);
            // local frame: prism axis (local y) along world axis ax
            V3 yAxis = V3.axis(ax);
            V3 xAxis = V3.axis(a1);
            V3 zAxis = xAxis.cross(yAxis);
            M3 pf = M3.cols(xAxis, yAxis, zAxis);
            for (int s1 = -1; s1 <= 1; s1 += 2) {
                for (int s2 = -1; s2 <= 1; s2 += 2) {
                    V3 pc = V3.ZERO.with(a1, s1 * (h.get(a1) - rr)).with(a2, s2 * (h.get(a2) - rr));
                    List<Elem> p = new ArrayList<>();
                    if (len > 1e-9) {
                        prism(p, pc, pf, circ, len, n, side, side, side);
                    }
                    for (Elem e : p) {
                        out.add(e.transformed(frame, c));
                    }
                }
            }
        }
    }
}
