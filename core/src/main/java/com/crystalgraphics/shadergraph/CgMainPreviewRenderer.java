package com.crystalgraphics.shadergraph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.EnumMap;
import java.util.Map;

/**
 * The main preview: the <b>finished shader</b> on a mesh you can turn.
 *
 * <h3>Why this compiles the real emitter, not {@link CgPreviewEmitter}</h3>
 * <p>A node thumbnail asks "what value does this node hold?", so {@code CgPreviewEmitter} wraps one
 * output into a colour. This panel asks a different question — "what will this material look like?" — and
 * the honest answer is the file {@link CgShaderEmitter} actually produces. Going through the preview
 * wrapper would silently miss everything that is not the colour chain: a graph displacing
 * {@code Position} in the vertex stage would draw un-deformed, which is most of the reason to want a mesh
 * under it at all.</p>
 *
 * <h3>It is deliberately unlit</h3>
 * <p>Unity's preview ball is shaded. Ours cannot be, and the fact is the same one that keeps Metallic and
 * Smoothness off the Fragment block: {@code CgFrameBlock} carries no light term. Faking a headlight here
 * would be the worst available outcome — it looks the most finished, and it would have someone tuning a
 * shader against shading the pipeline cannot produce. A graph whose output varies with UV, position or
 * normal reads perfectly unlit; a constant colour renders flat, and a constant colour has nothing to show
 * anyway. When lighting lands, this and those ports come back together.</p>
 *
 * <h3>Orbit is a camera move, not an object move</h3>
 * <p>The rotation goes into the <b>view</b> matrix and the model matrix stays identity. Rotating the
 * object instead would drag object space around with it, so a graph reading {@code Position} in object
 * space would change what it draws as you turned it — which is exactly the coordinate frame a user turns
 * the mesh in order to inspect.</p>
 */
public final class CgMainPreviewRenderer {

    /** Big enough to judge a material on, small enough to redraw without thinking about it. */
    public static final int DEFAULT_SIZE = 320;

    /** Matches {@code CgPreviewRenderer}: a silhouette at this size stair-steps badly without it. */
    public static final int DEFAULT_SAMPLES = 4;

    private final int size;
    private final int samples;

    @Nullable
    private CgPreviewTarget target;

    private final Map<CgPreviewMesh, CgMesh> meshes = new EnumMap<>(CgPreviewMesh.class);
    private final Matrix4f identity = new Matrix4f();
    private final CgFrameData saved = new CgFrameData();

    /** What produced the picture currently in {@link #target}. @see #render */
    @Nullable
    private String renderedSource;
    @Nullable
    private CgPreviewMesh renderedMesh;
    private float renderedYaw = Float.NaN;
    private float renderedPitch = Float.NaN;
    private float renderedZoom = Float.NaN;
    private boolean renderedAnimated;

    private boolean failed;
    private boolean deleted;

    public CgMainPreviewRenderer() {
        this(DEFAULT_SIZE, DEFAULT_SAMPLES);
    }

    public CgMainPreviewRenderer(int size, int samples) {
        this.size = Math.max(1, size);
        this.samples = Math.max(1, samples);
    }

    public int size() {
        return size;
    }

    /** True when the last compile failed. The panel shows its last good picture rather than nothing. */
    public boolean hasFailed() {
        return failed;
    }

    /**
     * Draws {@code graph} on {@code mesh} and returns the texture, or null if it would not compile.
     *
     * <p><b>Redraws only when something actually changed.</b> Identical source, mesh and orientation mean
     * an identical picture, so a graph merely being panned around costs nothing — the same argument
     * {@code CgPreviewRenderer} makes per thumbnail, and it matters more here because this target is
     * larger than all of them.</p>
     *
     * @param yaw   orbit around the Y axis, radians
     * @param pitch orbit around the X axis, radians
     */
    @Nullable
    public CgTexture render(CgShaderGraph graph, CgMasterNode master, CgPreviewMesh mesh,
                            float yaw, float pitch) {
        return render(graph, master, mesh, yaw, pitch, 1f);
    }

