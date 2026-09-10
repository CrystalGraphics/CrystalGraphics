package com.crystalgraphics.mc.lwjgl2;

import com.crystalgraphics.platform.service.CgCursorService;

import org.lwjgl.BufferUtils;
import org.lwjgl.LWJGLException;
import org.lwjgl.input.Cursor;
import org.lwjgl.input.Mouse;

import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Presents cursors through LWJGL 2.
 *
 * <pre>{@code
 * CgPlatform.provide(CgCursorService.SERVICE, new Lwjgl2CursorService());
 * }</pre>
 *
 * <p>Needs no window handle: LWJGL 2's {@code Mouse} is bound to the single display it owns.
 *
 * <p><b>It ships no shapes of its own</b>, unlike the GLFW adapter — LWJGL 2 has no standard-cursor
 * API, so every picture is drawn from the pixels the caller supplied and a picture with none gets
 * the system arrow.
 *
 * <p>Two things this has to get right and the GLFW side does not:
 * <ul>
 *   <li><b>Rows go bottom-up.</b> {@link Image} states top-down, which is what every other
 *       toolkit wants; LWJGL 2 wants the opposite, so the image is flipped here and the hotspot's Y
 *       with it. Getting only one of the two right leaves a cursor that looks correct and clicks in
 *       the wrong place.</li>
 *   <li><b>The driver may refuse the size or the transparency.</b> Asked once and cached, because
 *       {@code Cursor.getCapabilities} is a native call and this runs from hover handling.</li>
 * </ul>
 *
 * <p>One failure disables it for the process, for the same reason as the GLFW adapter: a cursor is
 * cosmetic and the frame thread should not carry an exception for it.
 */
public final class Lwjgl2CursorService implements CgCursorService {

    private final Map<String, Cursor> cache = new HashMap<>();
    private boolean supported = true;
    private Boolean cursorsAvailable;

    @Override
    public void show(Image image) {
        if (!supported) return;
        try {
            Mouse.setNativeCursor(resolve(image));
        } catch (LWJGLException | RuntimeException e) {
            supported = false;
        }
    }

    private Cursor resolve(Image image) {
        // No picture, or one only a native could present: the system arrow beats a wrong shape.
        if (image == null || !image.hasPixels()) return null;
        if (cache.containsKey(image.name())) return cache.get(image.name());

        Cursor created = null;
        if (canCreateCursors(image)) {
            try {
                created = toCursor(image);
            } catch (LWJGLException e) {
                created = null;
            }
        }
        cache.put(image.name(), created);
        return created;
    }

    private boolean canCreateCursors(Image image) {
        if (cursorsAvailable == null) {
            cursorsAvailable = (Cursor.getCapabilities()
                    & Cursor.CURSOR_ONE_BIT_TRANSPARENCY) != 0;
        }
        return cursorsAvailable
                && image.width() >= Cursor.getMinCursorSize()
                && image.width() <= Cursor.getMaxCursorSize();
    }

    private static Cursor toCursor(Image image) throws LWJGLException {
        final int w = image.width();
        final int h = image.height();
        int[] topDown = image.argb();

        IntBuffer pixels = BufferUtils.createIntBuffer(w * h);
        for (int y = h - 1; y >= 0; y--) {
            for (int x = 0; x < w; x++) {
                pixels.put(topDown[y * w + x]);
            }
        }
        pixels.flip();

        // Y from the bottom, since that is the space the image is now in.
        int flippedHotspotY = h - 1 - image.hotspotY();
        return new Cursor(w, h, image.hotspotX(), flippedHotspotY, 1, pixels, null);
    }
}
