package com.crystalgraphics.lwjgl3;

import com.crystalgraphics.platform.service.CgDisplayScale;

import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;

import java.nio.FloatBuffer;
import java.util.function.LongSupplier;

/**
 * {@link CgDisplayScale} from GLFW's window content scale.
 *
 * <pre>{@code
 * CgPlatform.provide(CgDisplayScale.SERVICE, new GlfwDisplayScale(PlatformServiceModern::windowHandle));
 * }</pre>
 *
 * <p>The window is a supplier: it is not open when the service is built. Before GLFW 3.3 (the LWJGL of Minecraft
 * 1.13 and 1.14) there is no content scale, and this answers 1 from the first call on.</p>
 */
public final class GlfwDisplayScale implements CgDisplayScale {

    private final LongSupplier window;
    private boolean unsupported;

    public GlfwDisplayScale(LongSupplier window) {
        this.window = window;
    }

    @Override
    public float scale() {
        if (unsupported) return 1f;
        long handle = window.getAsLong();
        if (handle == 0L) return 1f;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer x = stack.mallocFloat(1);
            FloatBuffer y = stack.mallocFloat(1);
            GLFW.glfwGetWindowContentScale(handle, x, y);
            float scale = x.get(0);
            return scale > 0f && !Float.isNaN(scale) ? scale : 1f;
        } catch (LinkageError olderGlfw) {
            unsupported = true;
            return 1f;
        }
    }
}