    /**
     * As above, with a zoom factor.
     *
     * @param zoom above 1 moves closer. Applied by DIVIDING the orthographic half-extent, so it reads as
     *             a camera move rather than as the object changing size — which is the same distinction
     *             the orbit makes, and keeping the two consistent is what stops the panel feeling like
     *             two unrelated gestures.
     */
    @Nullable
    public CgTexture render(CgShaderGraph graph, CgMasterNode master, CgPreviewMesh mesh,
                            float yaw, float pitch, float zoom) {
        return render(graph, master, mesh, yaw, pitch, zoom, true);
    }

    /**
     * As above, choosing whether the preview lights its own output.
     *
     * @param lit viewport shading, <b>not</b> a lighting model — see {@link CgShaderEmitter.Shading}.
     *            Unlit is what the material actually draws in game, and is the mode to check against when
     *            the colour matters more than the form.
     */
    @Nullable
    public CgTexture render(CgShaderGraph graph, CgMasterNode master, CgPreviewMesh mesh,
                            float yaw, float pitch, float zoom, boolean lit) {
        if (deleted) throw new IllegalStateException("This CgMainPreviewRenderer has been deleted");

        CgShaderEmitter.Result emitted = CgShaderEmitter.emit(graph, master,
                lit ? CgShaderEmitter.Shading.PREVIEW_LIT : CgShaderEmitter.Shading.UNLIT);
        if (!emitted.ok()) {
            failed = true;
            return currentTexture();
        }
        failed = false;

        boolean unchanged = emitted.source().equals(renderedSource)
                && mesh == renderedMesh
                && yaw == renderedYaw
                && pitch == renderedPitch
                && zoom == renderedZoom
                && target != null;
        // An animated graph names a uniform rather than baking a value, so its source is byte-identical
        // frame to frame while its picture is not. Same carve-out CgPreviewRenderer documents.
        if (unchanged && !renderedAnimated) return target.texture();

        if (target == null) target = new CgPreviewTarget("cg_main_preview", size, samples);

        try {
            CgMaterial material = CgMaterial.fromSource(emitted.source());
            drawInto(material, mesh, yaw, pitch, zoom);
        } catch (RuntimeException broken) {
            // A preview is a convenience. One material that will not compile must not take the editor
            // down, nor be retried every frame — which is what rethrowing here would amount to.
            failed = true;
            return currentTexture();
        }

        renderedSource = emitted.source();
        renderedMesh = mesh;
        renderedYaw = yaw;
        renderedPitch = pitch;
        renderedZoom = zoom;
        renderedAnimated = isAnimated(graph);
        return target.texture();
    }

    /** The last picture drawn, without drawing. Null before the first successful render. */
    @Nullable
    public CgTexture currentTexture() {
        return target == null ? null : target.texture();
    }

    /** Whether anything in the graph redraws on its own — {@code Time} and friends. */
    private static boolean isAnimated(CgShaderGraph graph) {
        for (CgShaderGraph.Instance instance : graph.instances()) {
            if (instance.type().isAnimated()) return true;
        }
        return false;
    }

