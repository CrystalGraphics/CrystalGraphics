package io.github.somehussar.crystalgraphics.gl.debug;

import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.state.CgBlendState;
import io.github.somehussar.crystalgraphics.api.state.CgColorMask;
import io.github.somehussar.crystalgraphics.api.state.CgDepthState;
import io.github.somehussar.crystalgraphics.api.state.CgGlSlot;
import io.github.somehussar.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle;
import io.github.somehussar.crystalgraphics.gl.shader.CgShaderFactory;
import io.github.somehussar.crystalgraphics.gl.state.CgGlScope;
import io.github.somehussar.crystalgraphics.gl.state.CgGlState;
import io.github.somehussar.crystalgraphics.gl.vertex.CgVertexArray;
import org.lwjgl.opengl.GL11;

/**
 * Fullscreen debug blit utility that renders any texture as a full-viewport overlay.
 *
 * <h3>Covering-triangle technique</h3>
 * <p>Rather than uploading a quad mesh, {@code CgDebugBlit} uses the "covering triangle"
 * (also called "big triangle") technique. Three vertices are generated entirely from
 * {@code gl_VertexID} inside the vertex shader — no VBO or vertex data is needed.
 * The triangle's clip-space positions are {@code (-1,-1)}, {@code (3,-1)}, and
 * {@code (-1,3)}, which enclose the entire NDC square. The GPU clips the
 * overshooting area automatically. A single empty VAO is bound to satisfy the GL 3.x
 * core-profile requirement that a VAO must always be active during a draw call.</p>
 *
 * <h3>GL-thread constraint</h3>
 * <p>All public methods ({@link #depth}, {@link #rgba}, and {@link #dispose}) must
 * be called on the render thread that owns the GL context. Calling them from any
 * other thread is undefined behaviour.</p>
 *
 * <h3>Lifecycle</h3>
 * <p>The singleton is created lazily on the first call to {@link #depth} or {@link #rgba}.
 * {@link #dispose()} must be called when the GL context is destroyed — wire it into
 * {@link CgGraphicsLifecycle#destroyContext()}
 * or call it from your mod's teardown hook. Failing to call {@code dispose()} will leak
 * the two compiled shader programs and the empty VAO on the GPU.</p>
 *
 * <h3>Usage example</h3>
 * <pre>{@code
 * // Inside a debug render hook — visualize the scene depth buffer:
 * CgDebugBlit.depth(myFbo.getDepthAttachmentId(), 0.1f, 512f);
 *
 * // Visualize a color attachment:
 * CgDebugBlit.rgba(myFbo.getColorAttachmentId(0));
 * }</pre>
 */
public final class CgDebugBlit {

    // ── GLSL source ───────────────────────────────────────────────────────────

    /**
     * Vertex shader shared by both programs.
     * Generates a covering triangle from {@code gl_VertexID} — no vertex buffer required.
     */
    private static final String VERT_SRC =
            "#version 330 core\n"
                    + "\n"
                    + "out vec2 v_uv;\n"
                    + "\n"
                    + "void main() {\n"
                    + "    // Covering triangle: 3 vertices that enclose the entire NDC square.\n"
                    + "    // gl_VertexID: 0 -> (-1,-1), 1 -> (3,-1), 2 -> (-1,3)\n"
                    + "    // UVs [0,1] map to the screen; the overshooting triangle area is clipped.\n"
                    + "    vec2 uv = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);\n"
                    + "    v_uv       = uv;\n"
                    + "    gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0);\n"
                    + "}\n";

    /**
     * Fragment shader that linearizes a non-linear depth texture to a [0,1] grayscale value.
     *
     * <p>The raw NDC depth is linearized to eye-space using the standard perspective formula:<br>
     * {@code eyeDepth = (2 * near * far) / (far + near - z_ndc * (far - near))}<br>
     * then normalized to [0,1] by dividing by {@code far}.</p>
     */
    private static final String DEPTH_FRAG_SRC =
            "#version 330 core\n"
                    + "\n"
                    + "uniform sampler2D u_depthTex;\n"
                    + "uniform float u_near;\n"
                    + "uniform float u_far;\n"
                    + "\n"
                    + "in  vec2 v_uv;\n"
                    + "out vec4 fragColor;\n"
                    + "\n"
                    + "void main() {\n"
                    + "    float rawDepth = texture(u_depthTex, v_uv).r;\n"
                    + "    // Linearize from non-linear NDC depth to eye-space, then normalize to [0,1].\n"
                    + "    // Formula: eyeDepth = (2 * near * far) / (far + near - z_ndc * (far - near))\n"
                    + "    float z        = rawDepth * 2.0 - 1.0;\n"
                    + "    float eyeDepth = (2.0 * u_near * u_far) / (u_far + u_near - z * (u_far - u_near));\n"
                    + "    float linear01 = clamp(eyeDepth / u_far, 0.0, 1.0);\n"
                    + "    fragColor      = vec4(rawDepth, rawDepth, rawDepth, 1.0);\n"
                    + "}\n";

