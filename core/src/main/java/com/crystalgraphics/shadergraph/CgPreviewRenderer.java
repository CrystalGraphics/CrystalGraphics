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
import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * <p>Its time is {@code CgFrameClock}'s, the one every pass reads: what makes a Time node's thumbnail animate,
 * in step with everything else.</p>
 *
 * <h3>Budget, because N nodes × a pass each is unbounded</h3>
 * <p>Only a bounded number of previews are rendered per frame, round-robin over the dirty set. A preview
 * that catches up within a few frames is indistinguishable from an instant one, and the bound is what
 * stops a fifty-node graph from costing fifty passes.</p>
 */
public final class CgPreviewRenderer {

    /** Draws per frame. Four is imperceptible at 60 Hz and bounds a large graph's cost hard. */
    public static final int DEFAULT_BUDGET = 4;

    private static final int GPU_DRAW = CgGpuTrace.name("preview.draw");

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
    /**
     * Keep/reuse/evict — <b>shared with every other renderer in this context</b>, see {@link CgPreviewPool}.
     *
     * <p>Held separately from the GL so the policy stays testable without a context, which is why it was
     * a separate class to begin with. What changed is who owns it: a pool per renderer meant memory
     * scaled with how many graphs were <em>open</em> rather than with how many were <em>visible</em>, and
     * closing one deleted framebuffers that reopening it immediately re-created.</p>
     */
    private final CgPreviewSlots<CgPreviewTarget> targets;

    /** This renderer's key namespace in the shared pool. Every key below is {@code scope + nodeId}. */
    private final String scope = CgPreviewPool.newScope();
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

    /** The last {@link #setVisible}, held so a frame can ask of it without being sent it again. */
    private final List<String> visibleIds = new ArrayList<>();

    /** nodeId → its key in the shared pool, built once: a render and a paint each ask, per node per frame. */
    private final Map<String, String> poolKeys = new LinkedHashMap<>();

    private String poolKey(String nodeId) {
        String key = poolKeys.get(nodeId);
        if (key == null) {
            key = scope + nodeId;
            poolKeys.put(nodeId, key);
        }
        return key;
    }

    /**
     * nodeId → the material drawing it, kept while its source is. A material owns a property UBO, and a
     * {@code Time}-fed node draws every frame: one built per draw was a GL buffer per node per frame that
     * nothing deleted.
     */
    private final Map<String, HeldMaterial> materials = new LinkedHashMap<>();

    /** nodeId → the material for its new source, while the driver compiles it; the old one keeps drawing. */
    private final Map<String, HeldMaterial> compilingMaterials = new LinkedHashMap<>();

    /** Nodes waiting on {@link #compilingMaterials}: asked again every {@link #renderPending}, like {@link #animated}. */
    private final Set<String> compiling = new LinkedHashSet<>();

    private record HeldMaterial(String source, CgMaterial material) {
    }

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

    /** Why each failed node failed — the emitter's problems, kept beside the ids. */
    private final Map<String, List<CgShaderProblem>> failureReasons = new LinkedHashMap<>();

