package io.github.somehussar.crystalgraphics.gl.state;

/**
 * Pure-Java, thread-local mirror of selected OpenGL state.
 *
 * <p>This class is the heart of CrystalGraphics' state capture system.  It
 * tracks framebuffer bindings, shader program bindings, active texture units,
 * and bound 2D textures <em>without ever issuing an OpenGL call</em>.  All
 * state is updated exclusively through the {@code on*} notification methods,
 * which are invoked by the bytecode transformer's redirect targets.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>All tracked state is stored in {@code static} fields.  Because OpenGL
 * contexts are thread-local and Minecraft's render thread is the only thread
 * issuing GL calls, this is safe in practice.  The {@link #redirectDepth}
 * recursion guard uses a {@link ThreadLocal} depth counter to remain correct
 * even with nested redirects.</p>
 *
 * <h3>Design Constraints</h3>
 * <ul>
 *   <li><strong>NO GL CALLS</strong> &mdash; this class must never import or
 *       invoke any LWJGL or OpenGL method.  It operates on pure Java integers
 *       and enums only.</li>
 *   <li><strong>No allocations on hot paths</strong> &mdash; none of the
 *       {@code on*} or getter methods allocate objects.</li>
 *   <li><strong>Java 8 compatible</strong> &mdash; no {@code var}, no modules,
 *       no post-Java-8 language features.</li>
 * </ul>
 *
 * @see CallFamily
 */
public final class GLStateMirror {

    /** GL constant for {@code GL_FRAMEBUFFER} (used to update both draw and read targets). */
    private static final int GL_FRAMEBUFFER = 0x8D40;

    /** GL constant for {@code GL_DRAW_FRAMEBUFFER} (updates draw target only). */
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;

    /** GL constant for {@code GL_READ_FRAMEBUFFER} (updates read target only). */
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;

    /** GL constant for {@code GL_TEXTURE_2D}. Only this target is tracked for texture binds. */
    private static final int GL_TEXTURE_2D = 0x0DE1;

    /** GL constant for {@code GL_TEXTURE0}. Subtracted from active-texture values to derive the unit index. */
    private static final int GL_TEXTURE0 = 0x84C0;

    /** Maximum number of texture units tracked. Units beyond this index are silently ignored. */
    private static final int MAX_TEXTURE_UNITS = 32;

    /** The currently bound draw framebuffer object ID. 0 means the default (window) framebuffer. */
    private static int drawFboId;

    /** The currently bound read framebuffer object ID. 0 means the default (window) framebuffer. */
    private static int readFboId;

    /** The GL call family that produced the current framebuffer binding. */
    private static CallFamily currentFboFamily = CallFamily.UNKNOWN;

    /** The currently bound shader program ID. 0 means no program is active. */
    private static int programId;

    /** The GL call family that produced the current shader program binding. */
    private static CallFamily currentProgramFamily = CallFamily.UNKNOWN;

    /** The currently active texture unit index (0 = {@code GL_TEXTURE0}). */
    private static int activeTextureUnit;

    /**
     * Per-unit tracking of bound 2D textures.  Index {@code i} holds the
     * texture ID bound to {@code GL_TEXTURE0 + i}.  Untracked or unbound
     * units have value 0.
     */
    private static final int[] boundTexture2D = new int[MAX_TEXTURE_UNITS];

