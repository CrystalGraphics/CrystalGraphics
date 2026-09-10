package com.crystalgraphics.platform.service;

/**
 * One cursor picture, in the only terms a windowing toolkit needs: a stable name, optionally some
 * pixels, and the point inside them that counts as "where the pointer is".
 *
 * <p>Built by whoever owns the cursor vocabulary — this package owns none. A UI layer decides that
 * its resize handle looks like a diagonal arrow and hands the result here; the toolkit adapter
 * decides only whether it can draw that itself.
 *
 * <pre>{@code
 * // artwork we supply
 * CgCursorImage beam = CgCursorImage.of("text-beam", pixels, 32, 32, 16, 16);
 *
 * // a shape the toolkit already ships, so no pixels at all
 * CgCursorImage arrow = CgCursorImage.standard("pointing-hand");
 *
 * CgPlatform.get(CgCursorService.SERVICE).show(beam);
 * }</pre>
 *
 * <p><b>{@link #name()} is the identity, not the pixels.</b> An adapter maps it to a native shape
 * when its toolkit has one and caches by it otherwise, so the same name must always mean the same
 * picture. {@link #argb()} is null exactly when the name is all there is.
 *
 * @param name      stable identifier; the key an adapter caches and maps by
 * @param argb      row-major ARGB pixels, top row first, or null to ask for the toolkit's own shape
 * @param width     pixel width, ignored when {@code argb} is null
 * @param height    pixel height, ignored when {@code argb} is null
 * @param hotspotX  the active pixel's column, measured from the left
 * @param hotspotY  the active pixel's row, measured from the top
 */
public record CgCursorImage(String name, int[] argb, int width, int height,
                            int hotspotX, int hotspotY) {

    /** A picture we supply. */
    public static CgCursorImage of(String name, int[] argb, int width, int height,
                                   int hotspotX, int hotspotY) {
        return new CgCursorImage(name, argb, width, height, hotspotX, hotspotY);
    }

    /** A shape the toolkit is expected to ship itself; carries no pixels. */
    public static CgCursorImage standard(String name) {
        return new CgCursorImage(name, null, 0, 0, 0, 0);
    }

    /** Whether this carries pixels of its own, as opposed to naming a shape the toolkit has. */
    public boolean hasPixels() {
        return argb != null && width > 0 && height > 0;
    }
}
