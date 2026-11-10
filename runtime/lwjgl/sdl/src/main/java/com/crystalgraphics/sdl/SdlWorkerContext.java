package com.crystalgraphics.sdl;

import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;

import java.nio.IntBuffer;

import static org.lwjgl.sdl.SDLVideo.SDL_CreateWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_DestroyWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_FLAGS;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_MAJOR_VERSION;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_MINOR_VERSION;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CONTEXT_PROFILE_MASK;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_CreateContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_DestroyContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetAttribute;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentWindow;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_MakeCurrent;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_SHARE_WITH_CURRENT_CONTEXT;
import static org.lwjgl.sdl.SDLVideo.SDL_GL_SetAttribute;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_HIDDEN;
import static org.lwjgl.sdl.SDLVideo.SDL_WINDOW_OPENGL;

/**
 * A hidden 1x1 SDL3 window with a GL context of its own, at the render thread's version and sharing none of its
 * objects: the GL device's link worker's where the host is windowed by SDL3 (Minecraft 26.3 on OpenGL), the GLFW one's
 * twin.
 *
 * <pre>{@code
 * CgGLDriver driver = new Lwjgl3GLDriver(SdlWorkerContext::isolated);
 * }</pre>
 *
 * <ul>
 *   <li>{@link #isolated()} runs on the render thread with its context current, and leaves it current.</li>
 *   <li>Null where no context is current or SDL refuses: the device links on the render thread.</li>
 * </ul>
 */
public final class SdlWorkerContext implements CgGLWorkerContext {

    private final long window, context;

    private SdlWorkerContext(long window, long context) {
        this.window = window;
        this.context = context;
    }

    /** One at the current context's version, profile and flags, sharing nothing; null where there is none or SDL refuses. */
    public static SdlWorkerContext isolated() {
        long mainWindow = SDL_GL_GetCurrentWindow(), mainContext = SDL_GL_GetCurrentContext();
        if (mainWindow == 0L || mainContext == 0L) return null;
        int[] attributes = {SDL_GL_CONTEXT_MAJOR_VERSION, SDL_GL_CONTEXT_MINOR_VERSION, SDL_GL_CONTEXT_PROFILE_MASK,
                SDL_GL_CONTEXT_FLAGS, SDL_GL_SHARE_WITH_CURRENT_CONTEXT};
        int[] before = new int[attributes.length];
        long window = 0L, context = 0L;
        // Attributes are global and are read at the next context's creation: the current context's own, and put back after.
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer value = stack.mallocInt(1);
            for (int i = 0; i < attributes.length; i++) {
                before[i] = SDL_GL_GetAttribute(attributes[i], value) ? value.get(0) : 0;
            }
            SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 0);
            window = SDL_CreateWindow("crystalgraphics-link", 1, 1, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
            if (window != 0L) context = SDL_GL_CreateContext(window);
        } finally {
            SDL_GL_MakeCurrent(mainWindow, mainContext);
            for (int i = 0; i < attributes.length; i++) SDL_GL_SetAttribute(attributes[i], before[i]);
        }
        if (context == 0L) {
            if (window != 0L) SDL_DestroyWindow(window);
            return null;
        }
        return new SdlWorkerContext(window, context);
    }

    @Override
    public void makeCurrent() {
        SDL_GL_MakeCurrent(window, context);
        GL.createCapabilities();
    }

    @Override
    public void release() {
        SDL_GL_MakeCurrent(window, 0L);
        GL.setCapabilities(null);
    }

    @Override
    public void destroy() {
        SDL_GL_DestroyContext(context);
        SDL_DestroyWindow(window);
    }
}
