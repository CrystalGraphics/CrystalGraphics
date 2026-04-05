package io.github.somehussar.crystalgraphics.gl.state;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.ARBMultitexture;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

/**
 * Save/restore boundary for protecting GL state around CrystalGraphics operations.
 *
 * <p>When CrystalGraphics needs to perform its own FBO or shader operations
 * (e.g., post-processing passes), it must not disturb the GL state that other
 * mods or Minecraft itself have established.  This class provides a
 * {@link #save()}/{@link #restore(CgStateSnapshot)} pair that captures the
 * current state before CrystalGraphics' work and restores it afterward.</p>
 *
 * <h3>Call Family Correctness</h3>
 * <p>The restore operation uses the <em>call family</em> recorded in the
 * snapshot to issue the correct GL calls.  For example, if the snapshot records
 * that the previous framebuffer was bound via ARB, the restore will unbind via
 * ARB and rebind via ARB &mdash; not via Core GL30.  This prevents cross-family
 * driver issues on hardware where different extension paths have subtly
 * different behavior.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * CgStateSnapshot saved = CgStateBoundary.save();
 * try {
 *     // CrystalGraphics GL operations here
 * } finally {
 *     CgStateBoundary.restore(saved);
 * }
 * }</pre>
 *
 * <h3>Thread Safety</h3>
 * <p>This class issues GL calls and must only be used on the render thread
 * that owns the current GL context.</p>
 *
 * @see CgStateSnapshot
 * @see GLStateMirror
 */
public final class CgStateBoundary {

    /** GL constant for {@code GL_FRAMEBUFFER} (draw + read combined target). */
    private static final int GL_FRAMEBUFFER = 0x8D40;

    /** GL constant for {@code GL_DRAW_FRAMEBUFFER} (draw-only binding target). */
    private static final int GL_DRAW_FRAMEBUFFER = 0x8CA9;

    /** GL constant for {@code GL_READ_FRAMEBUFFER} (read-only binding target). */
    private static final int GL_READ_FRAMEBUFFER = 0x8CA8;

    /** GL constant for {@code GL_TEXTURE0}, used to compute texture unit constants. */
    private static final int GL_TEXTURE0 = 0x84C0;

    /**
     * Private constructor to prevent instantiation.  All methods are static.
     */
    private CgStateBoundary() {
    }

    /**
     * Captures the current GL state as a snapshot.
     *
     * <p>Call this before performing any CrystalGraphics GL operations.
     * The returned snapshot can later be passed to {@link #restore(CgStateSnapshot)}
     * to return GL state to what it was at this point.</p>
     *
     * <p>This method does not issue any GL calls; it reads entirely from
     * the {@link GLStateMirror}.</p>
     *
     * @return a snapshot of the current state, suitable for passing to
     *         {@link #restore(CgStateSnapshot)}
     */
    public static CgStateSnapshot save() {
        boolean forceGlGet = Boolean.getBoolean("crystalgraphics.boundary.forceGlGet");
        boolean gapOnlyMode = isGapOnlyModeSafe();

        boolean mirrorOkForFbo = GLStateMirror.getCurrentFboFamily() != CallFamily.UNKNOWN;
        boolean mirrorOkForProgram = GLStateMirror.getCurrentProgramFamily() != CallFamily.UNKNOWN
                || GLStateMirror.getProgramId() == 0;

        if (forceGlGet || gapOnlyMode || !mirrorOkForFbo || !mirrorOkForProgram) {
            return captureUsingGlGet();
        }

        return CgStateSnapshot.captureFromMirror();
    }

