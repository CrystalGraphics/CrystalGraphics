package io.github.somehussar.crystalgraphics.mc.coremod;

import io.github.somehussar.crystalgraphics.gl.state.CallFamily;
import io.github.somehussar.crystalgraphics.gl.state.GLStateMirror;

import net.minecraft.client.renderer.OpenGlHelper;

import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ARBMultitexture;
import org.lwjgl.opengl.ARBShaderObjects;
import org.lwjgl.opengl.EXTFramebufferObject;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Static redirect targets for LWJGL and Minecraft GL calls.
 *
 * <p>The ASM transformer ({@link CrystalGraphicsTransformer}) rewrites call
 * sites throughout the Minecraft codebase and other mods so that instead of
 * invoking the original LWJGL / {@code OpenGlHelper} methods directly, they
 * call the corresponding static method in this class.  Each redirect:</p>
 *
 * <ol>
 *   <li>Checks the recursion guard ({@link GLStateMirror#isInRedirect()}).
 *       If already inside a redirect, state tracking is skipped to prevent
 *       infinite recursion.</li>
 *   <li>Updates the {@link GLStateMirror} with the new state <em>before</em>
 *       the actual GL call, so the mirror is always up-to-date before any
 *       side effects occur.</li>
 *   <li>Activates the recursion guard via
 *       {@link GLStateMirror#enterRedirect()} and calls the original LWJGL /
 *       Minecraft method inside a try/finally block that always calls
 *       {@link GLStateMirror#exitRedirect()}.</li>
 * </ol>
 *
 * <h3>Design Constraints</h3>
 * <ul>
 *   <li><strong>No object allocations</strong> &mdash; these methods are on
 *       the rendering hot path and must be allocation-free.</li>
 *   <li><strong>No {@code glGetInteger}</strong> &mdash; the mirror is updated
 *       exclusively from call arguments; no GL queries are issued.</li>
 *   <li><strong>Java 8 compatible</strong>.</li>
 * </ul>
 *
 * @see GLStateMirror
 * @see CallFamily
 */
public final class CrystalGLRedirects {

    /**
     * Private constructor to prevent instantiation.  All methods are static.
     */
    private CrystalGLRedirects() {
    }

    // ── Framebuffer binds ──────────────────────────────────────────────

    /**
     * Redirect for {@code GL30.glBindFramebuffer(int, int)}.
     *
     * <p>Updates the state mirror with {@link CallFamily#CORE_GL30} and
     * then delegates to the original Core GL30 entry point.</p>
     *
     * @param target      the framebuffer target ({@code GL_FRAMEBUFFER},
     *                    {@code GL_DRAW_FRAMEBUFFER}, or
     *                    {@code GL_READ_FRAMEBUFFER})
     * @param framebuffer the framebuffer object ID (0 = default framebuffer)
     */
    public static void bindFramebufferCore(int target, int framebuffer) {
        if (!GLStateMirror.isInRedirect()) {
            GLStateMirror.onBindFramebuffer(target, framebuffer, CallFamily.CORE_GL30);
        }
        GLStateMirror.enterRedirect();
        try {
            GL30.glBindFramebuffer(target, framebuffer);
        } finally {
            GLStateMirror.exitRedirect();
        }
    }

    /**
     * Redirect for {@code ARBFramebufferObject.glBindFramebuffer(int, int)}.
     *
     * <p>Updates the state mirror with {@link CallFamily#ARB_FBO} and
     * then delegates to the original ARB extension entry point.</p>
     *
     * @param target      the framebuffer target ({@code GL_FRAMEBUFFER},
     *                    {@code GL_DRAW_FRAMEBUFFER}, or
     *                    {@code GL_READ_FRAMEBUFFER})
     * @param framebuffer the framebuffer object ID (0 = default framebuffer)
     */
    public static void bindFramebufferArb(int target, int framebuffer) {
        if (!GLStateMirror.isInRedirect()) {
            GLStateMirror.onBindFramebuffer(target, framebuffer, CallFamily.ARB_FBO);
        }
        GLStateMirror.enterRedirect();
        try {
            ARBFramebufferObject.glBindFramebuffer(target, framebuffer);
        } finally {
            GLStateMirror.exitRedirect();
        }
    }

    /**
     * Redirect for {@code EXTFramebufferObject.glBindFramebufferEXT(int, int)}.
     *
     * <p>Updates the state mirror with {@link CallFamily#EXT_FBO} and
     * then delegates to the original EXT extension entry point.  Note the
     * {@code EXT} method suffix required by the legacy EXT API.</p>
     *
     * @param target      the framebuffer target (for EXT, typically
     *                    {@code GL_FRAMEBUFFER_EXT} = 0x8D40)
     * @param framebuffer the framebuffer object ID (0 = default framebuffer)
     */
    public static void bindFramebufferExt(int target, int framebuffer) {
        if (!GLStateMirror.isInRedirect()) {
            GLStateMirror.onBindFramebuffer(target, framebuffer, CallFamily.EXT_FBO);
        }
        GLStateMirror.enterRedirect();
        try {
            EXTFramebufferObject.glBindFramebufferEXT(target, framebuffer);
        } finally {
            GLStateMirror.exitRedirect();
        }
    }

    /**
     * Redirect for Minecraft's {@code OpenGlHelper.func_153171_g(int, int)}
     * (the obfuscated name for {@code OpenGlHelper.bindFramebuffer}).
     *
     * <p>Updates the state mirror with {@link CallFamily#OPENGLHELPER_WRAPPER}
     * and then delegates to Minecraft's wrapper, which internally dispatches
     * to one of the GL call families based on detected capabilities.</p>
     *
     * @param target      the framebuffer target
     * @param framebuffer the framebuffer object ID (0 = default framebuffer)
     */
    public static void bindFramebufferMc(int target, int framebuffer) {
        if (!GLStateMirror.isInRedirect()) {
            GLStateMirror.onBindFramebuffer(target, framebuffer, CallFamily.OPENGLHELPER_WRAPPER);
        }
        GLStateMirror.enterRedirect();
        try {
            OpenGlHelper.func_153171_g(target, framebuffer);
        } finally {
            GLStateMirror.exitRedirect();
        }
    }

    // ── Shader program binds ───────────────────────────────────────────

    /**
     * Redirect for {@code GL20.glUseProgram(int)}.
     *
     * <p>Updates the state mirror with {@link CallFamily#CORE_GL20} and
     * then delegates to the original Core GL20 entry point.</p>
     *
     * @param program the shader program ID (0 = unbind / fixed-function)
     */
    public static void useProgramCore(int program) {
        if (!GLStateMirror.isInRedirect()) {
            GLStateMirror.onUseProgram(program, CallFamily.CORE_GL20);
        }
        GLStateMirror.enterRedirect();
        try {
            GL20.glUseProgram(program);
        } finally {
            GLStateMirror.exitRedirect();
        }
    }

    /**
     * Redirect for {@code ARBShaderObjects.glUseProgramObjectARB(int)}.
     *
     * <p>Updates the state mirror with {@link CallFamily#ARB_SHADER_OBJECTS}
     * and then delegates to the original ARB shader objects entry point.</p>
     *
     * @param program the shader program object handle (0 = unbind)
     */
    public static void useProgramArb(int program) {
        if (!GLStateMirror.isInRedirect()) {
            GLStateMirror.onUseProgram(program, CallFamily.ARB_SHADER_OBJECTS);
        }
        GLStateMirror.enterRedirect();
        try {
            ARBShaderObjects.glUseProgramObjectARB(program);
        } finally {
            GLStateMirror.exitRedirect();
        }
    }

    // ── Texture state ──────────────────────────────────────────────────

    /**
     * Redirect for {@code GL13.glActiveTexture(int)}.
     *
     * <p>Updates the state mirror's active texture unit and then delegates to
     * the Core GL13 entry point.</p>
     *
     * @param texture the texture unit constant ({@code GL_TEXTURE0} + unit index)
     */
    public static void activeTextureCore(int texture) {
        if (!GLStateMirror.isInRedirect()) {
            GLStateMirror.onActiveTexture(texture);
        }
        GLStateMirror.enterRedirect();
        try {
            GL13.glActiveTexture(texture);
        } finally {
            GLStateMirror.exitRedirect();
        }
    }

    /**
     * Redirect for {@code ARBMultitexture.glActiveTextureARB(int)}.
     *
     * <p>Some older contexts expose multitexture only through the ARB extension
     * without Core GL13 being available. This redirect preserves the original
     * call family by invoking the ARB entry point, avoiding crashes that would
     * occur if we always called {@code GL13.glActiveTexture}.</p>
     *
     * @param texture the texture unit constant ({@code GL_TEXTURE0} + unit index)
     */
    public static void activeTextureArb(int texture) {
        if (!GLStateMirror.isInRedirect()) {
            GLStateMirror.onActiveTexture(texture);
        }
        GLStateMirror.enterRedirect();
        try {
            ARBMultitexture.glActiveTextureARB(texture);
        } finally {
            GLStateMirror.exitRedirect();
        }
    }

    /**
     * Redirect for {@code GL11.glBindTexture(int, int)}.
     *
     * <p>Updates the state mirror's bound texture for the currently active
     * texture unit (only {@code GL_TEXTURE_2D} binds are tracked) and then
     * delegates to the original GL11 entry point.</p>
     *
     * @param target  the texture target (e.g., {@code GL_TEXTURE_2D})
     * @param texture the texture ID (0 = unbind)
     */
    public static void bindTexture(int target, int texture) {
        if (!GLStateMirror.isInRedirect()) {
            GLStateMirror.onBindTexture(target, texture);
        }
        GLStateMirror.enterRedirect();
        try {
            GL11.glBindTexture(target, texture);
        } finally {
            GLStateMirror.exitRedirect();
        }
    }
}
