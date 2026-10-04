package com.crystalgraphics.lwjgl3;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL43;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.opengl.KHRDebug;

import java.util.HashMap;
import java.util.Map;

/**
 * Logs the Java stack of the first few GL errors, so a debug message names the call that caused it; and, asked, the
 * driver's performance messages, so a stall names its cause and the call it lands on.
 *
 * <pre>
 * -Dcrystalgraphics.gl.debugStacks=true          # the first 5 errors
 * -Dcrystalgraphics.gl.debugStacks.limit=20      # more
 * -Dcrystalgraphics.gl.debugPerf=true            # each distinct performance message once, with its stack,
 *                                                # and how often each came, every 120 of them
 * </pre>
 *
 * <p>Diagnosis only: it makes debug output synchronous and replaces the host's own callback for the
 * session. Needs a debug context, which a dev client has; elsewhere the driver may send nothing. The harness asks for
 * one under {@code debugPerf}.</p>
 */
final class GlDebugStacks {

    private static final int LIMIT = Integer.getInteger("crystalgraphics.gl.debugStacks.limit", 5);
    private static final boolean PERF = Boolean.getBoolean("crystalgraphics.gl.debugPerf");

    /** Held: the driver keeps only a native pointer, and a collected callback crashes the next error. */
    private static GLDebugMessageCallback callback;
    private static int reported, perfMessages;
    /** Performance messages by id, and how often each came. */
    private static final Map<Integer, int[]> PERF_SEEN = new HashMap<>();

    private GlDebugStacks() {
    }

    static void installIfAsked() {
        if ((!Boolean.getBoolean("crystalgraphics.gl.debugStacks") && !PERF) || callback != null) return;
        GLCapabilities caps = GL.getCapabilities();
        if (!caps.OpenGL43 && !caps.GL_KHR_debug) {
            System.err.println("[crystalgraphics] gl.debugStacks: this context has no debug output");
            return;
        }
        callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
            if (type == GL43.GL_DEBUG_TYPE_ERROR) {
                if (reported >= LIMIT) return;
                reported++;
                new Throwable("[crystalgraphics] GL error " + id + " (" + reported + " of " + LIMIT + "): "
                        + GLDebugMessageCallback.getMessage(length, message)).printStackTrace();
            } else if (PERF && severity != GL43.GL_DEBUG_SEVERITY_NOTIFICATION) {
                int[] count = PERF_SEEN.computeIfAbsent(id, k -> new int[1]);
                if (count[0]++ == 0) {
                    new Throwable("[crystalgraphics] GL " + typeName(type) + " " + id + ": "
                            + GLDebugMessageCallback.getMessage(length, message)).printStackTrace();
                }
                if (++perfMessages % 120 == 0) {
                    StringBuilder line = new StringBuilder("[crystalgraphics] GL performance messages so far:");
                    PERF_SEEN.forEach((key, n) -> line.append(' ').append(key).append(" x").append(n[0]));
                    System.err.println(line);
                }
            }
        });
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT);
        GL11.glEnable(GL43.GL_DEBUG_OUTPUT_SYNCHRONOUS);
        if (caps.OpenGL43) GL43.glDebugMessageCallback(callback, 0L);
        else KHRDebug.glDebugMessageCallback(callback, 0L);
        System.err.println("[crystalgraphics] gl.debugStacks: logging the stack of the first " + LIMIT + " GL errors"
                + (PERF ? ", and each distinct performance message" : ""));
    }

    private static String typeName(int type) {
        switch (type) {
            case GL43.GL_DEBUG_TYPE_PERFORMANCE: return "performance";
            case GL43.GL_DEBUG_TYPE_DEPRECATED_BEHAVIOR: return "deprecated";
            case GL43.GL_DEBUG_TYPE_UNDEFINED_BEHAVIOR: return "undefined";
            case GL43.GL_DEBUG_TYPE_PORTABILITY: return "portability";
            default: return "message";
        }
    }
}
