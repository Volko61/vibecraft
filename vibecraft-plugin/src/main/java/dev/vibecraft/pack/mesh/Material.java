package dev.vibecraft.pack.mesh;

import java.awt.image.BufferedImage;

/**
 * Surface material of shapes: a colour (ARGB, alpha < 255 = translucent) and/or a texture of the module
 * (baked into the object's atlas with box projection or stretched per face), glow (light_emission),
 * double-sided flag and baked-light flag.
 */
final class Material {

    final String name;
    final int argb;
    /** "ns:path" of a module texture, or null. */
    final String texture;
    /** Decoded texture (null if none / unavailable). */
    final BufferedImage image;
    /** "box" (world-space projection, tiles every {@code tile} blocks) or "face" (whole texture on each face). */
    final String mapping;
    /** Size in blocks of one texture repeat (box mapping). */
    final double tile;
    /** Atlas texels per block for this material's faces (textured materials only). */
    final double density;
    final int glow;
    final boolean doubleSided;
    final boolean shade;

    Material(String name, int argb, String texture, BufferedImage image, String mapping, double tile, double density,
             int glow, boolean doubleSided, boolean shade) {
        this.name = name;
        this.argb = argb;
        this.texture = texture;
        this.image = image;
        this.mapping = mapping;
        this.tile = tile;
        this.density = density;
        this.glow = glow;
        this.doubleSided = doubleSided;
        this.shade = shade;
    }

    static Material color(String name, int argb) {
        return new Material(name, argb, null, null, "box", 1, 0, 0, false, true);
    }

    boolean textured() {
        return image != null;
    }

    Material withDoubleSided(boolean d) {
        return d == doubleSided ? this : new Material(name, argb, texture, image, mapping, tile, density, glow, d, shade);
    }

    Material withDensity(double d) {
        return new Material(name, argb, texture, image, mapping, tile, d, glow, doubleSided, shade);
    }
}
