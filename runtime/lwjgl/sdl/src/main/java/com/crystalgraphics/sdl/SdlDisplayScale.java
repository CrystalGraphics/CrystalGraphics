package com.crystalgraphics.sdl;

import com.crystalgraphics.platform.service.CgDisplayScale;

import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link CgDisplayScale} from SDL3's window display scale: the display's content scale times the window's pixel
 * density, which is what content in its pixels is drawn at.
 *
 * <pre>{@code
 * CgPlatform.provide(CgDisplayScale.SERVICE, new SdlDisplayScale());
 * }</pre>
 *
 * <p>The host's window is SDL's first; it is looked up once, since the list is allocated on each call.</p>
 */
public final class SdlDisplayScale implements CgDisplayScale {

    private long window;

    @Override
    public float scale() {
        if (!InputSdl.videoUp()) return 1f;
        if (window == MemoryUtil.NULL) window = firstWindow();
        if (window == MemoryUtil.NULL) return 1f;
        float scale = SDLVideo.SDL_GetWindowDisplayScale(window);
        if (scale > 0f) return scale;
        window = MemoryUtil.NULL;   // closed or recreated: looked up again next frame
        return 1f;
    }

    private static long firstWindow() {
        PointerBuffer windows = SDLVideo.SDL_GetWindows();
        if (windows == null) return MemoryUtil.NULL;
        try {
            return windows.remaining() > 0 ? windows.get(0) : MemoryUtil.NULL;
        } finally {
            SDLStdinc.SDL_free(windows);
        }
    }
}