    /**
     * Returns {@code true} when the Forge coremod reports gap-only mode.
     *
     * <p>The standalone harness does not include the Forge coremod classes on
     * its classpath, so this method must degrade safely to {@code false} when
     * that class is unavailable.</p>
     */
    private static boolean isGapOnlyModeSafe() {
        try {
            Class<?> coremod = Class.forName(
                    "io.github.somehussar.crystalgraphics.mc.coremod.CrystalGraphicsCoremod");
            Object value = coremod.getMethod("isGapOnlyMode").invoke(null);
            return value instanceof Boolean && ((Boolean) value).booleanValue();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Captures the current GL state using {@code glGet*} queries.
     *
     * <p>This is used when the {@link GLStateMirror} cannot be trusted (for
     * example, in Angelica gap-only mode where Angelica-owned bindings are not
     * observed by CrystalGraphics' redirector).</p>
     *
     * <p>This method temporarily changes the active texture unit while reading
     * per-unit texture bindings, then restores it. Texture-unit switching is
     * wrapped in the redirect recursion guard to avoid perturbing the mirror.</p>
     *
     * @return a best-effort snapshot of current GL state
     */
    private static CgStateSnapshot captureUsingGlGet() {
        ContextCapabilities caps = GLContext.getCapabilities();

        int drawFbo;
        int readFbo;
        if (caps.OpenGL30 || caps.GL_ARB_framebuffer_object) {
            drawFbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            readFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        } else if (caps.GL_EXT_framebuffer_object) {
            int bound = GL11.glGetInteger(EXTFramebufferObject.GL_FRAMEBUFFER_BINDING_EXT);
            drawFbo = bound;
            readFbo = bound;
        } else {
            drawFbo = 0;
            readFbo = 0;
        }

        CallFamily fboFamily;
        if (caps.OpenGL30) {
            fboFamily = CallFamily.CORE_GL30;
        } else if (caps.GL_ARB_framebuffer_object) {
            fboFamily = CallFamily.ARB_FBO;
        } else if (caps.GL_EXT_framebuffer_object) {
            fboFamily = CallFamily.EXT_FBO;
        } else {
            fboFamily = CallFamily.OPENGLHELPER_WRAPPER;
        }

        int programId;
        CallFamily programFamily;
        if (caps.OpenGL20) {
            programId = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            programFamily = CallFamily.CORE_GL20;
        } else if (caps.GL_ARB_shader_objects) {
            programId = ARBShaderObjects.glGetHandleARB(ARBShaderObjects.GL_PROGRAM_OBJECT_ARB);
            programFamily = CallFamily.ARB_SHADER_OBJECTS;
        } else {
            programId = 0;
            programFamily = CallFamily.UNKNOWN;
        }

        int activeTextureEnum;
        if (caps.OpenGL13) {
            activeTextureEnum = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        } else if (caps.GL_ARB_multitexture) {
            activeTextureEnum = GL11.glGetInteger(ARBMultitexture.GL_ACTIVE_TEXTURE_ARB);
        } else {
            activeTextureEnum = GL_TEXTURE0;
        }

        int activeUnitIndex = activeTextureEnum - GL_TEXTURE0;
        if (activeUnitIndex < 0) {
            activeUnitIndex = 0;
        }

        int[] textures2d = new int[32];
        if (caps.OpenGL13 || caps.GL_ARB_multitexture) {
            GLStateMirror.enterRedirect();
            try {
                for (int i = 0; i < textures2d.length; i++) {
                    setActiveTextureUnit(i);
                    textures2d[i] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                }
                if (caps.OpenGL13) {
                    GL13.glActiveTexture(activeTextureEnum);
                } else {
                    ARBMultitexture.glActiveTextureARB(activeTextureEnum);
                }
            } finally {
                GLStateMirror.exitRedirect();
            }
        }

        return new CgStateSnapshot(
            drawFbo,
            readFbo,
            fboFamily,
            programId,
            programFamily,
            activeUnitIndex,
            textures2d
        );
    }

    /**
     * Restores GL state to what it was when {@link #save()} was called.
     *
     * <p>Uses the call family recorded in the snapshot to issue the correct
     * GL unbind/rebind calls, preventing cross-family driver issues.  The
     * restore follows this order:</p>
     * <ol>
     *   <li><strong>Framebuffer</strong>: If the current FBO family differs from
     *       the snapshot's family, unbinds the current family first (binds 0),
     *       then rebinds the snapshot's FBO using the snapshot's family.</li>
     *   <li><strong>Shader program</strong>: Same cross-family logic as FBOs.</li>
     *   <li><strong>Textures</strong>: For each texture unit where the current
     *       binding differs from the snapshot, activates that unit and rebinds
     *       the snapshot's texture.  Finally restores the active texture unit
     *       itself.</li>
     * </ol>
     *
     * <p>If the snapshot has unknown state (either family is
     * {@link CallFamily#UNKNOWN}), the restore falls back to safe defaults:
     * GL30 for framebuffers, GL20 for programs.</p>
     *
     * @param snapshot the snapshot returned by a prior {@link #save()} call
     */
    public static void restore(CgStateSnapshot snapshot) {
        restoreFramebuffer(snapshot);
        restoreProgram(snapshot);
        restoreTextures(snapshot);
    }

    /**
     * Restores framebuffer state from the snapshot.
     *
     * <p>If the current FBO family differs from the snapshot's family (and
     * the current family is known), unbinds the current family first by
     * binding 0.  Then rebinds the snapshot's draw FBO using the snapshot's
     * family.  For EXT, always uses {@code GL_FRAMEBUFFER} since EXT does
     * not support separate draw/read targets.</p>
     *
     * @param snapshot the state snapshot to restore from
     */
    private static void restoreFramebuffer(CgStateSnapshot snapshot) {
        CallFamily currentFamily = GLStateMirror.getCurrentFboFamily();
        CallFamily snapshotFamily = snapshot.getFboFamily();

        // If current family differs from snapshot and current is known, unbind current first
        if (currentFamily != snapshotFamily && currentFamily != CallFamily.UNKNOWN) {
            bindFboByFamily(GL_FRAMEBUFFER, 0, currentFamily);
        }

        ContextCapabilities caps = GLContext.getCapabilities();
        boolean separateTargets = caps.OpenGL30 || caps.GL_ARB_framebuffer_object;
        if (separateTargets
                && snapshotFamily != CallFamily.EXT_FBO
                && snapshot.getDrawFboId() != snapshot.getReadFboId()) {
            bindFboByFamily(GL_DRAW_FRAMEBUFFER, snapshot.getDrawFboId(), snapshotFamily);
            bindFboByFamily(GL_READ_FRAMEBUFFER, snapshot.getReadFboId(), snapshotFamily);
        } else {
            bindFboByFamily(GL_FRAMEBUFFER, snapshot.getDrawFboId(), snapshotFamily);
        }
    }

    /**
     * Restores shader program state from the snapshot.
     *
     * <p>If the current program family differs from the snapshot's family (and
     * the current family is known), unbinds the current program first.  Then
     * rebinds the snapshot's program using the snapshot's family.</p>
     *
     * @param snapshot the state snapshot to restore from
     */
    private static void restoreProgram(CgStateSnapshot snapshot) {
        CallFamily currentFamily = GLStateMirror.getCurrentProgramFamily();
        CallFamily snapshotFamily = snapshot.getProgramFamily();

        // If current family differs from snapshot and current is known, unbind current first
        if (currentFamily != snapshotFamily && currentFamily != CallFamily.UNKNOWN) {
            useProgramByFamily(0, currentFamily);
        }

        // Rebind the snapshot's program using the snapshot's family
        useProgramByFamily(snapshot.getProgramId(), snapshotFamily);
    }

    /**
     * Restores texture bindings from the snapshot.
     *
     * <p>Iterates over all tracked texture units.  For each unit where the
     * current 2D texture binding (from {@link GLStateMirror}) differs from
     * the snapshot's binding, activates that unit and rebinds the snapshot's
     * texture.  After all units are restored, the active texture unit itself
     * is restored to match the snapshot.</p>
     *
     * @param snapshot the state snapshot to restore from
     */
    private static void restoreTextures(CgStateSnapshot snapshot) {
        int unitCount = snapshot.getTextureUnitCount();
        for (int i = 0; i < unitCount; i++) {
            int snapshotTex = snapshot.getBoundTexture2D(i);
            int currentTex = GLStateMirror.getBoundTexture2D(i);
            if (snapshotTex != currentTex) {
                setActiveTextureUnit(i);
                GL11.glBindTexture(0x0DE1, snapshotTex); // GL_TEXTURE_2D = 0x0DE1
            }
        }
        // Restore the active texture unit
        setActiveTextureUnit(snapshot.getActiveTextureUnit());
    }

    /**
     * Sets the active texture unit using the best available API for the current context.
     *
     * <p>On older hardware, multitexture may only be exposed via
     * {@code GL_ARB_multitexture} without Core GL13 being available. This helper
     * chooses GL13 when available, otherwise uses {@code ARBMultitexture}.</p>
     *
     * @param unitIndex the 0-based unit index (0 = GL_TEXTURE0)
     */
    private static void setActiveTextureUnit(int unitIndex) {
        ContextCapabilities caps = GLContext.getCapabilities();
        int unitConst = GL_TEXTURE0 + unitIndex;
        if (caps.OpenGL13) {
            GL13.glActiveTexture(unitConst);
            return;
        }
        if (caps.GL_ARB_multitexture) {
            ARBMultitexture.glActiveTextureARB(unitConst);
            return;
        }
        throw new IllegalStateException("No active-texture API available (OpenGL13 and ARB_multitexture both absent)");
    }

    /**
     * Binds a framebuffer using the specified call family.
     *
     * <p>Dispatches to the correct LWJGL entry point based on the family.
     * For {@link CallFamily#UNKNOWN} or {@link CallFamily#OPENGLHELPER_WRAPPER},
     * falls back to GL30 as the safest default.</p>
     *
     * @param target the framebuffer target ({@code GL_FRAMEBUFFER})
     * @param fboId  the framebuffer object ID
     * @param family the call family to use for the bind
     */
    private static void bindFboByFamily(int target, int fboId, CallFamily family) {
        switch (family) {
            case ARB_FBO:
                ARBFramebufferObject.glBindFramebuffer(target, fboId);
                break;
            case EXT_FBO:
                EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER, fboId);
                break;
            case OPENGLHELPER_WRAPPER:
                OpenGlHelper.func_153171_g(target, fboId);
                break;
            case CORE_GL30:
            default:
                ContextCapabilities caps = GLContext.getCapabilities();
                if (caps.OpenGL30) {
                    GL30.glBindFramebuffer(target, fboId);
                } else if (caps.GL_ARB_framebuffer_object) {
                    ARBFramebufferObject.glBindFramebuffer(target, fboId);
                } else if (caps.GL_EXT_framebuffer_object) {
                    EXTFramebufferObject.glBindFramebufferEXT(target, fboId);
                } else {
                    OpenGlHelper.func_153171_g(target, fboId);
                }
                break;
        }
    }

    /**
     * Binds a shader program using the specified call family.
     *
     * <p>Dispatches to the correct LWJGL entry point based on the family.
     * For {@link CallFamily#UNKNOWN}, falls back to GL20 as the safest
     * default.</p>
     *
     * @param programId the shader program ID (0 = unbind)
     * @param family    the call family to use for the bind
     */
    private static void useProgramByFamily(int programId, CallFamily family) {
        switch (family) {
            case ARB_SHADER_OBJECTS:
                ARBShaderObjects.glUseProgramObjectARB(programId);
                break;
            case CORE_GL20:
            default:
                ContextCapabilities caps = GLContext.getCapabilities();
                if (caps.OpenGL20) {
                    GL20.glUseProgram(programId);
                } else if (caps.GL_ARB_shader_objects) {
                    ARBShaderObjects.glUseProgramObjectARB(programId);
                } else {
                }
                break;
        }
    }
}
