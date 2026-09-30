package com.crystalgraphics.sdl;

import com.crystalgraphics.platform.service.CgCursorService;

import org.lwjgl.BufferUtils;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDLPixels;
import org.lwjgl.sdl.SDLSurface;
import org.lwjgl.sdl.SDL_Surface;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

/**
 * Presents cursors through SDL3 — {@code GlfwCursorService} for a host SDL windows.
 *
 * <pre>{@code
 * CgPlatform.provide(CgCursorService.SERVICE, new SdlCursorService());
 * }</pre>
 *
 * <p>No window handle: SDL3 shows a cursor on whichever of its windows has the mouse. {@link #STANDARD}
 * maps picture names to the shapes SDL draws itself; every other name is drawn from the caller's pixels.
 * One failure disables it for the process, since a cursor is cosmetic and this runs from hover handling.</p>
 */
public final class SdlCursorService implements CgCursorService {

    /** The shapes SDL draws itself, keyed by the name the caller gave the picture. */
    private static final Map<String, Integer> STANDARD = new HashMap<>();
    static {
        STANDARD.put("horizontal-arrow", SDLMouse.SDL_SYSTEM_CURSOR_EW_RESIZE);
        STANDARD.put("vertical-arrow",   SDLMouse.SDL_SYSTEM_CURSOR_NS_RESIZE);
        STANDARD.put("diagonal-nwse",    SDLMouse.SDL_SYSTEM_CURSOR_NWSE_RESIZE);
        STANDARD.put("diagonal-nesw",    SDLMouse.SDL_SYSTEM_CURSOR_NESW_RESIZE);
        STANDARD.put("four-way",         SDLMouse.SDL_SYSTEM_CURSOR_MOVE);
        STANDARD.put("text-beam",        SDLMouse.SDL_SYSTEM_CURSOR_TEXT);
        STANDARD.put("pointing-hand",    SDLMouse.SDL_SYSTEM_CURSOR_POINTER);
        STANDARD.put("crosshair",        SDLMouse.SDL_SYSTEM_CURSOR_CROSSHAIR);
    }

    private final Map<String, Long> cache = new HashMap<>();
    private boolean supported = true;

    @Override
    public void show(Image image) {
        if (!supported || SDLInit.SDL_WasInit(SDLInit.SDL_INIT_VIDEO) == 0) return;
        try {
            long cursor = resolve(image);
            // SDL_SetCursor(NULL) redraws the CURRENT cursor in SDL3; the default has to be asked for.
            SDLMouse.SDL_SetCursor(cursor != MemoryUtil.NULL ? cursor : SDLMouse.SDL_GetDefaultCursor());
        } catch (RuntimeException e) {
            supported = false;
        }
    }

    private long resolve(Image image) {
        if (image == null) return MemoryUtil.NULL;
        Long cached = cache.get(image.name());
        if (cached != null) return cached;
        long created = create(image);
        cache.put(image.name(), created);
        return created;
    }

    /** SDL's own cursor if it knows this name, the caller's artwork if not. */
    private static long create(Image image) {
        Integer standard = STANDARD.get(image.name());
        if (standard != null) {
            long cursor = SDLMouse.SDL_CreateSystemCursor(standard);
            if (cursor != MemoryUtil.NULL) return cursor;
        }
        return createFromPixels(image);
    }

    /**
     * ARGB8888 is one {@code 0xAARRGGBB} int per pixel in native order, which is what {@link Image}
     * holds, so the pixels go in unconverted. SDL copies the surface into the cursor.
     */
    private static long createFromPixels(Image image) {
        if (!image.hasPixels()) return MemoryUtil.NULL;

        ByteBuffer pixels = BufferUtils.createByteBuffer(image.width() * image.height() * 4)
                .order(ByteOrder.nativeOrder());
        for (int pixel : image.argb()) pixels.putInt(pixel);
        pixels.flip();

        SDL_Surface surface = SDLSurface.SDL_CreateSurfaceFrom(image.width(), image.height(),
                SDLPixels.SDL_PIXELFORMAT_ARGB8888, pixels, image.width() * 4);
        if (surface == null) return MemoryUtil.NULL;
        try {
            return SDLMouse.SDL_CreateColorCursor(surface, image.hotspotX(), image.hotspotY());
        } finally {
            SDLSurface.SDL_DestroySurface(surface);
        }
    }
}
