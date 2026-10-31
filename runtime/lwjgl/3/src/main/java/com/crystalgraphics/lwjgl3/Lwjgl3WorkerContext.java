package com.crystalgraphics.lwjgl3;

import com.crystalgraphics.platform.gl.CgGLWorkerContext;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

/**
 * A hidden 1x1 GLFW window whose context shares the render thread's objects: Dolphin's shared-context shader
 * compiler, on GLFW. Made by {@link #create()} on the render thread, which is GLFW's main thread on every host that
 * runs this backend.
 */
final class Lwjgl3WorkerContext implements CgGLWorkerContext {

    private final long window;

    private Lwjgl3WorkerContext(long window) {
        this.window = window;
    }

    /** One sharing the current context, at its version and profile; null where there is none or GLFW refuses. */
    static Lwjgl3WorkerContext create() {
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
            window = GLFW.glfwCreateWindow(1, 1, "crystalgraphics-link", 0L, main);
        } finally {
            GLFW.glfwDefaultWindowHints();
        }
        return window == 0L ? null : new Lwjgl3WorkerContext(window);
    }

    @Override
    public void makeCurrent() {
        GLFW.glfwMakeContextCurrent(window);
        GL.createCapabilities();
    }

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
