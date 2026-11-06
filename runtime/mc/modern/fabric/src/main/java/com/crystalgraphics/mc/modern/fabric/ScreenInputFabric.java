package com.crystalgraphics.mc.modern.fabric;

import com.crystalgraphics.mc.modern.platform.ScreenInputModern;
import com.crystalgraphics.mc.modern.platform.Windows;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
// 26.3 ships SDL3 and no GLFW.
//? if >=26.3 {
/*import org.lwjgl.PointerBuffer;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLMouse;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_EventFilter;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
*///?} else {
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWCharModsCallback;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWMouseButtonCallback;
import org.lwjgl.glfw.GLFWScrollCallback;
//?}

/**
 * The screen's input on Fabric, fired on {@code CgInputStage.SCREEN} through {@link ScreenInputModern}. Fabric API has
 * no character event and no input event outside a screen, so this chains the window's own callbacks at
 * {@code CLIENT_STARTED}; not forwarding to the previous callback is the cancellation.
 *
 * <p>Characters go on {@code glfwSetCharModsCallback}, not {@code glfwSetCharCallback}: Minecraft installs its handler on
 * the mods variant and GLFW fires both for one keystroke, so hooking the plain one sits beside Minecraft's handler and
 * declining to forward suppresses nothing.</p>
 *
 * <p>From 26.3 the same chain is SDL's event filter: returning false drops an event before Minecraft polls it. Keys
 * arrive as scancodes, buttons count from 1 and characters are {@code SDL_EVENT_TEXT_INPUT}, sent only while text input
 * is started. A filter can run for an event pushed from another thread; it acts only on the render thread's.</p>
 */
final class ScreenInputFabric {

    private ScreenInputFabric() {}

    static void register() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> install(Windows.handle(client)));
    }

    //? if >=26.3 {
    /*// Held: SDL keeps only the native pointer, and a collected callback is a crash.
    private static SDL_EventFilter filter;
    private static Thread renderThread;

    private static void install(long window) {
        renderThread = Thread.currentThread();
        SDL_EventFilter previous;
        long previousData;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer function = stack.mallocPointer(1);
            PointerBuffer data = stack.mallocPointer(1);
            boolean had = SDLEvents.SDL_GetEventFilter(function, data) && function.get(0) != MemoryUtil.NULL;
            previous = had ? SDL_EventFilter.createSafe(function.get(0)) : null;
            previousData = had ? data.get(0) : MemoryUtil.NULL;
        }
        filter = SDL_EventFilter.create((userdata, event) -> {
            if (Thread.currentThread() == renderThread && consumed(SDL_Event.create(event))) return false;
            return previous == null || previous.invoke(previousData, event);
        });
        SDLEvents.SDL_SetEventFilter(filter, MemoryUtil.NULL);
    }

    private static boolean consumed(SDL_Event event) {
        switch (event.type()) {
            case SDLEvents.SDL_EVENT_MOUSE_BUTTON_DOWN:
            case SDLEvents.SDL_EVENT_MOUSE_BUTTON_UP:
                return ScreenInputModern.mouseButton(event.button().button() & 0xFF, event.button().down());
            case SDLEvents.SDL_EVENT_KEY_DOWN:
            case SDLEvents.SDL_EVENT_KEY_UP:
                return !event.key().repeat() && ScreenInputModern.key(event.key().scancode(), event.key().down());
            case SDLEvents.SDL_EVENT_TEXT_INPUT: {
                String text = event.text().textString();
                boolean taken = false;
                for (int i = 0; text != null && i < text.length(); i++) {
                    taken |= ScreenInputModern.character(text.charAt(i));
                }
                return taken;
            }
            case SDLEvents.SDL_EVENT_MOUSE_WHEEL: {
                float y = event.wheel().y();
                if (event.wheel().direction() == SDLMouse.SDL_MOUSEWHEEL_FLIPPED) y = -y;
                return ScreenInputModern.scrolled(y);
            }
            default:
                return false;
        }
    }
    *///?} else {
    private static void install(long window) {
        // GLFW hands the previous callback to the setter and offers no getter: read it back by setting null.
        GLFWMouseButtonCallback prevButton = GLFW.glfwSetMouseButtonCallback(window, null);
        GLFW.glfwSetMouseButtonCallback(window, (win, button, action, mods) -> {
            if (action != GLFW.GLFW_REPEAT && ScreenInputModern.mouseButton(button, action == GLFW.GLFW_PRESS)) return;
            if (prevButton != null) prevButton.invoke(win, button, action, mods);
        });

        GLFWKeyCallback prevKey = GLFW.glfwSetKeyCallback(window, null);
        GLFW.glfwSetKeyCallback(window, (win, key, scancode, action, mods) -> {
            if (action != GLFW.GLFW_REPEAT && ScreenInputModern.key(key, action == GLFW.GLFW_PRESS)) return;
            if (prevKey != null) prevKey.invoke(win, key, scancode, action, mods);
        });

        GLFWCharModsCallback prevChar = GLFW.glfwSetCharModsCallback(window, null);
        GLFW.glfwSetCharModsCallback(window, (win, codepoint, mods) -> {
            if (ScreenInputModern.character(codepoint)) return;
            if (prevChar != null) prevChar.invoke(win, codepoint, mods);
        });

        GLFWScrollCallback prevScroll = GLFW.glfwSetScrollCallback(window, null);
        GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> {
            if (ScreenInputModern.scrolled(dy)) return;
            if (prevScroll != null) prevScroll.invoke(win, dx, dy);
        });
    }
    //?}
}
