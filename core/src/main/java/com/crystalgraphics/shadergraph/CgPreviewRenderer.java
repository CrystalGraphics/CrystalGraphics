package com.crystalgraphics.shadergraph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMeshData;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.render.CgFrameClock;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.graph.CgRequest;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Renders node thumbnails: one subgraph, one small target, one draw. It issues no GL: each thumbnail is a raster pass
 * recorded into the recording its caller lends, which the caller's frame executes.
 *
 * <pre>{@code
 * renderer.setVisible(onScreenIds);
 * renderer.renderPending(graph, recording);         // in paint: up to the budget of passes
 * CgGraphTexture thumbnail = renderer.textureOf(nodeId);
 * }</pre>
 *
 * <h3>Its own camera, in its own pass block</h3>
 * <p>Each thumbnail's pass has a {@code CgPassConstants} of its own, so nothing another pass draws under is read.</p>
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
    private final Map<String, Compiling> compilingMaterials = new LinkedHashMap<>();

    /** Nodes waiting on {@link #compilingMaterials}: asked again every {@link #renderPending}, like {@link #animated}. */
    private final Set<String> compiling = new LinkedHashSet<>();

    private record HeldMaterial(String source, CgMaterial material) {
    }

    /** A material compiling for a node's new source, and the latest compile request recorded for it. */
    private static final class Compiling {
        final String source;
        final CgMaterial material;
        @Nullable
        CgRequest request;

        Compiling(String source, CgMaterial material) {
            this.source = source;
            this.material = material;
        }
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

    /** The preview camera: its own pass block, reused every draw. */
    private final CgPassConstants camera = new CgPassConstants();

    private static final CgRenderState PASS_STATE = CgRenderState.builder()
            .depth(CgDepthState.TEST_WRITE).blend(CgBlendState.DISABLED).build();

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
     * Records up to {@link #setBudget the budget} of the pending previews into {@code recording}.
     *
     * <p>Any thread, one at a time. Returns how many were recorded, which is 0 in the common steady state where
     * nothing changed.</p>
     *
     * <p>{@link #animated} is folded into {@code dirty} here, every call, rather than being drawn
     * separately — a Time-fed node needs exactly the same draw {@link #render} already knows how to do,
     * just unconditionally instead of behind the source-equality check. Re-adding it here rather than
     * leaving it added once is what makes it redraw every frame instead of only its first: {@code dirty}
     * is drained by this same loop, so without this it would draw once (when discovered) and then sit
     * empty again exactly like any other node whose source stopped changing.</p>
     *
     * @param graph     the graph the dirty node ids refer to
     * @param recording where the passes go; its frame executes them before anything recorded after reads them
     * @return the number of previews rendered this call
     */
    public int renderPending(CgShaderGraph graph, CgRecording recording) {
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
                if (render(graph, nodeId, recording) != null) drawn++;
            }
            return drawn;
        }
    }

    /**
     * Records one node's preview into {@code recording}, ignoring the budget.
     *
     * @return the texture, or null when the node cannot be previewed at all
     */
    @Nullable
    public CgGraphTexture render(CgShaderGraph graph, String nodeId, CgRecording recording) {
        checkUsable();
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.SHADERGRAPH, "preview.render")) {
            return renderTraced(graph, nodeId, recording);
        }
    }

    @Nullable
    private CgGraphTexture renderTraced(CgShaderGraph graph, String nodeId, CgRecording recording) {
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
            CgMaterial material = readyMaterial(nodeId, emitted.source(), recording);
            if (material == null) {
                // STILL COMPILING, and the frame does not wait for it. The target keeps the last picture; a
                // Time-fed node goes on moving on the material it had.
                compiling.add(nodeId);
                CgTrace.add(CgChannels.SHADERGRAPH, "preview.compiling", 1);
                HeldMaterial old = materials.get(nodeId);
                if (old == null || target != renderedTarget.get(nodeId)) return null;
                if (emitted.animated()) drawInto(target, old.material(), emitted.geometry(), recording);
                return target.texture();
            }
            CgTrace.add(CgChannels.SHADERGRAPH, emitted.animated() ? "preview.draw.animated" : "preview.draw.changed", 1);
            drawInto(target, material, emitted.geometry(), recording);
        } catch (RuntimeException broken) {
            // Recorded as failed rather than rethrown: a preview is a convenience, and one node whose
            // material will not compile — the emitter cannot predict the DRIVER's refusal — must not take the
            // editor down, nor be retried, which would mean a shader compile every frame.
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
     * The node's material for {@code source}, or null while the driver is still compiling it. A new source records
     * a compile the frame does not wait for, again each frame until one answers; the held material is replaced only
     * once that one is ready.
     *
     * @throws IllegalStateException when the driver refused the source
     */
    @Nullable
    private CgMaterial readyMaterial(String nodeId, String source, CgRecording recording) {
        HeldMaterial held = materials.get(nodeId);
        if (held != null && held.source().equals(source)) return held.material();
        Compiling next = compilingMaterials.get(nodeId);
        if (next == null || !next.source.equals(source)) {
            if (next != null) next.material.delete();
            next = new Compiling(source, CgMaterial.fromSource(source));
            compilingMaterials.put(nodeId, next);
            CgTrace.add(CgChannels.SHADERGRAPH, "preview.materials.created", 1);
        }
        if (next.request != null && next.request.failed()) throw new IllegalStateException(next.request.failure());
        if (next.request == null || !next.request.done()) {
            CgPipeline pipeline = next.material.pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) {
                String error = next.material.lastCompileError();
                throw new IllegalStateException(error != null ? error : "the preview shader does not parse");
            }
            // Asked again rather than waited on: the last request may not have executed yet.
            next.request = recording.compile(pipeline);
            return null;
        }
        compilingMaterials.remove(nodeId);
        if (held != null) held.material().delete();
        materials.put(nodeId, new HeldMaterial(source, next.material));
        return next.material;
    }

    private void dropMaterial(String nodeId) {
        HeldMaterial held = materials.remove(nodeId);
        if (held != null) held.material().delete();
        Compiling next = compilingMaterials.remove(nodeId);
        if (next != null) next.material.delete();
    }

    private static void dropMaterialsOutside(Map<String, ?> held, Set<String> keep) {
        held.entrySet().removeIf(entry -> {
            if (keep.contains(entry.getKey())) return false;
            Object value = entry.getValue();
            (value instanceof Compiling compiling ? compiling.material : ((HeldMaterial) value).material()).delete();
            CgTrace.add(CgChannels.SHADERGRAPH, "preview.materials.dropped", 1);
            return true;
        });
    }

    /** The texture a node's picture was drawn into, or null: never drawn, or its target since went to another. */
    @Nullable
    public CgGraphTexture textureOf(String nodeId) {
        CgPreviewTarget target = targets.peek(poolKey(nodeId));
        return target == null || target != renderedTarget.get(nodeId) ? null : target.texture();
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

    private void drawInto(CgPreviewTarget target, CgMaterial material, CgPreviewGeometry geometry,
                          CgRecording recording) {
        try (CgTrace.Zone traced = CgTrace.zone(CgChannels.SHADERGRAPH, "preview.draw")) {
            applyCamera(camera, geometry);
            CgRasterPass pass = target.begin(recording, camera, PASS_STATE);
            CgPreviewDraw.object(recording, pass, material, meshFor(geometry));
            target.end(recording, pass);
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
    private void applyCamera(CgPassConstants camera, CgPreviewGeometry geometry) {
        camera.view.identity();
        if (geometry == CgPreviewGeometry.SPHERE) {
            camera.projection.setOrtho(-1.15f, 1.15f, -1.15f, 1.15f, -4f, 4f);
        } else {
            camera.projection.identity();
        }
        camera.resolution(previewSize, previewSize).time(CgFrameClock.seconds()).cameraFromView();
    }

    /** Built lazily, uploaded by the render thread, and shared by every preview: two meshes for the whole editor. */
    private CgMesh meshFor(CgPreviewGeometry geometry) {
        if (geometry == CgPreviewGeometry.SPHERE) {
            if (sphereMesh == null) {
                sphereMesh = CgMesh.uploadDeferred(CgMeshBuilder.uvSphere(CgVertexFormat.SPATIAL, 24, 32, 1f));
            }
            return sphereMesh;
        }
        if (quadMesh == null) {
            CgMeshData data = CgMeshBuilder.quad2D(CgVertexFormat.SPATIAL, -1f, -1f, 1f, 1f);
            quadMesh = CgMesh.uploadDeferred(data);
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
        for (Compiling next : compilingMaterials.values()) next.material.delete();
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
