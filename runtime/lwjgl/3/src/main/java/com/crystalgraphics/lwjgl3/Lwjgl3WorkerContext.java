package com.crystalgraphics.lwjgl3;

import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;

/**
 * A hidden 1x1 GLFW window with a context of its own, at the render thread's version and sharing none of its objects:
 * the GL device's link worker's. Made by {@link #isolated()} on the render thread, which is GLFW's main thread on every
 * host windowed by GLFW.
 */
final class Lwjgl3WorkerContext implements CgGLWorkerContext {

    private final long window;

    private Lwjgl3WorkerContext(long window) {
        this.window = window;
    }

    /** One at the current context's version and profile, sharing nothing; null where there is none or GLFW refuses. */
    static Lwjgl3WorkerContext isolated() {
        long main = GLFW.glfwGetCurrentContext();
        if (main == 0L) return null;
        // Hints are global: set from the main window's own, and put back to GLFW's defaults after.
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_FOCUSED, GLFW.GLFW_FALSE);
        GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_CLIENT_API));
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MAJOR, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_CONTEXT_VERSION_MAJOR));
        GLFW.glfwWindowHint(GLFW.GLFW_CONTEXT_VERSION_MINOR, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_CONTEXT_VERSION_MINOR));
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_PROFILE, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_OPENGL_PROFILE));
        GLFW.glfwWindowHint(GLFW.GLFW_OPENGL_FORWARD_COMPAT, GLFW.glfwGetWindowAttrib(main, GLFW.GLFW_OPENGL_FORWARD_COMPAT));
        long window;
        try {
            window = GLFW.glfwCreateWindow(1, 1, "crystalgraphics-link", 0L, 0L);
        } finally {
            GLFW.glfwDefaultWindowHints();
        }
        return window == 0L ? null : new Lwjgl3WorkerContext(window);
    }

    @Override
    public void makeCurrent() {
        GLFW.glfwMakeContextCurrent(window);
        // Made once: a worker switching between two contexts would build them at every switch
        if (capabilities == null) capabilities = GL.createCapabilities();
        else GL.setCapabilities(capabilities);
    }

    private GLCapabilities capabilities;

    @Override
    public void release() {
        GLFW.glfwMakeContextCurrent(0L);
        GL.setCapabilities(null);
    }

    @Override
    public void destroy() {
        GLFW.glfwDestroyWindow(window);
    }
}
