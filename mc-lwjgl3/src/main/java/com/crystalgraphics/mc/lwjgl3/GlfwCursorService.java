package com.crystalgraphics.mc.lwjgl3;

import com.crystalgraphics.platform.service.CgCursorImage;
import com.crystalgraphics.platform.service.CgCursorService;

import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Presents cursors through GLFW.
 *
 * <pre>{@code
 * // the host supplies the window; nothing else is needed
 * CgPlatform.provide(CgCursorService.SERVICE,
 *         new GlfwCursorService(() -> Minecraft.getInstance().getWindow().getWindow()));
 * }</pre>
 *
 * <p><b>The window arrives as a {@link LongSupplier}, not a handle.</b> GLFW's window is created
 * after this service can be constructed and can be recreated under it, so a captured {@code long}
 * would be stale exactly when it mattered. Asking per call costs a field read.
 *
 * <p><b>It enumerates no cursor keywords.</b> {@link #STANDARD} maps
 * {@link CgCursorImage#name() names} to the shapes <i>GLFW itself ships</i> and nothing more; every
 * other name is drawn from the pixels the caller supplied. That split is the whole seam — the
 * vocabulary lives with whoever owns the UI, and this class only answers "can I draw that natively".
 *
 * <p>One failure disables it for the process. A cursor is cosmetic, and this runs from hover
 * handling on the frame thread, so throwing every time the pointer crosses an element is strictly
 * worse than showing the system arrow.
 */
public final class GlfwCursorService implements CgCursorService {

    /**
     * The shapes GLFW draws itself, keyed by the name the caller gave the picture.
     *
     * <p><b>Only the five that exist in LWJGL 3.2.2</b>, which is what this tier compiles against so
     * it can serve MC 1.13–1.16 as well as 1.20.x. {@code GLFW_RESIZE_NWSE_CURSOR},
     * {@code GLFW_RESIZE_NESW_CURSOR} and {@code GLFW_RESIZE_ALL_CURSOR} arrived in GLFW 3.4 and are
     * deliberately absent: the three names they would serve — {@code diagonal-nwse},
     * {@code diagonal-nesw}, {@code four-way} — all come with the caller's own artwork, so leaving
     * them out costs a system look and nothing else. Hardcoding their numeric values to get that look
     * back was the alternative and is worse: a wrong one lands on a neighbouring valid shape and draws
     * a confidently incorrect cursor, where a missing mapping just draws ours.
     */
    private static final Map<String, Integer> STANDARD = new HashMap<>();
    static {
        STANDARD.put("horizontal-arrow", GLFW.GLFW_HRESIZE_CURSOR);
        STANDARD.put("vertical-arrow",   GLFW.GLFW_VRESIZE_CURSOR);
        STANDARD.put("text-beam",        GLFW.GLFW_IBEAM_CURSOR);
        STANDARD.put("pointing-hand",    GLFW.GLFW_HAND_CURSOR);
        STANDARD.put("crosshair",        GLFW.GLFW_CROSSHAIR_CURSOR);
    }

    private final LongSupplier window;
    private final Map<String, Long> cache = new HashMap<>();
    private boolean supported = true;

    public GlfwCursorService(LongSupplier window) {
        this.window = window;
    }

    @Override
    public void show(CgCursorImage image) {
        if (!supported) return;
        try {
            long handle = window.getAsLong();
            if (handle == MemoryUtil.NULL) return;
            // NULL restores the system arrow -- GLFW's own contract, and a better answer than a
            // wrong picture.
            GLFW.glfwSetCursor(handle, resolve(image));
        } catch (RuntimeException e) {
            supported = false;
        }
    }

    private long resolve(CgCursorImage image) {
        if (image == null) return MemoryUtil.NULL;
        Long cached = cache.get(image.name());
        if (cached != null) return cached;
        long created = create(image);
        cache.put(image.name(), created);
        return created;
    }

    /** GLFW's own cursor if it knows this name, the caller's artwork if not. */
    private static long create(CgCursorImage image) {
        Integer standard = STANDARD.get(image.name());
        if (standard != null) {
            long handle = GLFW.glfwCreateStandardCursor(standard);
            if (handle != MemoryUtil.NULL) return handle;
        }
        return createFromPixels(image);
    }

    /**
     * GLFW wants RGBA bytes in top-down rows, which is the order {@link CgCursorImage} states — so
     * the only work is unpacking each {@code 0xAARRGGBB} int into four bytes.
     */
    private static long createFromPixels(CgCursorImage image) {
        if (!image.hasPixels()) return MemoryUtil.NULL;

        ByteBuffer pixels = BufferUtils.createByteBuffer(image.width() * image.height() * 4);
        for (int pixel : image.argb()) {
            pixels.put((byte) ((pixel >> 16) & 0xFF));   // R
            pixels.put((byte) ((pixel >> 8) & 0xFF));    // G
            pixels.put((byte) (pixel & 0xFF));           // B
            pixels.put((byte) ((pixel >> 24) & 0xFF));   // A
        }
        pixels.flip();

        GLFWImage descriptor = GLFWImage.malloc();
        try {
            descriptor.set(image.width(), image.height(), pixels);
            return GLFW.glfwCreateCursor(descriptor, image.hotspotX(), image.hotspotY());
        } finally {
            // The native cursor owns a copy by now; the descriptor is ours to free.
            descriptor.free();
        }
    }
}
