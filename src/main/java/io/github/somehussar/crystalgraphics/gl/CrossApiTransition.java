package io.github.somehussar.crystalgraphics.gl;

import io.github.somehussar.crystalgraphics.gl.state.CallFamily;
import io.github.somehussar.crystalgraphics.gl.state.GLStateMirror;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GLContext;

/**
 * Performs safe cross-API binding transitions for framebuffers and shader
 * programs.
 *
 * <h3>The Problem</h3>
 * <p>When multiple mods and Minecraft itself bind OpenGL resources via
 * different LWJGL entry points (Core GL30, ARB, EXT, or Minecraft's
 * {@code OpenGlHelper} wrapper), switching between call families back-to-back
 * can cause undefined driver behavior on some hardware.  For example, if an
 * external mod bound FBO #5 via the ARB extension and CrystalGraphics now
 * wants to bind FBO #7 via Core GL30, some drivers exhibit corrupt state or
 * rendering artifacts because the ARB-bound resource was never explicitly
 * unbound through the same API path.</p>
 *
 * <h3>The Solution</h3>
 * <p>This class inspects the {@link GLStateMirror} to determine which call
 * family produced the currently active binding.  If that family differs from
 * the requested target family, a <em>defensive unbind</em> is performed via
 * the <em>previous</em> family first (binding 0 through that family's entry
 * point), and then the new resource is bound through the target family.</p>
 *
 * <h3>EXT Special Case</h3>
 * <p>The EXT framebuffer extension does not support separate draw/read targets
 * ({@code GL_DRAW_FRAMEBUFFER} / {@code GL_READ_FRAMEBUFFER}).  All EXT
 * operations use {@code GL_FRAMEBUFFER_EXT} (0x8D40) regardless of the
 * requested target.</p>
 *
 * <h3>Recursion Safety</h3>
 * <p>All raw LWJGL calls are wrapped with
 * {@link GLStateMirror#enterRedirect()} / {@link GLStateMirror#exitRedirect()}
 * to prevent the bytecode transformer's redirect layer from re-entering
 * state tracking during the transition, which would cause infinite
 * recursion.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>This class has no mutable state of its own.  Thread safety is inherited
 * from {@link GLStateMirror} (which uses a {@code ThreadLocal} recursion
 * guard).  All methods must be called on the render thread with an active
 * OpenGL context.</p>
 *
 * @see GLStateMirror
 * @see CallFamily
 */
public final class CrossApiTransition {

    /** {@code GL_FRAMEBUFFER} — used as the unbind target for EXT and as a generic target. */
    private static final int GL_FRAMEBUFFER = 0x8D40;

    /**
     * Private constructor to prevent instantiation.  All access is through
     * static methods.
     */
    private CrossApiTransition() {
    }

    /**
     * Performs a safe framebuffer bind, transitioning from the currently-active
     * call family if needed.
     *
     * <p>If the current FBO family (from {@link GLStateMirror}) differs from
     * {@code targetFamily}, first unbinds the current family (binds 0 via the
     * current family), then binds {@code id} via {@code targetFamily}.</p>
     *
     * <p>If the current family is {@link CallFamily#UNKNOWN}, binds directly
     * via {@code targetFamily} (no unbind — we don't know what to unbind).
     * If the current family matches {@code targetFamily}, binds directly
     * (no transition needed).</p>
     *
     * <p>For the EXT family, separate draw/read targets are not supported.
     * All EXT operations use {@code GL_FRAMEBUFFER} (0x8D40) regardless of
     * the {@code target} parameter.</p>
     *
     * @param target       GL target constant ({@code GL_FRAMEBUFFER} = 0x8D40,
     *                     {@code GL_DRAW_FRAMEBUFFER} = 0x8CA9,
     *                     {@code GL_READ_FRAMEBUFFER} = 0x8CA8)
     * @param id           framebuffer ID to bind (0 = default framebuffer)
     * @param targetFamily the call family to use for the bind
     * @throws IllegalArgumentException if {@code targetFamily} is {@code null}
     */
    public static void bindFramebuffer(int target, int id, CallFamily targetFamily) {
        CallFamily currentFamily = GLStateMirror.getCurrentFboFamily();

        // Transition: unbind via the previous family if it differs
        if (currentFamily != targetFamily
                && currentFamily != CallFamily.UNKNOWN) {
            unbindFboViaFamily(currentFamily);
        }

        // Bind via the target family
        bindFboViaFamily(target, id, targetFamily);
    }

    /**
     * Performs a safe shader program bind, transitioning from the
     * currently-active call family if needed.
     *
     * <p>If the current program family (from {@link GLStateMirror}) differs
     * from {@code targetFamily}, first unbinds via the current family (binds
     * program 0), then binds {@code programId} via {@code targetFamily}.</p>
     *
     * <p>If the current family is {@link CallFamily#UNKNOWN}, binds directly
     * via {@code targetFamily} (no unbind).  If the current family matches
     * {@code targetFamily}, binds directly (no transition needed).</p>
     *
     * @param programId    program ID to bind (0 = no program / fixed-function)
     * @param targetFamily the call family to use for the bind
     * @throws IllegalArgumentException if {@code targetFamily} is {@code null}
     */
    public static void bindProgram(int programId, CallFamily targetFamily) {
        CallFamily currentFamily = GLStateMirror.getCurrentProgramFamily();

        // Transition: unbind via the previous family if it differs
        if (currentFamily != targetFamily
                && currentFamily != CallFamily.UNKNOWN) {
            unbindProgramViaFamily(currentFamily);
        }

        // Bind via the target family
        bindProgramViaFamily(programId, targetFamily);
    }

