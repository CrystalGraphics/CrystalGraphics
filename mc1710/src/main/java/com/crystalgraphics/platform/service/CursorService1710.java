package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.input.CgCursorBitmaps;
import com.crystalgraphics.platform.input.CgCursor;
import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Mouse;

import java.nio.IntBuffer;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Presents real OS cursors via LWJGL2, for the CSS {@code cursor} property the engine resolves.
 *
 * <p>Lives in the loader because {@code platform/} and {@code core/} may not import LWJGL — only the
 * artwork is shared, and {@link CgCursorBitmaps} is pure pixel maths, so this adapter is the entire
 * per-loader cost. An LWJGL3/GLFW loader has an easier job still, since
 * <code>glfwCreateStandardCursor</code> covers the whole resize set and needs no bitmaps.</p>
 *
 * <p><b>Cursors are created lazily</b>, on the first {@link #setCursor} that needs one, so constructing
 * this service before the LWJGL display exists is fine — the capability query is deferred with them.</p>
 *
 * <h3>Three LWJGL2 details that are easy to get wrong</h3>
 * <ol>
 *   <li><b>Images are bottom-up, and the hotspot's Y is measured from the bottom.</b> Every drawing
 *       function in {@link CgCursorBitmaps} works top-down like the rest of this codebase, so the flip
 *       happens here, once, in {@link #toCursor}. Getting this wrong produces a vertically mirrored
 *       cursor whose hotspot is at the wrong end — and for a symmetric double-arrow the mirroring is
 *       invisible, so only the hotspot would give it away.</li>
 *   <li><b>Cursors must be a size the platform accepts</b>, which in practice means 32×32 on Windows.
 *       {@code Cursor.getMinCursorSize()}/{@code getMaxCursorSize()} are checked rather than assumed,
 *       and a platform outside that range gets the system arrow instead of a crash.</li>
 *   <li><b>Native cursors need the capability bit.</b> Without
 *       {@code CURSOR_ONE_BIT_TRANSPARENCY} there is no cursor support at all, and creating one
 *       throws.</li>
 * </ol>
 *
 * <p>Cursors are created lazily and cached <b>by shape</b> (see {@link Shape}), so the mapped keywords
 * share far fewer native objects than there are of them. Anything without artwork maps
 * to {@code null}, which is LWJGL2's way of restoring the system arrow — a better answer than a wrong
 * picture, and the right one for most of the keyword set.</p>
 */
public final class CursorService1710 implements CgCursorService {

    /**
     * The distinct pictures, as opposed to the CSS keywords that ask for them.
     *
     * <p><b>Caching by shape rather than by keyword is the point.</b> Eighteen keywords map onto the
     * handful below — {@code ew-resize}, {@code col-resize}, {@code e-resize} and {@code w-resize} all
     * want the same horizontal arrow. Keying the cache on {@link CgCursor} instead allocated a separate
     * native cursor object per keyword: seventeen natives for a handful of images, each one a real OS
     * handle. (Deliberately not a count — it was already one out of date.)</p>
     */
    private enum Shape {
        HORIZONTAL_ARROW(CgCursorBitmaps::horizontalDoubleArrow),
        VERTICAL_ARROW(CgCursorBitmaps::verticalDoubleArrow),
        DIAGONAL_NWSE(CgCursorBitmaps::diagonalNwseArrow),
        DIAGONAL_NESW(CgCursorBitmaps::diagonalNeswArrow),
        FOUR_WAY(CgCursorBitmaps::fourWayArrow),
        TEXT_BEAM(CgCursorBitmaps::textBeam),
        SLIDE_ARROW(CgCursorBitmaps::slideArrow),
        // The one shape whose hotspot is not its centre: a hand points, and the click must land on the
        // fingertip rather than half a cursor below it.
        POINTING_HAND(CgCursorBitmaps::pointingHand,
                CgCursorBitmaps.HAND_HOTSPOT_X, CgCursorBitmaps.HAND_HOTSPOT_Y);

        private final Supplier<int[]> draw;
        private final int hotspotX;
        private final int hotspotY;

        Shape(Supplier<int[]> draw) {
            this(draw, CgCursorBitmaps.HOTSPOT, CgCursorBitmaps.HOTSPOT);
        }

        Shape(Supplier<int[]> draw, int hotspotX, int hotspotY) {
            this.draw = draw;
            this.hotspotX = hotspotX;
            this.hotspotY = hotspotY;
        }
    }

    /** Keyed by shape, so identical pictures share one native object. Null values are cached too —
     * "this platform cannot draw it" is as stable an answer as a cursor. */
    private final Map<Shape, Cursor> cache = new EnumMap<>(Shape.class);
    private boolean supported = true;
    /** Tri-state: null until first asked, since the capability query needs a live display. */
    private Boolean cursorsAvailable;

    @Override
    public void setCursor(CgCursor cursor) {
        if (!supported) return;
        try {
            Mouse.setNativeCursor(resolve(cursor));
        } catch (LWJGLException | RuntimeException e) {
            // One failure means this platform cannot do it; stop trying rather than throwing every
            // time the pointer crosses an element. A missing cursor is cosmetic.
            supported = false;
        }
    }

    /** @return the native cursor for {@code cursor}, or {@code null} to use the system arrow. */
    private Cursor resolve(CgCursor cursor) {
        Shape shape = shapeFor(cursor);
        if (shape == null) return null; // no artwork — the system arrow beats a wrong picture
        if (cache.containsKey(shape)) return cache.get(shape);

        Cursor created = null;
        if (canCreateCursors()) {
            try {
                created = toCursor(shape.draw.get(), shape.hotspotX, shape.hotspotY);
            } catch (LWJGLException e) {
                created = null;
            }
        }
        cache.put(shape, created);
        return created;
    }

    /**
     * Which CSS keywords we have artwork for. Everything else falls through to the system arrow, which
     * is a better answer than a wrong shape — and that is most of the set, deliberately: {@link CgCursor}
     * ports all of CSS's keywords while only a handful matter to this UI.
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
            // Bare opposed triangles, NOT the horizontal arrow above. A node editor puts a scrubbable
            // number inside a resizable panel, so "this value slides" and "this edge moves" appear pixels
            // apart and have to be distinguishable — see CgCursor.SLIDE_ARROW.
            case SLIDE_ARROW:
                return Shape.SLIDE_ARROW;
            // `pointer` is the most common cursor in any UI — every button, link and menu row asks for it —
            // so it is the one keyword worth artwork beyond the resize set.
            case POINTER: case GRAB:
                return Shape.POINTING_HAND;
            default:
                return null;
        }
    }

    /** Queried once. The capability bit and the size limits cannot change for the life of a display,
     * and the query is not free enough to repeat on every cache miss. */
    private boolean canCreateCursors() {
        if (cursorsAvailable == null) {
            cursorsAvailable = (Cursor.getCapabilities()
                    & Cursor.CURSOR_ONE_BIT_TRANSPARENCY) != 0
                    && CgCursorBitmaps.SIZE >= Cursor.getMinCursorSize()
                    && CgCursorBitmaps.SIZE <= Cursor.getMaxCursorSize();
        }
        return cursorsAvailable;
    }

    /**
     * Converts a top-down ARGB bitmap into an LWJGL2 cursor.
     *
     * <p>The vertical flip and the hotspot conversion are the whole reason this is a separate method:
     * they are one line each and both are silently wrong if omitted.</p>
     */
    private Cursor toCursor(int[] topDown, int hotspotX, int hotspotY) throws LWJGLException {
        final int size = CgCursorBitmaps.SIZE;
        IntBuffer pixels = BufferUtils.createIntBuffer(size * size);
        for (int y = size - 1; y >= 0; y--) {
            for (int x = 0; x < size; x++) {
                pixels.put(topDown[y * size + x]);
            }
        }
        pixels.flip();

        // Y from the bottom, since that is the space the image is now in.
        int flippedHotspotY = size - 1 - hotspotY;
        return new Cursor(size, size, hotspotX, flippedHotspotY, 1, pixels, null);
    }
}
