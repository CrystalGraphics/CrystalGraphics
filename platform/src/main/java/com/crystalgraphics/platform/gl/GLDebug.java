package com.crystalgraphics.platform.gl;

import com.crystalgraphics.variable.CgVar;

/**
 * The GL device's diagnostic switches: driver errors and performance messages with the Java stack that made them, and
 * whether a host's own debug output is kept.
 *
 * <pre>
 * -Dcrystalgraphics.gl.debugStacks=true          # the first 5 errors
 * -Dcrystalgraphics.gl.debugStacks.limit=20      # more
 * -Dcrystalgraphics.gl.debugPerf=true            # each distinct performance message once, with its stack
 * -Dcrystalgraphics.gl.keepHostDebugOutput=true  # Minecraft's debug output left on
 * </pre>
 *
 * <ul>
 *   <li>Read once, as the driver is made: set them on the command line.</li>
 *   <li>The stacks need a debug context, which a dev client has; elsewhere the driver may send nothing.</li>
 * </ul>
 */
public final class GLDebug {

    public static final CgVar.Bool DEBUG_STACKS = CgVar.bool("crystalgraphics.gl.debugStacks", false, CgVar.DEV | CgVar.RESTART,
            "Log the Java stack of the first GL errors, so a debug message names the call (LWJGL 3 hosts on a debug"
                    + " context: every dev client has one).");
    public static final CgVar.Int DEBUG_STACKS_LIMIT = CgVar.integer("crystalgraphics.gl.debugStacks.limit", 5, 1, 1 << 20,
            CgVar.DEV | CgVar.RESTART, "GL errors debugStacks logs a stack for.");
    public static final CgVar.Bool DEBUG_PERF = CgVar.bool("crystalgraphics.gl.debugPerf", false, CgVar.DEV | CgVar.RESTART,
            "Each distinct driver performance message once, with the stack of the call it came on, and counts: names a CPU"
                    + " stall (pixel transfer is synchronized). The harness asks for a debug context under it.");
    public static final CgVar.Bool KEEP_HOST_DEBUG_OUTPUT = CgVar.bool("crystalgraphics.gl.keepHostDebugOutput", false,
            CgVar.DEV | CgVar.RESTART, "Keep the GL debug output Minecraft enables at its default glDebugVerbosity; modern"
                    + " GL nodes turn it off at the first host section (on NVIDIA it slowed every call several times)."
                    + " debugStacks and debugPerf keep it too.");

    private GLDebug() {
    }
}
