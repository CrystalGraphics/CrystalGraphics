package io.github.somehussar.crystalgraphics.mc.coremod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Defines the complete set of OpenGL call-site redirections that the
 * {@link CrystalGraphicsTransformer} applies.
 *
 * <p>Two operating modes are provided:</p>
 * <ul>
 *   <li><b>{@link #FULL_MODE}</b> &mdash; used when Angelica is <em>not</em> present.
 *       Intercepts all GL state-mutating calls that CrystalGraphics needs to track:
 *       framebuffer binds (Core/ARB/EXT and OpenGlHelper), shader program binds
 *       (Core/ARB), active-texture switching (Core/ARB), and texture binds.</li>
 *   <li><b>{@link #GAP_ONLY_MODE}</b> &mdash; used when Angelica <em>is</em> present.
 *       Angelica's {@code GLStateManager} already redirects:
 *       {@code GL30.glBindFramebuffer}, {@code GL20.glUseProgram},
 *       {@code OpenGlHelper.func_153171_g}, {@code GL13.glActiveTexture},
 *       {@code GL11.glBindTexture}, and {@code ARBMultitexture.glActiveTextureARB}.
 *       The gap-only list intercepts only the extension variants that Angelica
 *       does <em>not</em> cover:
 *       {@code ARBFramebufferObject.glBindFramebuffer},
 *       {@code EXTFramebufferObject.glBindFramebufferEXT}, and
 *       {@code ARBShaderObjects.glUseProgramObjectARB}.</li>
 * </ul>
 *
 * <p>All lists are unmodifiable and safe to share across threads.</p>
 *
 * <p>This class is not instantiable.</p>
 */
public final class CoverageMatrix {

    /** Internal name of the class containing redirect target methods. */
    private static final String REDIRECT_CLASS =
        "io/github/somehussar/crystalgraphics/mc/coremod/CrystalGLRedirects";

    /**
     * Full redirect list for use when Angelica is absent.
     *
     * <p>Covers all framebuffer bind paths (GL30 core, ARB, EXT, and
     * OpenGlHelper's SRG-named wrapper), both shader program bind paths
     * (GL20 core and ARB), active texture switching (GL13 core and ARB),
     * and texture binding (GL11).</p>
     */
    public static final List<Redirect> FULL_MODE;

    /**
     * Reduced redirect list for use when Angelica is present.
     *
     * <p>Contains only the three call sites that Angelica's GLStateManager
     * does not intercept: ARB framebuffer bind, EXT framebuffer bind, and
     * ARB shader program bind.</p>
     */
    public static final List<Redirect> GAP_ONLY_MODE;

    static {
        List<Redirect> full = new ArrayList<Redirect>();

        // --- Framebuffer binds ---
        full.add(new Redirect(
            "org/lwjgl/opengl/GL30",
            "glBindFramebuffer",
            "(II)V",
            REDIRECT_CLASS,
            "bindFramebufferCore"
        ));
        full.add(new Redirect(
            "org/lwjgl/opengl/ARBFramebufferObject",
            "glBindFramebuffer",
            "(II)V",
            REDIRECT_CLASS,
            "bindFramebufferArb"
        ));
        full.add(new Redirect(
            "org/lwjgl/opengl/EXTFramebufferObject",
            "glBindFramebufferEXT",
            "(II)V",
            REDIRECT_CLASS,
            "bindFramebufferExt"
        ));
        full.add(new Redirect(
            "net/minecraft/client/renderer/OpenGlHelper",
            "func_153171_g",
            "(II)V",
            REDIRECT_CLASS,
            "bindFramebufferMc"
        ));

        // --- Shader program binds ---
        full.add(new Redirect(
            "org/lwjgl/opengl/GL20",
            "glUseProgram",
            "(I)V",
            REDIRECT_CLASS,
            "useProgramCore"
        ));
        full.add(new Redirect(
            "org/lwjgl/opengl/ARBShaderObjects",
            "glUseProgramObjectARB",
            "(I)V",
            REDIRECT_CLASS,
            "useProgramArb"
        ));

        // --- Active texture ---
        full.add(new Redirect(
            "org/lwjgl/opengl/GL13",
            "glActiveTexture",
            "(I)V",
            REDIRECT_CLASS,
            "activeTextureCore"
        ));
        full.add(new Redirect(
            "org/lwjgl/opengl/ARBMultitexture",
            "glActiveTextureARB",
            "(I)V",
            REDIRECT_CLASS,
            "activeTextureArb"
        ));

        // --- Texture bind ---
        full.add(new Redirect(
            "org/lwjgl/opengl/GL11",
            "glBindTexture",
            "(II)V",
            REDIRECT_CLASS,
            "bindTexture"
        ));

        FULL_MODE = Collections.unmodifiableList(full);

        // --- Gap-only: ARB/EXT variants that Angelica does not cover ---
        List<Redirect> gap = new ArrayList<Redirect>();

        gap.add(new Redirect(
            "org/lwjgl/opengl/ARBFramebufferObject",
            "glBindFramebuffer",
            "(II)V",
            REDIRECT_CLASS,
            "bindFramebufferArb"
        ));
        gap.add(new Redirect(
            "org/lwjgl/opengl/EXTFramebufferObject",
            "glBindFramebufferEXT",
            "(II)V",
            REDIRECT_CLASS,
            "bindFramebufferExt"
        ));
        gap.add(new Redirect(
            "org/lwjgl/opengl/ARBShaderObjects",
            "glUseProgramObjectARB",
            "(I)V",
            REDIRECT_CLASS,
            "useProgramArb"
        ));

        GAP_ONLY_MODE = Collections.unmodifiableList(gap);
    }

    /**
     * Private constructor to prevent instantiation.
     *
     * @throws AssertionError always
     */
    private CoverageMatrix() {
        throw new AssertionError("CoverageMatrix is not instantiable");
    }

    /**
     * Returns the appropriate redirect list for the current operating mode.
     *
     * @param gapOnly {@code true} to return the gap-only list (Angelica present),
     *                {@code false} for the full redirect list (Angelica absent)
     * @return an unmodifiable list of {@link Redirect} entries; never {@code null}
     */
    public static List<Redirect> forMode(boolean gapOnly) {
        return gapOnly ? GAP_ONLY_MODE : FULL_MODE;
    }

    /**
     * An immutable description of a single INVOKESTATIC call-site redirect.
     *
     * <p>Each instance maps an original OpenGL (or Minecraft helper) static call
     * to a corresponding method in the CrystalGraphics redirect class. The
     * descriptor is preserved unchanged &mdash; the redirect method must accept
     * exactly the same parameter types and return the same type.</p>
     *
     * <p>Instances of this class are immutable and safe to share across threads.</p>
     */
    public static final class Redirect {

        /**
         * The internal (slash-separated) name of the class that owns the
         * original call site.
         *
         * <p>Example: {@code "org/lwjgl/opengl/GL30"}</p>
         */
        public final String ownerInternalName;

        /**
         * The name of the method being called at the original call site.
         *
         * <p>Example: {@code "glBindFramebuffer"}</p>
         */
        public final String methodName;

        /**
         * The JVM method descriptor of both the original and redirect methods.
         *
         * <p>Example: {@code "(II)V"}</p>
         */
        public final String descriptor;

        /**
         * The internal (slash-separated) name of the class containing the
         * redirect target method.
         *
         * <p>Example: {@code "io/github/somehussar/crystalgraphics/mc/coremod/CrystalGLRedirects"}</p>
         */
        public final String redirectClass;

        /**
         * The name of the redirect target method. Must have the same descriptor
         * as the original method.
         *
         * <p>Example: {@code "bindFramebufferCore"}</p>
         */
        public final String redirectMethod;

        /**
         * Creates a new redirect entry.
         *
         * @param ownerInternalName the internal name of the class owning the original call
         * @param methodName        the original method name
         * @param descriptor        the JVM method descriptor
         * @param redirectClass     the internal name of the redirect target class
         * @param redirectMethod    the redirect target method name
         */
        public Redirect(String ownerInternalName,
                         String methodName,
                         String descriptor,
                         String redirectClass,
                         String redirectMethod) {
            this.ownerInternalName = ownerInternalName;
            this.methodName = methodName;
            this.descriptor = descriptor;
            this.redirectClass = redirectClass;
            this.redirectMethod = redirectMethod;
        }
    }
}
