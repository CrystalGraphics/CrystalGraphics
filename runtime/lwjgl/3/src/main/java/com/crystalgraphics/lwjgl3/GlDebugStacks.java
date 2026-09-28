package com.crystalgraphics.lwjgl3;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.KHRDebug;

/**
 * Logs the Java stack of the first few GL errors, so a debug message names the call that caused it.
 *
 * <pre>
 * -Dcrystalgraphics.gl.debugStacks=true          # the first 5 errors
 * -Dcrystalgraphics.gl.debugStacks.limit=20      # more
 * </pre>
 *
 * <p>Diagnosis only: it makes debug output synchronous and replaces the host's own callback for the
 * session. Needs a debug context, which a dev client has; elsewhere the driver may send nothing.</p>
 */
final class GlDebugStacks {

    private static final int LIMIT = Integer.getInteger("crystalgraphics.gl.debugStacks.limit", 5);

    /** Held: the driver keeps only a native pointer, and a collected callback crashes the next error. */
    private static GLDebugMessageCallback callback;
    private static int reported;

    private GlDebugStacks() {
    }

    static void installIfAsked() {
        if (!Boolean.getBoolean("crystalgraphics.gl.debugStacks") || callback != null) return;
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.OpenGL43 && !caps.GL_KHR_debug) {
            System.err.println("[crystalgraphics] gl.debugStacks: this context has no debug output");
            return;
        }
        callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
            if (type != GL43.GL_DEBUG_TYPE_ERROR || reported >= LIMIT) return;
            reported++;
            new Throwable("[crystalgraphics] GL error " + id + " (" + reported + " of " + LIMIT + "): "
                    + GLDebugMessageCallback.getMessage(length, message)).printStackTrace();
        });
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT);
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        if (caps.OpenGL43) GL43.glDebugMessageCallback(callback, 0L);
        else KHRDebug.glDebugMessageCallback(callback, 0L);
        System.err.println("[crystalgraphics] gl.debugStacks: logging the stack of the first " + LIMIT + " GL errors");
    }
}
