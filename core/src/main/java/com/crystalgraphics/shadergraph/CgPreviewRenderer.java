package com.crystalgraphics.shadergraph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.render.CgFrameData;
import com.crystalgraphics.api.render.CgRenderPipeline;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renders node thumbnails: one subgraph, one small target, one draw.
 *
 * <h3>There is no second pipeline here</h3>
 * <p>{@link CgRenderPipeline#prepareFrame()} already exists for exactly this — its own docs describe it as
 * for "manual-bind scenes that call {@code CgMaterial.bind()} directly". It uploads the frame UBO and
 * binds both engine buffers without sorting or dispatching anything, which is the whole of what a preview
 * needs from the pipeline.</p>
 *
 * <h3>The shared frame data is borrowed, not owned — and must be put back</h3>
 * <p>{@code prepareFrame()} reads the pipeline's single {@link CgFrameData}. A preview needs its own tiny
 * camera and viewport, so it writes into that shared object and <b>restores every field afterwards</b>. Not
 * doing so would leave the world pass rendering through a thumbnail-sized preview camera later in the same
 * frame — a failure with no exception and no obvious cause, which is why the save/restore is unconditional
 * and wrapped in a finally.</p>
 *
 * <p>{@code timeSecs} is deliberately <em>not</em> overridden: inheriting the app's clock is what makes a
 * Time node's thumbnail animate, for free and in step with everything else.</p>
 *
 * <h3>Budget, because N nodes × a pass each is unbounded</h3>
 * <p>Only a bounded number of previews are rendered per frame, round-robin over the dirty set. A preview
 * that catches up within a few frames is indistinguishable from an instant one, and the bound is what
 * stops a fifty-node graph from costing fifty passes.</p>
 */
public final class CgPreviewRenderer {

    /** Draws per frame. Four is imperceptible at 60 Hz and bounds a large graph's cost hard. */
    public static final int DEFAULT_BUDGET = 4;

    /**
     * Edge length of a preview, matching the node's slot at {@code uiScale 2}.
     *
     * <p>Rendered at the size it is displayed, with {@link #DEFAULT_SAMPLES} handling the silhouette.
     * Supersampling instead would need a 512² target and would multiply <em>shading</em> cost too, which
     * matters when dozens of previews are live.</p>
     */
    public static final int DEFAULT_SIZE = 256;

    /** 4× coverage — the point where silhouette stair-stepping stops being visible at this size. */
    public static final int DEFAULT_SAMPLES = 4;

    /**
     * Live targets.
     *
     * <p>At 256² and 4×, one preview costs ~1 MB multisampled colour, ~1 MB multisampled depth and
     * 256 KB resolved. A node is ~130px tall, so a viewport realistically shows a dozen or two — the cap
     * bounds the pathological case, not the normal one.</p>
     */
    public static final int DEFAULT_CAPACITY = 24;

    private final int previewSize;
    /** Keep/reuse/evict, held separately so the policy is testable without a GL context. */
    private final CgPreviewSlots<CgPreviewTarget> targets;
    private int budget = DEFAULT_BUDGET;

    /** nodeId → the source its target was last drawn with, so an unchanged graph re-renders nothing. */
    private final Map<String, String> renderedSource = new LinkedHashMap<>();
    /** nodeId → the target that source was drawn INTO, so a recycled target forces a redraw. */
    private final Map<String, CgPreviewTarget> renderedTarget = new LinkedHashMap<>();
    /** nodeId → the RESOLVED (never {@code INHERIT}) geometry its texture was last drawn on — see
     * {@link #geometryOf}. */
    private final Map<String, CgPreviewGeometry> renderedGeometry = new LinkedHashMap<>();
    /** Insertion-ordered, so the round-robin is fair rather than dependent on hashing. */
    private final Set<String> dirty = new LinkedHashSet<>();

    /**
     * Nodes whose last-emitted source reads the live frame clock (a {@code Time} node feeding them,
     * directly or through the graph) — see {@link #render}.
     *
     * <p><b>The source-equality cache and a live uniform are fundamentally incompatible.</b> Two frames
     * of a {@code Time}-driven subgraph compile to the SAME GLSL text — the source reads {@code CG_TIME},
     * it never bakes in a value — so an unchanged-source check correctly says "nothing to redraw" every
     * single time, and the thumbnail freezes at whatever instant it last happened to draw. Rewiring the
     * node gave it a genuinely different source once (a real cache miss, so it drew once), which is why
     * the symptom looked like "a new static colour per rewire" rather than "never animates" — it never
     * animates, rewiring just forces the one redraw that made that visible.</p>
     *
     * <p>Re-added to {@code dirty} on every {@link #renderPending} call, and its redraw in {@link #render}
     * bypasses the source-equality check outright rather than trying to diff two draws of a moving
     * target.</p>
     */
    private final Set<String> animated = new LinkedHashSet<>();

    /**
     * Nodes that could not be drawn, and must therefore not be retried every frame.
     *
     * <p><b>This is the difference between a preview system and a hang.</b> A node that fails is never
     * recorded as rendered, so without this {@link #setVisible} re-dirties it on the very next frame and
     * {@code renderPending} tries it again — forever. The Output node hits it permanently by design (it
     * has no output port, so it can never be previewed), and any node whose material fails to compile
     * would retry a SHADER COMPILE every frame, which is slow enough to look like a freeze.</p>
     *
     * <p>Cleared by {@link #invalidate}, so fixing whatever was wrong lets it try again.</p>
     */
    private final Set<String> failed = new LinkedHashSet<>();

    private CgMesh quadMesh;
    private CgMesh sphereMesh;
    private boolean deleted;

    /** Scratch, reused every draw — a preview must not allocate per frame. */
    private final Matrix4f identity = new Matrix4f();
    private final CgFrameData saved = new CgFrameData();

    public CgPreviewRenderer() {
        this(DEFAULT_SIZE, DEFAULT_CAPACITY, DEFAULT_SAMPLES);
    }

    public CgPreviewRenderer(int size, int capacity, int samples) {
        if (size <= 0) throw new IllegalArgumentException("Preview size must be > 0, got " + size);
        this.previewSize = size;
        this.targets = new CgPreviewSlots<>(capacity, () -> new CgPreviewTarget("cg_preview", size, samples));
    }

    /** How many previews may be drawn per {@link #renderPending}. */
    public CgPreviewRenderer setBudget(int drawsPerFrame) {
        this.budget = Math.max(1, drawsPerFrame);
        return this;
    }

    // ── What needs drawing ──────────────────────────────────────────────────

    /**
     * Marks a node's thumbnail as needing a redraw.
     *
     * <p>Cheap and idempotent — the editor may call it on every graph change without filtering.</p>
     */
    public void invalidate(String nodeId) {
        failed.remove(nodeId);
        dirty.add(nodeId);
    }

    /** Marks every node currently holding a target. Use when the whole graph changed. */
    public void invalidateAll() {
        // Failures are cleared too: whatever was wrong may be exactly what just changed.
        failed.clear();
        dirty.addAll(renderedSource.keySet());
    }

    /**
     * Marks every visible node for redraw and releases the targets of everything else.
     *
     * <p><b>The cull set is the render set.</b> {@code CanvasView} already knows what is on screen, so a
     * preview never decides visibility for itself — and a node scrolled out of view stops costing
     * anything at all rather than merely being drawn where nobody looks.</p>
     */
    public void setVisible(Set<String> visibleNodeIds) {
        targets.retainOnly(visibleNodeIds);
        renderedSource.keySet().retainAll(visibleNodeIds);
        renderedTarget.keySet().retainAll(visibleNodeIds);
        renderedGeometry.keySet().retainAll(visibleNodeIds);
        dirty.retainAll(visibleNodeIds);
        animated.retainAll(visibleNodeIds);

        // A visible node that has never been drawn needs a first pass, and THIS is the only place that
        // can notice. invalidateAll() re-marks what has already been rendered, so on a cold start it
        // marks nothing at all — every node was in exactly that state, the dirty set stayed empty, and
        // renderPending early-returned forever. The symptom was every thumbnail blank with no error
        // anywhere, because nothing had failed: nothing had been asked for.
        for (String nodeId : visibleNodeIds) {
            // `failed` is what stops this from being an unbounded retry: a node that cannot be drawn is
            // never recorded as rendered, so without the check it comes back dirty on every single frame.
            if (!renderedSource.containsKey(nodeId) && !failed.contains(nodeId)) dirty.add(nodeId);
        }
    }

    /** Whether anything is waiting to be drawn — lets a caller skip the GL scope entirely. */
    public boolean hasPending() {
        return !dirty.isEmpty() || !animated.isEmpty();
    }

    // ── Drawing ─────────────────────────────────────────────────────────────

    /**
     * Draws up to {@link #setBudget the budget} of the pending previews.
     *
     * <p><b>GL thread, inside a live context.</b> Returns how many were actually drawn, which is 0 in the
     * common steady state where nothing changed.</p>
     *
     * <p>{@link #animated} is folded into {@code dirty} here, every call, rather than being drawn
     * separately — a Time-fed node needs exactly the same draw {@link #render} already knows how to do,
     * just unconditionally instead of behind the source-equality check. Re-adding it here rather than
     * leaving it added once is what makes it redraw every frame instead of only its first: {@code dirty}
     * is drained by this same loop, so without this it would draw once (when discovered) and then sit
     * empty again exactly like any other node whose source stopped changing.</p>
     *
     * @param graph the graph the dirty node ids refer to
     * @return the number of previews rendered this call
     */
    public int renderPending(CgShaderGraph graph) {
        checkUsable();
        dirty.addAll(animated);
        if (dirty.isEmpty()) return 0;

        int drawn = 0;
        var iterator = dirty.iterator();
        while (iterator.hasNext() && drawn < budget) {
            String nodeId = iterator.next();
            iterator.remove();
            if (render(graph, nodeId) != null) drawn++;
        }
        return drawn;
    }

    /**
     * Draws one node's preview immediately, ignoring the budget.
     *
     * @return the texture, or null when the node cannot be previewed at all
     */
    @Nullable
    public CgTexture render(CgShaderGraph graph, String nodeId) {
        checkUsable();
        CgPreviewEmitter.Result emitted = CgPreviewEmitter.emit(graph, nodeId);
        if (!emitted.ok()) {
            failed.add(nodeId);
            animated.remove(nodeId);
            return null;
        }

        // Tracked from the compiler's own answer, not guessed here — see CgShaderNode.isAnimated() and
        // the field doc on `animated` above. Kept up to date on every draw, in either direction: a node
        // rewired to no longer depend on Time goes back to costing nothing, the same as it would if it
        // had never been animated at all.
        if (emitted.animated()) animated.add(nodeId);
        else animated.remove(nodeId);

        CgPreviewTarget target = targets.acquire(nodeId);

        // Two conditions, and BOTH are needed — UNLESS the node is animated, in which case its picture
        // changes every frame with the source staying byte-identical (it names a uniform, it never bakes
        // in a value), so the equality check would forever say "nothing changed" about something that
        // never stops changing. See the `animated` field doc.
        //
        // Identical source means an identical picture, which is what makes a graph that is merely being
        // panned around cost nothing. But the target must also be the same object we last drew into:
        // a pooled target can be recycled to another node and handed back later, and it then holds that
        // node's picture. Testing only the source would leave the thumbnail showing a DIFFERENT node's
        // output — plausible, silent, and impossible to attribute to pooling by looking at it.
        boolean sameSource = emitted.source().equals(renderedSource.get(nodeId));
        boolean sameTarget = target == renderedTarget.get(nodeId);
        if (sameSource && sameTarget && !emitted.animated()) return target.texture();

        try {
            CgMaterial material = CgMaterial.fromSource(emitted.source());
            drawInto(target, material, emitted.geometry());
        } catch (RuntimeException broken) {
            // Recorded as failed rather than rethrown: a preview is a convenience, and one node whose
            // material will not compile must not take the editor down — nor be retried, which would mean
            // a shader compile every frame.
            failed.add(nodeId);
            return null;
        }
        renderedSource.put(nodeId, emitted.source());
        renderedTarget.put(nodeId, target);
        renderedGeometry.put(nodeId, emitted.geometry());
        return target.texture();
    }

    /** The texture already rendered for a node, or null. Never draws. */
    @Nullable
    public CgTexture textureOf(String nodeId) {
        CgPreviewTarget target = targets.peek(nodeId);
        return target == null ? null : target.texture();
    }

    /**
     * The RESOLVED geometry — never {@link CgPreviewGeometry#INHERIT} — the node's current texture was
     * drawn on, or {@code null} before it has ever been rendered.
     *
     * <p>Exists so a consumer painting the texture into a UI slot can match the fit to the shape: a
     * sphere needs a square, aspect-preserving letterbox or it reads as an ellipse, while a flat quad
     * has no such constraint and should simply fill whatever rectangle it is given. Reading it back here
     * — the same "already resolved once, don't resolve it twice" reasoning {@link #render} itself
     * applies to the source string — is cheaper and less error-prone than a caller re-running
     * {@link CgPreviewGeometry#resolve} against its own copy of the graph.</p>
     */
    @Nullable
    public CgPreviewGeometry geometryOf(String nodeId) {
        return renderedGeometry.get(nodeId);
    }

    private void drawInto(CgPreviewTarget target, CgMaterial material, CgPreviewGeometry geometry) {
        CgRenderPipeline pipeline = CgRenderPipeline.getInstance();
        CgFrameData frame = pipeline.getFrameData();
        copyCamera(frame, saved);

        try (CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM, CgGlSlot.VIEWPORT,
                CgGlSlot.DEPTH, CgGlSlot.BLEND, CgGlSlot.CULL, CgGlSlot.VERTEX_INPUT,
                CgGlSlot.TEXTURES)) {

            applyCamera(frame, geometry);
            // Uploads the frame UBO and binds both engine buffers. The single reason this class does not
            // need a pipeline of its own.
            pipeline.prepareFrame();
            writeObjectRecord(pipeline.objectBuffer());

            target.drawTarget().bind();
            CgGL.glViewport(0, 0, previewSize, previewSize);
            // Cleared to transparent, not to a colour: the thumbnail is composited into the node's
            // rounded preview region, and any opaque clear would show as a square behind it.
            CgGL.glClearColor(0f, 0f, 0f, 0f);
            CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT | CgGL.GL_DEPTH_BUFFER_BIT);

            CgDepthState.TEST_WRITE.apply();
            CgBlendState.DISABLED.apply();

            CgMesh mesh = meshFor(geometry);
            material.drawChain(() -> mesh.drawInstanced(1));

            target.drawTarget().unbind();
            // The multisample resolve. Without it the readable texture is never written and every
            // thumbnail stays empty — the multisampled buffer holds the picture, and nothing can sample
            // it directly.
            target.resolve();
        } finally {
            // Unconditional: leaving the world pass on the preview camera is a failure with
            // no exception and no obvious cause.
            copyCamera(saved, frame);
        }
    }

    /**
     * The preview camera.
     *
     * <p>A quad is drawn in clip space directly — identity view and projection, with the mesh spanning
     * -1..1 — so it exactly fills the target with no fitting maths to get wrong. A sphere gets a slightly
     * wider orthographic box so the silhouette is not clipped at the edges.</p>
     */
    private void applyCamera(CgFrameData frame, CgPreviewGeometry geometry) {
        frame.viewMatrix.identity();
        if (geometry == CgPreviewGeometry.SPHERE) {
            frame.projMatrix.setOrtho(-1.15f, 1.15f, -1.15f, 1.15f, -4f, 4f);
        } else {
            frame.projMatrix.identity();
        }
        frame.viewportW = previewSize;
        frame.viewportH = previewSize;
        frame.deriveFromViewMatrix();
    }

    private static void copyCamera(CgFrameData from, CgFrameData to) {
        to.viewMatrix.set(from.viewMatrix);
        to.projMatrix.set(from.projMatrix);
        to.viewportW = from.viewportW;
        to.viewportH = from.viewportH;
    }

    /**
     * One identity instance.
     *
     * <p>Every field of {@code OBJECT_FORMAT} is written, not just the two that matter: the record is a
     * fixed stride, so a short write leaves the next instance reading this one's tail.</p>
     */
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

    /** Built lazily and shared by every preview — two meshes for the whole editor. */
    private CgMesh meshFor(CgPreviewGeometry geometry) {
        if (geometry == CgPreviewGeometry.SPHERE) {
            if (sphereMesh == null) {
                sphereMesh = CgMesh.upload(CgMeshBuilder.uvSphere(CgVertexFormat.SPATIAL, 24, 32, 1f));
            }
            return sphereMesh;
        }
        if (quadMesh == null) {
            CgMeshData data = CgMeshBuilder.quad2D(CgVertexFormat.SPATIAL, -1f, -1f, 1f, 1f);
            quadMesh = CgMesh.upload(data);
        }
        return quadMesh;
    }

    /**
     * Frees the pool and both meshes.
     *
     * <p>Must be called on context destruction — the pool's targets are {@code createOwned}, so no
     * registry sweep reaches them.</p>
     */
    public void delete() {
        if (deleted) return;
        for (CgPreviewTarget target : targets.all()) target.delete();
        targets.clear();
        if (quadMesh != null) quadMesh.delete();
        if (sphereMesh != null) sphereMesh.delete();
        quadMesh = null;
        sphereMesh = null;
        renderedSource.clear();
        renderedTarget.clear();
        renderedGeometry.clear();
        dirty.clear();
        animated.clear();
        deleted = true;
    }

    private void checkUsable() {
        if (deleted) throw new IllegalStateException("This CgPreviewRenderer has been deleted");
    }
}