    /**
     * Thread-local recursion depth counter.
     *
     * <p>This is a depth counter (not a boolean) to correctly handle nested
     * redirects. Redirect targets may call into Minecraft wrappers
     * ({@code OpenGlHelper}) which in turn call redirected LWJGL methods.
     * A boolean guard would be cleared too early by the inner call, allowing
     * re-entry while still inside the outer redirect.</p>
     */
    private static final ThreadLocal<int[]> redirectDepth = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[] { 0 };
        }
    };

    /**
     * Private constructor to prevent instantiation.  All access is through
     * static methods.
     */
    private GLStateMirror() {
    }

    /**
     * Called when a framebuffer bind is intercepted.
     *
     * <p>{@code GL_FRAMEBUFFER} updates both draw and read targets.
     * {@code GL_DRAW_FRAMEBUFFER} updates draw only.
     * {@code GL_READ_FRAMEBUFFER} updates read only.</p>
     *
     * @param target GL target constant ({@code GL_FRAMEBUFFER} = 0x8D40,
     *               {@code GL_DRAW_FRAMEBUFFER} = 0x8CA9,
     *               {@code GL_READ_FRAMEBUFFER} = 0x8CA8)
     * @param id     the framebuffer object ID (0 = default framebuffer)
     * @param family the GL call family used for this bind
     */
    public static void onBindFramebuffer(int target, int id, CallFamily family) {
        if (target == GL_FRAMEBUFFER) {
            drawFboId = id;
            readFboId = id;
        } else if (target == GL_DRAW_FRAMEBUFFER) {
            drawFboId = id;
        } else if (target == GL_READ_FRAMEBUFFER) {
            readFboId = id;
        }
        currentFboFamily = family;
    }

    /**
     * Called when a shader program bind is intercepted.
     *
     * @param id     the program ID (0 = unbind / fixed-function)
     * @param family the GL call family used for this bind
     */
    public static void onUseProgram(int id, CallFamily family) {
        programId = id;
        currentProgramFamily = family;
    }

    /**
     * Called when the active texture unit is changed.
     *
     * <p>The incoming value is a GL constant such as {@code GL_TEXTURE0}
     * (0x84C0).  This method converts it to a zero-based index by subtracting
     * {@code GL_TEXTURE0}.</p>
     *
     * @param unit {@code GL_TEXTURE0}-based constant (e.g., 0x84C0 for unit 0,
     *             0x84C1 for unit 1, etc.)
     */
    public static void onActiveTexture(int unit) {
        activeTextureUnit = unit - GL_TEXTURE0;
    }

    /**
     * Called when a 2D texture is bound.
     *
     * <p>Only {@code GL_TEXTURE_2D} binds are tracked.  Other targets
     * (cube maps, 3D textures, etc.) are silently ignored.  If the current
     * active texture unit is outside the tracked range (0..31), the bind is
     * also ignored.</p>
     *
     * @param target the GL texture target (only {@code GL_TEXTURE_2D} = 0x0DE1
     *               is tracked)
     * @param id     the texture ID
     */
    public static void onBindTexture(int target, int id) {
        if (target == GL_TEXTURE_2D && activeTextureUnit >= 0 && activeTextureUnit < MAX_TEXTURE_UNITS) {
            boundTexture2D[activeTextureUnit] = id;
        }
    }

    /**
     * Returns the currently tracked draw framebuffer ID.
     *
     * @return the draw framebuffer object ID, or 0 for the default framebuffer
     */
    public static int getDrawFboId() {
        return drawFboId;
    }

    /**
     * Returns the currently tracked read framebuffer ID.
     *
     * @return the read framebuffer object ID, or 0 for the default framebuffer
     */
    public static int getReadFboId() {
        return readFboId;
    }

    /**
     * Returns the GL call family used to bind the current framebuffer.
     *
     * @return the {@link CallFamily} that produced the current FBO binding,
     *         or {@link CallFamily#UNKNOWN} if no bind has been observed
     */
    public static CallFamily getCurrentFboFamily() {
        return currentFboFamily;
    }

    /**
     * Returns the currently tracked shader program ID.
     *
     * @return the program ID, or 0 if no program is bound
     */
    public static int getProgramId() {
        return programId;
    }

    /**
     * Returns the GL call family used to bind the current shader program.
     *
     * @return the {@link CallFamily} that produced the current program binding,
     *         or {@link CallFamily#UNKNOWN} if no bind has been observed
     */
    public static CallFamily getCurrentProgramFamily() {
        return currentProgramFamily;
    }

    /**
     * Returns the currently active texture unit index.
     *
     * @return the active texture unit index (0 = {@code GL_TEXTURE0})
     */
    public static int getActiveTextureUnit() {
        return activeTextureUnit;
    }

    /**
     * Returns the bound 2D texture ID for the specified texture unit.
     *
     * @param unit the unit index (0..31)
     * @return the bound 2D texture ID for that unit, or 0 if the unit is
     *         untracked or out of range
     */
    public static int getBoundTexture2D(int unit) {
        if (unit < 0 || unit >= MAX_TEXTURE_UNITS) {
            return 0;
        }
        return boundTexture2D[unit];
    }

    /**
     * Marks all tracked state as unknown / default.
     *
     * <p>Called when CrystalGraphics cannot trust its mirror &mdash; for example,
     * when external code performs GL calls outside the interception layer.
     * After this call, all IDs are reset to 0, all families to
     * {@link CallFamily#UNKNOWN}, and all texture bindings to 0.</p>
     */
    public static void markUnknown() {
        drawFboId = 0;
        readFboId = 0;
        currentFboFamily = CallFamily.UNKNOWN;
        programId = 0;
        currentProgramFamily = CallFamily.UNKNOWN;
        activeTextureUnit = 0;
        for (int i = 0; i < MAX_TEXTURE_UNITS; i++) {
            boundTexture2D[i] = 0;
        }
    }

    /**
     * Returns whether all tracked state is in a known (non-{@link CallFamily#UNKNOWN}) state.
     *
     * <p>A return value of {@code true} indicates that both the framebuffer
     * family and the program family have been set by at least one intercepted
     * call since the last {@link #markUnknown()} or initialization.</p>
     *
     * @return {@code true} if all tracked state is known (no
     *         {@link CallFamily#UNKNOWN} families), {@code false} otherwise
     */
    public static boolean isFullyKnown() {
        return currentFboFamily != CallFamily.UNKNOWN && currentProgramFamily != CallFamily.UNKNOWN;
    }

    /**
     * Returns whether the current thread is inside a redirect call.
     *
     * <p>When {@code true}, intercepted GL calls should be passed through
     * to the original implementation without re-entering the state tracking
     * layer, to prevent infinite recursion.</p>
     *
     * @return {@code true} if the recursion guard is active on the current
     *         thread, {@code false} otherwise
     */
    public static boolean isInRedirect() {
        return redirectDepth.get()[0] > 0;
    }

    /**
     * Enters the redirect context, activating the recursion guard for the
     * current thread.
     *
     * <p>Must be paired with {@link #exitRedirect()} in a {@code finally}
     * block to ensure the guard is always released:</p>
     * <pre>{@code
     * GLStateMirror.enterRedirect();
     * try {
     *     // perform GL call that might trigger another intercept
     * } finally {
     *     GLStateMirror.exitRedirect();
     * }
     * }</pre>
     */
    public static void enterRedirect() {
        redirectDepth.get()[0]++;
    }

    /**
     * Exits the redirect context, deactivating the recursion guard for the
     * current thread.
     *
     * <p>Must always be called in a {@code finally} block after a
     * corresponding {@link #enterRedirect()} call.</p>
     */
    public static void exitRedirect() {
        int[] depth = redirectDepth.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }
}
