package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.CgService;

/**
 * Presents a mouse cursor. One method, and it knows nothing about what a cursor <i>means</i>.
 *
 * <pre>{@code
 * // a consumer, which owns the vocabulary:
 * CgPlatform.get(CgCursorService.SERVICE).show(CgCursorService.Image.standard("pointing-hand"));
 * CgPlatform.get(CgCursorService.SERVICE).show(null);   // back to the system arrow
 *
 * // a loader, once, with whatever value its toolkit needs:
 * CgPlatform.provide(CgCursorService.SERVICE, new GlfwCursorService(window::handle));
 * }</pre>
 *
 * <p><b>A slot, not a bundle method</b> — an unpresented cursor is cosmetic, and this engine runs
 * where there is nothing to present to: a dedicated server, a headless test, a fixture with no
 * window. Those register nothing and read {@link #NONE}, rather than being made to supply a stub.
 *
 * <p>Easy to get wrong, in a line each:
 * <ul>
 *   <li><b>{@code show(null)} is the reset</b>, not a no-op — it asks for the system default.</li>
 *   <li>An implementation <b>caches by {@link Image#name()}</b>. Uploading pixels per call leaks a
 *       native handle every time the pointer crosses an element.</li>
 *   <li>An implementation <b>must not throw.</b> This is called from hover handling on the frame
 *       thread; a toolkit that cannot honour a shape gives the caller the default and stops trying.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgCursorService {

    /** Presents nothing. What a server, a test or any host with no window reads. */
    CgCursorService NONE = image -> { };

    CgService<CgCursorService> SERVICE = CgService.of("crystalgraphics:cursor", NONE);

    /**
     * Show {@code image}, or the system default when it is null.
     *
     * @param image the picture to present, or null to restore the default arrow
     */
    void show(Image image);

    /**
     * One cursor picture, in the only terms a windowing toolkit needs: a stable name, optionally some
     * pixels, and the point inside them that counts as "where the pointer is".
     *
     * <p>Nested here because it exists for nothing else. Built by whoever owns the cursor vocabulary —
     * this package owns none. A UI layer decides that its resize handle looks like a diagonal arrow
     * and hands the result over; the adapter decides only whether it can draw that itself.
     *
     * <pre>{@code
     * Image beam  = Image.of("text-beam", pixels, 32, 32, 16, 16);   // artwork we supply
     * Image arrow = Image.standard("pointing-hand");                 // a shape the toolkit ships
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
    record Image(String name, int[] argb, int width, int height, int hotspotX, int hotspotY) {

        /** A picture we supply. */
        public static Image of(String name, int[] argb, int width, int height,
                               int hotspotX, int hotspotY) {
            return new Image(name, argb, width, height, hotspotX, hotspotY);
        }

        /** A shape the toolkit is expected to ship itself; carries no pixels. */
        public static Image standard(String name) {
            return new Image(name, null, 0, 0, 0, 0);
        }

        /** Whether this carries pixels of its own, as opposed to naming a shape the toolkit has. */
        public boolean hasPixels() {
            return argb != null && width > 0 && height > 0;
        }
    }
}