    /**
     * Fragment shader that blits a raw RGBA texture with no transformation.
     */
    private static final String RGBA_FRAG_SRC =
            "#version 330 core\n"
                    + "\n"
                    + "uniform sampler2D u_tex;\n"
                    + "\n"
                    + "in  vec2 v_uv;\n"
                    + "out vec4 fragColor;\n"
                    + "\n"
                    + "void main() {\n"
                    + "    fragColor = texture(u_tex, v_uv);\n"
                    + "}\n";

    // ── Singleton ─────────────────────────────────────────────────────────────

    /** Lazily initialized singleton; {@code null} until the first draw call. */
    private static CgDebugBlit INSTANCE;

    // ── Instance fields ───────────────────────────────────────────────────────

    /**
     * Shader that linearizes a depth texture to [0,1] grayscale.
     * Created from {@link #VERT_SRC} + {@link #DEPTH_FRAG_SRC}.
     */
    private final CgShader depthShader;

    /**
     * Shader that blits a raw RGBA texture as-is.
     * Created from {@link #VERT_SRC} + {@link #RGBA_FRAG_SRC}.
     */
    private final CgShader blitShader;

    /**
     * Empty VAO — no attribute pointers configured.
     * Satisfies the GL 3.x core-profile requirement that a VAO must be bound during a draw.
     * {@code configure()} is intentionally never called; vertex data comes from {@code gl_VertexID}.
     */
    private final CgVertexArray emptyVao;

    // ── Constructor ───────────────────────────────────────────────────────────

    private CgDebugBlit() {
        depthShader = CgShaderFactory.fromSource(VERT_SRC, DEPTH_FRAG_SRC);
        blitShader = CgShaderFactory.fromSource(VERT_SRC, RGBA_FRAG_SRC);
        emptyVao = CgVertexArray.create();
    }

    // ── Singleton accessor ────────────────────────────────────────────────────

    /**
     * Returns the singleton, initializing it on the first call.
     *
     * <p>Must only be called on the GL thread.</p>
     */
    private static CgDebugBlit instance() {
        if (INSTANCE == null) INSTANCE = new CgDebugBlit();
        return INSTANCE;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Blits a depth texture as a linearized [0,1] grayscale overlay covering the entire viewport.
     *
     * <p>The raw non-linear depth values stored in {@code depthTexId} are transformed to
     * eye-space using the perspective linearization formula, then normalized to [0,1] by
     * dividing by {@code far}. The result is written as a grayscale RGBA image where
     * darker values are closer to the near plane and brighter values are further away.</p>
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @param depthTexId the GL texture object ID of the depth texture to visualize
     * @param near       the near clip plane distance used when rendering the scene
     * @param far        the far clip plane distance used when rendering the scene
     */
    public static void depth(int depthTexId, float near, float far) {
        CgDebugBlit inst = instance();
            try (CgGlScope scope = CgGlState.save(CgGlSlot.DEPTH, CgGlSlot.BLEND, CgGlSlot.COLOR_MASK)) {
            CgDepthState.NONE.apply();
            CgBlendState.DISABLED.apply();
            CgColorMask.ALL.apply();

            inst.emptyVao.bind();
            inst.depthShader
                    .applyBindings(b -> {
                        b.sampler("u_depthTex", 0, depthTexId);
                        b.set1f("u_near", near);
                        b.set1f("u_far", far);
                    })
                    .bind();
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            inst.depthShader.unbind();
            inst.emptyVao.unbind();
        }
    }

    /**
     * Blits an RGBA texture as-is, covering the entire viewport.
     *
     * <p>No color transformation is applied — the texture samples are written directly
     * to {@code fragColor}. Useful for visualizing color attachments, HDR render targets,
     * or any intermediate framebuffer texture.</p>
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     *
     * @param texId the GL texture object ID of the RGBA texture to display
     */
    public static void rgba(int texId) {
        CgDebugBlit inst = instance();
        try (CgGlScope scope = CgGlState.save(CgGlSlot.DEPTH, CgGlSlot.BLEND, CgGlSlot.COLOR_MASK)) {
            CgDepthState.NONE.apply();
            CgBlendState.DISABLED.apply();
            CgColorMask.ALL.apply();

            inst.emptyVao.bind();
            inst.blitShader
                    .applyBindings(b -> b.sampler("u_tex", 0, texId))
                    .bind();
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
            inst.blitShader.unbind();
            inst.emptyVao.unbind();
        }
    }

    /**
     * Releases all GPU resources owned by {@code CgDebugBlit}.
     *
     * <p>Deletes both compiled shader programs and the empty VAO. After this call the
     * singleton is cleared and the next call to {@link #depth} or {@link #rgba} will
     * reinitialize it. This method is a no-op if {@code CgDebugBlit} was never initialized.</p>
     *
     * <p>Wire this into
     * {@link io.github.somehussar.crystalgraphics.gl.lifecycle.CgGraphicsLifecycle#destroyContext()}
     * or call it from your mod's GL context teardown hook to avoid GPU resource leaks.</p>
     *
     * <p><strong>Must be called on the GL thread.</strong></p>
     */
    public static void dispose() {
        if (INSTANCE == null) return;
        INSTANCE.delete();
        INSTANCE = null;
    }

    // ── Private deletion ──────────────────────────────────────────────────────

    /**
     * Deletes the two owned shaders and the empty VAO.
     */
    private void delete() {
        depthShader.delete();
        blitShader.delete();
        emptyVao.delete();
    }
}