    // ── Private FBO helpers ────────────────────────────────────────────

    /**
     * Unbinds the current framebuffer by binding 0 via the specified family.
     *
     * <p>This performs a defensive unbind to ensure the driver's internal
     * state for the previous call family is cleanly reset before a different
     * family takes over.</p>
     *
     * @param family the call family to unbind through
     */
    private static void unbindFboViaFamily(CallFamily family) {
        GLStateMirror.enterRedirect();
        try {
            switch (family) {
                case CORE_GL30:
                    GL30.glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    break;
                case ARB_FBO:
                    ARBFramebufferObject.glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    break;
                case EXT_FBO:
                    EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER, 0);
                    break;
                case OPENGLHELPER_WRAPPER:
                    OpenGlHelper.func_153171_g(GL_FRAMEBUFFER, 0);
                    break;
                default:
                    // UNKNOWN or non-FBO families — nothing to unbind
                    break;
            }
        } finally {
            GLStateMirror.exitRedirect();
        }
        GLStateMirror.onBindFramebuffer(GL_FRAMEBUFFER, 0, family);
    }

    /**
     * Binds a framebuffer via the specified call family, updating the
     * state mirror.
     *
     * <p>For the EXT family, the target is always forced to
     * {@code GL_FRAMEBUFFER} (0x8D40) since EXT does not support
     * separate draw/read targets.</p>
     *
     * @param target GL target constant
     * @param id     framebuffer object ID
     * @param family the call family to use
     */
    private static void bindFboViaFamily(int target, int id, CallFamily family) {
        int effectiveTarget = target;
        CallFamily usedFamily = family;
        GLStateMirror.enterRedirect();
        try {
            switch (family) {
                case CORE_GL30:
                    GL30.glBindFramebuffer(target, id);
                    break;
                case ARB_FBO:
                    ARBFramebufferObject.glBindFramebuffer(target, id);
                    break;
                case EXT_FBO:
                    // EXT has no separate draw/read — always use GL_FRAMEBUFFER
                    effectiveTarget = GL_FRAMEBUFFER;
                    EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER, id);
                    break;
                case OPENGLHELPER_WRAPPER:
                    OpenGlHelper.func_153171_g(target, id);
                    break;
                default:
                    ContextCapabilities caps = GLContext.getCapabilities();
                    if (caps.OpenGL30) {
                        usedFamily = CallFamily.CORE_GL30;
                        GL30.glBindFramebuffer(target, id);
                    } else if (caps.GL_ARB_framebuffer_object) {
                        usedFamily = CallFamily.ARB_FBO;
                        ARBFramebufferObject.glBindFramebuffer(target, id);
                    } else if (caps.GL_EXT_framebuffer_object) {
                        usedFamily = CallFamily.EXT_FBO;
                        effectiveTarget = GL_FRAMEBUFFER;
                        EXTFramebufferObject.glBindFramebufferEXT(GL_FRAMEBUFFER, id);
                    } else {
                        usedFamily = CallFamily.OPENGLHELPER_WRAPPER;
                        OpenGlHelper.func_153171_g(target, id);
                    }
                    break;
            }
        } finally {
            GLStateMirror.exitRedirect();
        }
        GLStateMirror.onBindFramebuffer(effectiveTarget, id, usedFamily);
    }

    // ── Private shader program helpers ─────────────────────────────────

    /**
     * Unbinds the current shader program by binding 0 via the specified
     * family.
     *
     * @param family the call family to unbind through
     */
    private static void unbindProgramViaFamily(CallFamily family) {
        GLStateMirror.enterRedirect();
        try {
            switch (family) {
                case CORE_GL20:
                    GL20.glUseProgram(0);
                    break;
                case ARB_SHADER_OBJECTS:
                    ARBShaderObjects.glUseProgramObjectARB(0);
                    break;
                default:
                    // Non-shader families — nothing to unbind
                    break;
            }
        } finally {
            GLStateMirror.exitRedirect();
        }
        GLStateMirror.onUseProgram(0, family);
    }

    /**
     * Binds a shader program via the specified call family, updating the
     * state mirror.
     *
     * @param programId program object ID (0 = fixed-function)
     * @param family    the call family to use
     */
    private static void bindProgramViaFamily(int programId, CallFamily family) {
        CallFamily usedFamily = family;
        GLStateMirror.enterRedirect();
        try {
            switch (family) {
                case CORE_GL20:
                    GL20.glUseProgram(programId);
                    break;
                case ARB_SHADER_OBJECTS:
                    ARBShaderObjects.glUseProgramObjectARB(programId);
                    break;
                default:
                    ContextCapabilities caps = GLContext.getCapabilities();
                    if (caps.OpenGL20) {
                        usedFamily = CallFamily.CORE_GL20;
                        GL20.glUseProgram(programId);
                    } else if (caps.GL_ARB_shader_objects) {
                        usedFamily = CallFamily.ARB_SHADER_OBJECTS;
                        ARBShaderObjects.glUseProgramObjectARB(programId);
                    } else {
                        usedFamily = CallFamily.UNKNOWN;
                    }
                    break;
            }
        } finally {
            GLStateMirror.exitRedirect();
        }
        GLStateMirror.onUseProgram(programId, usedFamily);
    }
}