    /**
     * The nodes whose thumbnail could not be produced, and why.
     *
     * <p>This class already knew both — {@code failed} is what stops it retrying forever, and the emitter
     * handed over the problems on the way in — and told nobody. A node whose preview cannot compile simply
     * showed nothing, permanently, with the explanation discarded one line after it arrived.</p>
     */
    public Map<String, List<CgShaderProblem>> failures() {
        return Map.copyOf(failureReasons);
    }

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
        this.targets = CgPreviewPool.forGeometry(size, samples, capacity);
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
        failureReasons.remove(nodeId);
        emittedPreviews.remove(nodeId);
        dirty.add(nodeId);
    }

    /** Marks every node currently holding a target. Use when the whole graph changed. */
    public void invalidateAll() {
        // Failures are cleared too: whatever was wrong may be exactly what just changed.
        failed.clear();
        failureReasons.clear();
        emittedPreviews.clear();
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
        visibleIds.clear();
        visibleIds.addAll(visibleNodeIds);
        Set<String> keep = new LinkedHashSet<>();
        poolKeys.keySet().retainAll(visibleNodeIds);
        for (String visible : visibleNodeIds) keep.add(poolKey(visible));
        // WITHIN this renderer's namespace. The pool is shared, so an unscoped cull would release the
        // targets of every other open graph -- which would look like thumbnails going blank in a tab
        // nobody had touched.
        targets.retainOnlyWithin(scope, keep);
        renderedSource.keySet().retainAll(visibleNodeIds);
        renderedTarget.keySet().retainAll(visibleNodeIds);
        renderedGeometry.keySet().retainAll(visibleNodeIds);
        // THE MATERIALS AND THE EMITTED SOURCE STAY. A culled node is still in the graph, and dropping its program
        // made scrolling it back into view a recompile, whose link landed on the frame a couple of seconds later.
        // Only a node that has left the graph gives them up. @see #retainNodes
        compiling.retainAll(visibleNodeIds);
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

    /**
     * Drops what is held for nodes no longer in the graph: their materials and emitted source, which
     * {@link #setVisible} keeps for a node that is merely off screen.
     *
     * <pre>{@code
     * renderer.retainNodes(allNodeIds);   // when nodes were added or removed
     * renderer.setVisible(onScreenIds);   // when that, or what is on screen, changed
     * }</pre>
     */
    public void retainNodes(Set<String> nodeIds) {
        emittedPreviews.keySet().retainAll(nodeIds);
        dropMaterialsOutside(materials, nodeIds);
        dropMaterialsOutside(compilingMaterials, nodeIds);
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
        dirty.addAll(compiling);
        compiling.clear();
        // A visible node that has never been drawn needs a first pass, asked here rather than only in setVisible so
        // a caller need not re-send an unchanged set to get it -- invalidateAll clears a failure without re-marking.
        for (int i = 0; i < visibleIds.size(); i++) {
            String nodeId = visibleIds.get(i);
            if (!renderedSource.containsKey(nodeId) && !failed.contains(nodeId)) dirty.add(nodeId);
        }
        CgTrace.counter(CgChannels.SHADERGRAPH, "preview.animated", animated.size());
        CgTrace.counter(CgChannels.SHADERGRAPH, "preview.dirty", dirty.size());
        if (dirty.isEmpty()) return 0;

        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.SHADERGRAPH, "preview.renderPending")) {
            int drawn = 0;
            var iterator = dirty.iterator();
            while (iterator.hasNext() && drawn < budget) {
                String nodeId = iterator.next();
                iterator.remove();
                if (render(graph, nodeId) != null) drawn++;
            }
            return drawn;
        }
    }

    /**
     * Draws one node's preview immediately, ignoring the budget.
     *
     * @return the texture, or null when the node cannot be previewed at all
     */
    @Nullable
    public CgTexture render(CgShaderGraph graph, String nodeId) {
        checkUsable();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.SHADERGRAPH, "preview.render")) {
            return renderTraced(graph, nodeId);
        }
    }

    @Nullable
    private CgTexture renderTraced(CgShaderGraph graph, String nodeId) {
        CgPreviewEmitter.Result emitted = emitted(graph, nodeId);
        if (!emitted.ok()) {
            failed.add(nodeId);
            failureReasons.put(nodeId, List.copyOf(emitted.problems()));
            animated.remove(nodeId);
            return null;
        }

        // Tracked from the compiler's own answer, not guessed here — see CgShaderNode.isAnimated() and
        // the field doc on `animated` above. Kept up to date on every draw, in either direction: a node
        // rewired to no longer depend on Time goes back to costing nothing, the same as it would if it
        // had never been animated at all.
        if (emitted.animated()) animated.add(nodeId);
        else animated.remove(nodeId);

        CgPreviewTarget target = targets.acquire(poolKey(nodeId));

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
        if (sameSource && sameTarget && !emitted.animated()) {
            CgTrace.add(CgChannels.SHADERGRAPH, "preview.unchanged", 1);
            return target.texture();
        }

        try {
            CgMaterial material = readyMaterial(nodeId, emitted.source());
            if (material == null) {
                // STILL COMPILING, and the frame does not wait for it. The target keeps the last picture; a
                // Time-fed node goes on moving on the material it had.
                compiling.add(nodeId);
                CgTrace.add(CgChannels.SHADERGRAPH, "preview.compiling", 1);
                HeldMaterial old = materials.get(nodeId);
                if (old == null || target != renderedTarget.get(nodeId)) return null;
                if (emitted.animated()) drawInto(target, old.material(), emitted.geometry());
                return target.texture();
            }
            // The DRIVER's refusal, which the emitter cannot predict. Asked before the draw, since a bind of a
            // failed material compiles it again.
            String driver = material.lastCompileError();
            if (driver == null) {
                CgTrace.add(CgChannels.SHADERGRAPH, emitted.animated() ? "preview.draw.animated" : "preview.draw.changed", 1);
                drawInto(target, material, emitted.geometry());
                driver = material.lastCompileError();
            }
            if (driver != null) {
                dropMaterial(nodeId);
                failed.add(nodeId);
                failureReasons.put(nodeId, List.of(CgShaderProblem.node(nodeId, driver)));
                return null;
            }
        } catch (RuntimeException broken) {
            // Recorded as failed rather than rethrown: a preview is a convenience, and one node whose
            // material will not compile must not take the editor down — nor be retried, which would mean
            // a shader compile every frame.
            dropMaterial(nodeId);
            failed.add(nodeId);
            failureReasons.put(nodeId, List.of(CgShaderProblem.node(nodeId,
                    broken.getMessage() == null ? broken.toString() : broken.getMessage())));
            return null;
        }
        renderedSource.put(nodeId, emitted.source());
        renderedTarget.put(nodeId, target);
        renderedGeometry.put(nodeId, emitted.geometry());
        return target.texture();
    }

    /**
     * The node's emitted preview, reused while it is asked of the same graph. The emit is a pure function of the
     * graph, and a Time-fed node asks every frame of a graph that only changes on an edit: the whole compile, per
     * node per frame, was most of what an idle graph allocated.
     */
    private CgPreviewEmitter.Result emitted(CgShaderGraph graph, String nodeId) {
        EmittedPreview held = emittedPreviews.get(nodeId);
        if (held != null && held.graph() == graph) return held.result();
        CgPreviewEmitter.Result result = CgPreviewEmitter.emit(graph, nodeId);
        emittedPreviews.put(nodeId, new EmittedPreview(graph, result));
        return result;
    }

    private record EmittedPreview(CgShaderGraph graph, CgPreviewEmitter.Result result) {
    }

    private final Map<String, EmittedPreview> emittedPreviews = new LinkedHashMap<>();

    /**
     * The node's material for {@code source}, or null while the driver is still compiling it. A new source starts
     * a compile the frame does not wait for; the held material is replaced only once that one is ready.
     */
    @Nullable
    private CgMaterial readyMaterial(String nodeId, String source) {
        HeldMaterial held = materials.get(nodeId);
        if (held != null && held.source().equals(source)) return held.material();
        HeldMaterial next = compilingMaterials.get(nodeId);
        if (next == null || !next.source().equals(source)) {
            if (next != null) next.material().delete();
            next = new HeldMaterial(source, CgMaterial.fromSource(source));
            compilingMaterials.put(nodeId, next);
            CgTrace.add(CgChannels.SHADERGRAPH, "preview.materials.created", 1);
        }
        if (!next.material().prepare()) return null;
        compilingMaterials.remove(nodeId);
        if (held != null) held.material().delete();
        materials.put(nodeId, next);
        return next.material();
    }

    private void dropMaterial(String nodeId) {
        HeldMaterial held = materials.remove(nodeId);
        if (held != null) held.material().delete();
        HeldMaterial next = compilingMaterials.remove(nodeId);
        if (next != null) next.material().delete();
    }

    private static void dropMaterialsOutside(Map<String, HeldMaterial> held, Set<String> keep) {
        held.entrySet().removeIf(entry -> {
            if (keep.contains(entry.getKey())) return false;
            entry.getValue().material().delete();
            CgTrace.add(CgChannels.SHADERGRAPH, "preview.materials.dropped", 1);
            return true;
        });
    }

    /** The texture already rendered for a node, or null. Never draws. */
    @Nullable
    public CgTexture textureOf(String nodeId) {
        CgPreviewTarget target = targets.peek(poolKey(nodeId));
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

        CgGpuTrace.begin(GPU_DRAW);
        try (CgTrace.Zone traced = CgTrace.zone(CgChannels.SHADERGRAPH, "preview.draw");
             CgGlScope scope = CgGlState.save(CgGlSlot.FBO, CgGlSlot.PROGRAM, CgGlSlot.VIEWPORT,
                CgGlSlot.DEPTH, CgGlSlot.BLEND, CgGlSlot.CULL, CgGlSlot.VERTEX_INPUT,
                CgGlSlot.TEXTURES)) {

            applyCamera(frame, geometry);
            // Uploads the frame UBO and binds both engine buffers. The single reason this class does not
            // need a pipeline of its own.
            pipeline.prepareFrame();
            writeObjectRecord(pipeline.objectBuffer());

            try (CgTrace.Zone cleared = CgTrace.zone(CgChannels.SHADERGRAPH, "preview.clear")) {
                target.drawTarget().bind();
                CgGL.glViewport(0, 0, previewSize, previewSize);
                // Cleared to transparent, not to a colour: the thumbnail is composited into the node's
                // rounded preview region, and any opaque clear would show as a square behind it.
                CgGL.glClearColor(0f, 0f, 0f, 0f);
                CgGL.glClear(CgGL.GL_COLOR_BUFFER_BIT | CgGL.GL_DEPTH_BUFFER_BIT);
            }

            CgDepthState.TEST_WRITE.apply();
            CgBlendState.DISABLED.apply();

            // Timed apart: a driver that links a program lazily blocks on its first draw here.
            try (CgTrace.Zone drawn = CgTrace.zone(CgChannels.SHADERGRAPH, "preview.drawMesh")) {
                CgMesh mesh = meshFor(geometry);
                material.drawChain(() -> mesh.drawInstanced(1));
            }

            try (CgTrace.Zone resolved = CgTrace.zone(CgChannels.SHADERGRAPH, "preview.resolve")) {
                target.drawTarget().unbind();
                // The multisample resolve. Without it the readable texture is never written and every
                // thumbnail stays empty — the multisampled buffer holds the picture, and nothing can sample
                // it directly.
                target.resolve();
            }
        } finally {
            CgGpuTrace.end();
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
     *
     * <h3>Why the camera is NOT moved to the other side of the mesh, though it looks like it should be</h3>
     * <p>Unity's object-space thumbnails show the hemisphere at z &lt; 0, because Unity is <b>left-handed</b>
     * — its +Z points away from the viewer. This engine is right-handed, and the difference between the
     * two is a <b>mirror</b>, not a rotation.</p>
     *
     * <p>That is not a detail, it is the whole reason the nodes compensate in their preview bodies rather
     * than the camera compensating here. Two attempts were made to fix it at the camera and the arithmetic
     * refuses both:</p>
     * <ul>
     *   <li>Swapping the near/far arguments flips the depth mapping AND the sign of the projection's
     *       determinant — so it flips triangle winding too, back-face culling keeps the opposite set, and
     *       the two cancel. The visible hemisphere does not move at all. Measured: {@code det} goes
     *       {@code -0.0945} to {@code +0.0945} with the same picture on screen.</li>
     *   <li>A 180° rotation shows the far hemisphere with culling and depth in agreement — but it also
     *       turns the mesh around, so +X now points left. Unity's ball has red on the right.</li>
     * </ul>
     *
     * <p>There is no rotation that shows the other hemisphere while keeping +X right and +Y up. Only a
     * mirror does, and a mirror inverts winding, which is exactly why a left-handed API winds its front
     * faces the other way. So the compensation belongs where a handedness difference can be expressed
     * without touching geometry: in the value, in the preview body. See {@code CgBuiltinShaderNodes}.</p>
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
     * Gives this renderer's targets back to the pool and frees its own meshes.
     *
     * <h3>Releases; does not delete</h3>
     *
     * <p>It used to delete every target it held, which made closing a shader graph a GPU teardown and
     * reopening it a GPU allocate — driver-serialised work, on a gesture people repeat constantly. The
     * targets are owned by the <b>context</b> now ({@link CgPreviewPool}), so this hands back keys and
     * the framebuffers stay for whatever asks next.</p>
     *
     * <p>The meshes are genuinely this renderer's own and are still deleted. They are the small half.</p>
     */
    public void delete() {
        if (deleted) return;
        targets.releaseAllWithin(scope);
        if (quadMesh != null) quadMesh.delete();
        if (sphereMesh != null) sphereMesh.delete();
        quadMesh = null;
        sphereMesh = null;
        renderedSource.clear();
        renderedTarget.clear();
        renderedGeometry.clear();
        emittedPreviews.clear();
        visibleIds.clear();
        poolKeys.clear();
        for (HeldMaterial held : materials.values()) held.material().delete();
        materials.clear();
        for (HeldMaterial held : compilingMaterials.values()) held.material().delete();
        compilingMaterials.clear();
        compiling.clear();
        dirty.clear();
        animated.clear();
        deleted = true;
    }

    private void checkUsable() {
        if (deleted) throw new IllegalStateException("This CgPreviewRenderer has been deleted");
    }
}
