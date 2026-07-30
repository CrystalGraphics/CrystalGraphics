package com.crystalgraphics.platform.service;

import com.crystalgraphics.platform.input.CgCursor;
import com.crystalgraphics.platform.input.CgCursorBitmaps;

/**
 * Platform abstraction for showing a mouse cursor.
 *
 * <p>Deciding <em>which</em> cursor to show is the caller's job — for CrystalGUI that means the
 * {@code cursor} CSS property, its inheritance, and the {@code auto} context rule. Presenting one is
 * loader-specific to an awkward degree, which is exactly why it is a seam:</p>
 *
 * <ul>
 *   <li><b>LWJGL3 / GLFW</b> (MC 1.20.x) has standard system cursors, including the full resize set.
 *       A mapping table is the whole implementation.</li>
 *   <li><b>LWJGL2</b> (MC 1.7.10, and the harness) has <b>no standard cursors at all</b> —
 *       {@code Mouse.setNativeCursor} takes a {@code Cursor} built from raw pixel data. That platform
 *       needs bitmaps, which {@link CgCursorBitmaps} supplies, or has to draw its own indicator.</li>
 * </ul>
 *
 * <p>An unimplemented cursor is a cosmetic gap, never a functional one, so a platform that cannot show one
 * implements {@link #setCursor} with an empty body — callers keep resolving cursors correctly and simply
 * nothing appears. <b>There is deliberately no {@code NOOP} constant</b> to reach for instead; see
 * {@link com.crystalgraphics.platform.CgPlatformService} for why none of its methods have defaults either.</p>
 *
 * <p><b>Called only on change</b>, not per frame: callers are expected to track the last cursor they asked
 * for and stay quiet while the pointer sits still. Implementations may therefore do real work here
 * (creating a native cursor object) without needing their own caching, though caching by {@link CgCursor}
 * is still wise — a user waving the pointer across a UI will cycle through a handful repeatedly.</p>
 */
@FunctionalInterface
public interface CgCursorService {

    /**
     * Shows {@code cursor}.
     *
     * <p>Never receives {@link CgCursor#AUTO} — callers resolve that to a concrete value first, so an
     * implementation only ever sees something it can map or ignore.</p>
     */
    void setCursor(CgCursor cursor);
}
