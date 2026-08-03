package com.crystalgraphics.mc.platform.service;

import com.crystalgraphics.platform.input.CgCursor;
import com.crystalgraphics.platform.input.CgCursorBitmaps;
import com.crystalgraphics.platform.service.CgCursorService;
import net.minecraft.client.Minecraft;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Presents real OS cursors via GLFW, for the CSS {@code cursor} property the engine resolves.
 *
 * <p>The LWJGL3 counterpart of {@code CursorService1710}, and mostly the easier of the two: GLFW ships
 * standard cursors, so most keywords are a table lookup needing no artwork. <b>Not entirely
 * bitmap-free</b> though, which is the correction worth recording because {@code PlatformService1201}'s
 * old stub note claimed otherwise — see {@link CgCursor#SLIDE_ARROW} below.</p>
 *
 * <h3>⚠️ Compiled, but not run</h3>
 * <p>{@code mc1201} is commented out of {@code settings.gradle.kts}, so this is not part of any build.
 * It has been type-checked out-of-band against LWJGL 3.3.1 — the version MC 1.20.x ships — plus the real
 * {@code platform} classes, so the GLFW surface used here is known to exist and to have these signatures.
 * What is <em>not</em> verified is behaviour against a live driver, and the Minecraft window accessor,
 * which is the same one {@code RenderingService1201} already uses.</p>
 *
 * <h3>Standard first, artwork as the fallback</h3>
 * <p>Three shapes ask for a standard cursor that <b>GLFW only added in 3.4</b> — the two diagonal resizes
 * and the four-way move. LWJGL 3.3.1 does declare those constants (it bundles a GLFW 3.4 pre-release), so
 * they will usually work, but "usually" is doing real work in that sentence: a standard shape the loaded
 * native does not recognise comes back as {@code NULL} rather than throwing, and the cursor would simply
 * never appear. Rather than depend on which snapshot a given MC build shipped,
 * {@link #create} tries the standard cursor and falls back to {@link CgCursorBitmaps} when it comes back
 * empty. The artwork is already there, shared with mc1710, and costs nothing until it is needed.</p>
 *
 * <p>{@link CgCursor#SLIDE_ARROW} has no standard cursor in any GLFW version, because the gesture it
 * advertises has no toolkit standard behind it — that is exactly why the enum value exists. It is drawn,
 * always.</p>
 *
 * <h3>GLFW images are top-down — no flip, unlike LWJGL2</h3>
 * <p>{@code CursorService1710} has to mirror every bitmap vertically and measure the hotspot from the
 * bottom. GLFW takes rows top-to-bottom with the hotspot in the same space, which is already what
 * {@link CgCursorBitmaps} produces, so the conversion here is only ARGB ints to RGBA bytes. <b>Do not
 * copy the flip across from mc1710</b> — on a symmetric double-arrow a mirrored image is invisible, and
 * only the misplaced hotspot would eventually give it away.</p>
 */
public final class CursorService1201 implements CgCursorService {

    /**
     * No GLFW standard cursor for this shape — draw it.
     *
     * <p>On the outer class rather than inside {@link Shape} on purpose: a {@code static final} field of
     * an enum cannot be read from that enum's own constant list (JLS 8.3.3 illegal forward reference),
     * and the failure is a confusing compile error rather than anything to do with cursors.</p>
     */
    private static final int NO_STANDARD = -1;

    /**
     * The distinct pictures, as opposed to the keywords that ask for them.
     *
     * <p>Same reasoning as mc1710's: several keywords share one picture, and keying the cache on
     * {@link CgCursor} would allocate a separate native cursor per keyword.</p>
     *
     * <p>Every drawn shape here is symmetric, so its hotspot is the bitmap's centre. The one asymmetric
     * piece of artwork — the pointing hand, which must click at the fingertip — is a GLFW standard cursor
     * on this platform and never reaches the bitmap path.</p>
     */
    private enum Shape {
        HORIZONTAL_ARROW(GLFW.GLFW_HRESIZE_CURSOR, null),
        VERTICAL_ARROW(GLFW.GLFW_VRESIZE_CURSOR, null),
        TEXT_BEAM(GLFW.GLFW_IBEAM_CURSOR, null),
        POINTING_HAND(GLFW.GLFW_HAND_CURSOR, null),
        CROSSHAIR(GLFW.GLFW_CROSSHAIR_CURSOR, null),

        // GLFW 3.4 standards, with our own artwork behind them. @see the class javadoc.
        DIAGONAL_NWSE(GLFW.GLFW_RESIZE_NWSE_CURSOR, CgCursorBitmaps::diagonalNwseArrow),
        DIAGONAL_NESW(GLFW.GLFW_RESIZE_NESW_CURSOR, CgCursorBitmaps::diagonalNeswArrow),
        FOUR_WAY(GLFW.GLFW_RESIZE_ALL_CURSOR, CgCursorBitmaps::fourWayArrow),

        // No standard cursor exists for this one, in any GLFW version.
        SLIDE_ARROW(NO_STANDARD, CgCursorBitmaps::slideArrow);

        /** A {@code GLFW_*_CURSOR} constant, or {@link #NO_STANDARD}. */
        private final int standard;

        /** Artwork, or {@code null} when the standard cursor is the only option. Held as a supplier so
         * nothing is drawn at class-init — a cursor nobody asks for is never rendered. */
        private final Supplier<int[]> draw;

        Shape(int standard, Supplier<int[]> draw) {
            this.standard = standard;
            this.draw = draw;
        }
    }

    /** Keyed by shape, so identical pictures share one native handle. {@code NULL} is cached too —
     * "this platform cannot make it" is as stable an answer as a cursor. */
    private final Map<Shape, Long> cache = new EnumMap<>(Shape.class);
    private boolean supported = true;

    @Override
    public void setCursor(CgCursor cursor) {
        if (!supported) return;
        try {
            long window = Minecraft.getInstance().getWindow().getWindow();
            if (window == MemoryUtil.NULL) return;
            // NULL restores the system arrow — GLFW's own contract, and a better answer than a wrong
            // picture. @see #shapeFor
            GLFW.glfwSetCursor(window, resolve(cursor));
        } catch (RuntimeException e) {
            // One failure means this platform cannot do it; stop trying rather than throwing every time
            // the pointer crosses an element. A missing cursor is cosmetic, never functional.
            supported = false;
        }
    }

    /** @return the native cursor handle for {@code cursor}, or {@link MemoryUtil#NULL} for the arrow. */
    private long resolve(CgCursor cursor) {
        Shape shape = shapeFor(cursor);
        if (shape == null) return MemoryUtil.NULL;

        Long cached = cache.get(shape);
        if (cached != null) return cached;

        long created = create(shape);
        cache.put(shape, created);
        return created;
    }

    /** Standard cursor if the loaded GLFW knows it, our own artwork if not. @see CursorService1201 */
    private static long create(Shape shape) {
        if (shape.standard != NO_STANDARD) {
            long standard = GLFW.glfwCreateStandardCursor(shape.standard);
            if (standard != MemoryUtil.NULL) return standard;
        }
        return shape.draw == null ? MemoryUtil.NULL : createFromBitmap(shape.draw.get());
    }

    /**
     * Builds a native cursor from one of {@link CgCursorBitmaps}' ARGB images.
     *
     * <p>GLFW wants RGBA bytes in top-down rows, which is the order the bitmaps are already in — so the
     * only work is unpacking each {@code 0xAARRGGBB} int into four bytes.</p>
     */
    private static long createFromBitmap(int[] argb) {
        if (argb == null) return MemoryUtil.NULL;

        final int size = CgCursorBitmaps.SIZE;
        ByteBuffer pixels = BufferUtils.createByteBuffer(size * size * 4);
        for (int pixel : argb) {
            pixels.put((byte) ((pixel >> 16) & 0xFF));   // R
            pixels.put((byte) ((pixel >> 8) & 0xFF));    // G
            pixels.put((byte) (pixel & 0xFF));           // B
            pixels.put((byte) ((pixel >> 24) & 0xFF));   // A
        }
        pixels.flip();

        GLFWImage image = GLFWImage.malloc();
        try {
            image.set(size, size, pixels);
            return GLFW.glfwCreateCursor(image, CgCursorBitmaps.HOTSPOT, CgCursorBitmaps.HOTSPOT);
        } finally {
            // The native cursor owns a copy by now; the descriptor is ours to free.
            image.free();
        }
    }

    /**
     * Which keywords we have a cursor for. Everything else falls through to the system arrow, which is a
     * better answer than a wrong shape — and that is most of the set, deliberately: {@link CgCursor}
     * carries all of CSS's keywords while only a handful matter to this UI.
     */
    private static Shape shapeFor(CgCursor cursor) {
        switch (cursor) {
            case EW_RESIZE: case COL_RESIZE: case E_RESIZE: case W_RESIZE:
                return Shape.HORIZONTAL_ARROW;
            case NS_RESIZE: case ROW_RESIZE: case N_RESIZE: case S_RESIZE:
                return Shape.VERTICAL_ARROW;
            case NWSE_RESIZE: case NW_RESIZE: case SE_RESIZE:
                return Shape.DIAGONAL_NWSE;
            case NESW_RESIZE: case NE_RESIZE: case SW_RESIZE:
                return Shape.DIAGONAL_NESW;
            case MOVE: case ALL_SCROLL: case GRABBING:
                return Shape.FOUR_WAY;
            case TEXT:
                return Shape.TEXT_BEAM;
            case CROSSHAIR:
                return Shape.CROSSHAIR;
            case POINTER: case GRAB:
                return Shape.POINTING_HAND;
            // Bare opposed triangles, NOT the horizontal arrow above. A node editor puts a scrubbable
            // number inside a resizable panel, so "this value slides" and "this edge moves" appear pixels
            // apart and have to be distinguishable — see CgCursor.SLIDE_ARROW.
            case SLIDE_ARROW:
                return Shape.SLIDE_ARROW;
            default:
                return null;
        }
    }
}
