package com.crystalgraphics.sdl;

import com.crystalgraphics.platform.input.CgClipboard;

import org.lwjgl.sdl.SDLClipboard;
import org.lwjgl.sdl.SDLStdinc;
import org.lwjgl.system.MemoryUtil;

/**
 * {@link CgClipboard} over SDL3. Empty, and a write dropped, until the host starts SDL's video.
 *
 * <pre>{@code
 * CgPlatform.provide(CgClipboard.HOST, ClipboardSdl.INSTANCE);
 * }</pre>
 */
public final class ClipboardSdl implements CgClipboard.Host {

    public static final ClipboardSdl INSTANCE = new ClipboardSdl();

    private ClipboardSdl() {
    }

    @Override
    public String get() {
        if (!InputSdl.videoUp()) return "";
        long text = SDLClipboard.nSDL_GetClipboardText();   // SDL's copy, which the caller frees
        if (text == MemoryUtil.NULL) return "";
        try {
            return MemoryUtil.memUTF8(text);
        } finally {
            SDLStdinc.nSDL_free(text);
        }
    }

    @Override
    public void set(String text) {
        if (text != null && InputSdl.videoUp()) SDLClipboard.SDL_SetClipboardText(text);
    }
}