    private void drawInto(CgMaterial material, CgPreviewMesh mesh, float yaw, float pitch,
                          float zoom) {
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        CgFrameData frame = pipeline.getFrameData();
        copyCamera(frame, saved);

        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM, CgGlSlot.VIEWPORT,
                CgGlSlot.DEPTH, CgGlSlot.BLEND, CgGlSlot.CULL, CgGlSlot.VERTEX_INPUT,
                CgGlSlot.TEXTURES)) {

            applyCamera(frame, mesh, yaw, pitch, zoom);
            pipeline.prepareFrame();
            writeObjectRecord(pipeline.objectBuffer());

            target.drawTarget().bind();
            CgGL.glViewport(0, 0, size, size);
            // Transparent, so the panel's own backdrop shows through — which is what makes a graph's
            // Alpha visible at all against a checkerboard rather than against a colour we chose.
            CgGL.glClearColor(0f, 0f, 0f, 0f);
            CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT | CgGL.GL_DEPTH_BUFFER_BIT);

            CgDepthState.TEST_WRITE.apply();
            // Blending ON, unlike a node thumbnail: Alpha is a real master port now, and an opaque
            // preview of a transparent material is a preview of something else.
            CgBlendState.ALPHA.apply();

            CgMesh uploaded = meshFor(mesh);
            material.drawChain(() -> uploaded.drawInstanced(1));

            target.drawTarget().unbind();
            // The multisample resolve. Without it the readable texture is never written and the panel
            // stays empty — the multisampled buffer holds the picture and nothing can sample it.
            target.resolve();
        } finally {
            // Unconditional: leaving the world pass on the preview camera is a failure with no exception
            // and no obvious cause.
            copyCamera(saved, frame);
        }
    }

    /**
     * An orthographic camera orbited by {@code yaw}/{@code pitch}.
     *
     * <p>Orthographic rather than perspective on purpose: a perspective preview makes the same material
     * look different depending on how near the camera was put, and there is no scene here to give that
     * distance any meaning.</p>
     */
    private void applyCamera(CgFrameData frame, CgPreviewMesh mesh, float yaw, float pitch,
                             float zoom) {
        frame.viewMatrix.identity().rotateX(pitch).rotateY(yaw);
        // Clamped rather than trusted: a zero or negative zoom collapses the ortho box and the driver
        // draws nothing at all, which reads as "the shader broke" rather than "the gesture went wrong".
        float r = mesh.viewRadius() / Math.max(0.05f, zoom);
        // Depth range spans well past the shape in both directions: the view rotation moves geometry
        // through Z, and a box fitted to the un-rotated extent would clip a corner into view as it turned.
        frame.projMatrix.setOrtho(-r, r, -r, r, -4f, 4f);
        frame.viewportW = size;
        frame.viewportH = size;
        frame.deriveFromViewMatrix();
    }

    private static void copyCamera(CgFrameData from, CgFrameData to) {
        to.viewMatrix.set(from.viewMatrix);
        to.projMatrix.set(from.projMatrix);
        to.viewportW = from.viewportW;
        to.viewportH = from.viewportH;
    }

    /** One identity instance. Every field is written — the record is a fixed stride, so a short write
     * leaves the next instance reading this one's tail. */
    private void writeObjectRecord(CgShaderBuffer objectBuffer) {
        CgBufferWriter writer = objectBuffer.beginWrite(1);
        writer.beginRecord()
                .mat4("modelMatrix", identity)
                .mat4("normalMatrix", identity)
                .vec4("custom0", 0f, 0f, 0f, 0f)
                .vec4("custom1", 0f, 0f, 0f, 0f)
                .vec4("custom2", 0f, 0f, 0f, 0f)
                .vec4("custom3", 0f, 0f, 0f, 0f);
        objectBuffer.endRecord();
        objectBuffer.endWrite();
    }

    /** Built on first use and kept: switching back to a shape must not re-upload it. */
    private CgMesh meshFor(CgPreviewMesh mesh) {
        return meshes.computeIfAbsent(mesh, m -> CgMesh.upload(m.build(CgVertexFormat.SPATIAL)));
    }

    /**
     * Frees the target and every mesh built so far.
     *
     * <p>Must be called on context destruction: the target is {@code createOwned}, so no registry sweep
     * reaches it.</p>
     */
    public void delete() {
        if (deleted) return;
        if (target != null) {
            target.delete();
            target = null;
        }
        for (CgMesh mesh : meshes.values()) mesh.delete();
        meshes.clear();
        renderedSource = null;
        renderedMesh = null;
        deleted = true;
    }
}
