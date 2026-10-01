package com.crystalgraphics.shadergraph;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMesh;
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
import java.util.EnumMap;
import java.util.Map;

/**
 * The main preview: the <b>finished shader</b> on a mesh you can turn. It issues no GL: a redraw is a raster pass
 * recorded into the recording its caller lends, which the caller's frame executes.
 *
 * <pre>{@code
 * CgGraphTexture picture = renderer.render(graph, master, CgPreviewMesh.SPHERE, yaw, pitch, zoom, true, aspect,
 *         recording);                                    // in paint
 * ctx.drawLayer(picture, x, y, w, h);
 * }</pre>
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

    /**
     * How far back the camera sits from the origin.
     *
     * <p>Nothing to do with framing — the projection is orthographic, so sliding the camera along its own
     * axis changes the picture not at all. It exists because the camera's <b>position</b> is a real input
     * to a shader, and this one was sitting at {@code (0, 0, 0)}: the exact centre of the mesh it was
     * looking at.</p>
     *
     * <p>Which made {@code CG_CAMERA_WORLD_POS} the origin, so a {@code View Direction} node computed
     * {@code normalize(cameraPos - worldPos)} as {@code -normal} — pointing <em>into</em> the surface.
     * {@code dot(N, V)} came out at {@code -1} everywhere and a Fresnel term
     * {@code (1 - dot(N, V))^p} saturated to 1 across the whole mesh: a flat white sphere from a graph
     * whose own node thumbnail showed the rim gradient correctly, because a thumbnail evaluates
     * {@code previewBody} and never touches the camera.</p>
     *
     * <h3>Far, not merely outside the mesh</h3>
     * <p>Being outside is enough to make the view direction point the right WAY. It is not enough to make
     * it point the right way <em>consistently</em>, and this projection is orthographic: an ortho camera's
     * rays are parallel, so its true view vector is a constant, while {@code normalize(cameraPos - P)} is
     * radial from a point. At a distance of 4 those disagree by about 14 degrees at the silhouette of a
     * unit sphere — enough that a Fresnel term saturates well before the rim and its bright band spreads
     * visibly inward, against the same node's own thumbnail where the vector is the constant it should
     * be.</p>
     *
     * <p>At 64 the disagreement is under a degree, which is the point: the number is not a framing choice
     * (orthographic — the picture is identical at any distance) but the distance at which a point camera
     * and a parallel one stop being distinguishable.</p>
     */
    private static final float CAMERA_DISTANCE = 64f;

    /** Main previews drawn at once before one takes another's target: one per graph on screen. */
    private static final int CAPACITY = 4;

    private final int size;
    private final int samples;

    /** Shared with every main preview in the context, as the thumbnails share theirs. @see CgPreviewPool */
    private final CgPreviewSlots<CgPreviewTarget> targets;
    private final String scope = CgPreviewPool.newScope();
    private final String key = scope + "main";

    /** The target the current picture was drawn into; a pooled one may since have gone to another preview. */
    @Nullable
    private CgPreviewTarget target;

    private final Map<CgPreviewMesh, CgMesh> meshes = new EnumMap<>(CgPreviewMesh.class);
    /** The preview camera: its own pass block, so nothing the world draws under is touched. */
    private final CgPassConstants camera = new CgPassConstants();

    private static final CgRenderState PASS_STATE = CgRenderState.builder()
            .depth(CgDepthState.TEST_WRITE).blend(CgBlendState.ALPHA).build();

    /** What produced the picture currently in {@link #target}. @see #render */
    @Nullable
    private String renderedSource;
    @Nullable
    private CgPreviewMesh renderedMesh;
    /** The flat-white stand-in, built on first failure and kept. @see #drawFallback */
    @Nullable
    private CgMaterial fallback;
    private float renderedYaw = Float.NaN;
    private float renderedPitch = Float.NaN;
    private float renderedZoom = Float.NaN;
    private float renderedAspect = Float.NaN;
    private boolean renderedAnimated;

    private boolean failed;
    private boolean deleted;

    public CgMainPreviewRenderer() {
        this(DEFAULT_SIZE, DEFAULT_SAMPLES);
    }

    public CgMainPreviewRenderer(int size, int samples) {
        this.size = Math.max(1, size);
        this.samples = Math.max(1, samples);
        this.targets = CgPreviewPool.forGeometry(this.size, this.samples, CAPACITY);
    }

    public int size() {
        return size;
    }

    /** True when the last compile failed. The panel shows its last good picture rather than nothing. */
    public boolean hasFailed() {
        return failed;
    }

    /**
     * Records {@code graph} drawn on {@code mesh} into {@code recording} and returns the texture, or null if it would
     * not compile.
     *
     * <p><b>Redraws only when something actually changed.</b> Identical source, mesh and orientation mean
     * an identical picture, so a graph merely being panned around costs nothing — the same argument
     * {@code CgPreviewRenderer} makes per thumbnail, and it matters more here because this target is
     * larger than all of them.</p>
     *
     * <h3>The target stays square; the CAMERA is what widens</h3>
     * <p>A caller drawing this into a wide panel has two bad options and one good one. Letterboxing to the
     * short edge is what shipped: the picture is correct and most of the panel is empty backdrop, and
     * zooming in reveals the target's own square boundary — a sphere becomes a rounded square, which reads
     * as a rendering bug rather than as a frame. Stretching the square texture to fill turns that sphere
     * into an ellipse, which is worse.</p>
     *
     * <p>So the orthographic box grows on the long axis by exactly this ratio, the square viewport squashes
     * the result by the same factor, and drawing the square texture <em>stretched across the whole panel</em>
     * undoes it. The mesh fills the panel, keeps its shape, and there is no boundary to zoom into. The
     * texture is not reallocated as the panel is dragged, which is the point of doing it in the projection:
     * this is an MSAA target, so a resize per frame of a drag is real work.</p>
     *
     * @param graph     the IR to draw, or {@code null} when the document has no master node to compile toward;
     *                  drawn as a graph that will not compile
     * @param yaw       orbit around the Y axis, radians
     * @param pitch     orbit around the X axis, radians
     * @param zoom      above 1 moves closer. Applied by DIVIDING the orthographic half-extent, so it reads as a
     *                  camera move rather than as the object changing size, as the orbit does
     * @param lit       viewport shading, <b>not</b> a lighting model — see {@link CgShaderEmitter.Shading}. Unlit
     *                  is what the material draws in game, the mode to check colour against
     * @param aspect    the panel's width divided by its height. Values at or below zero are treated as square,
     *                  since a panel with no area has no aspect to honour
     * @param recording where the pass goes; its frame executes it before anything recorded after reads the texture
     */
    @Nullable
    public CgGraphTexture render(@Nullable CgShaderGraph graph, CgMasterNode master, CgPreviewMesh mesh,
                                 float yaw, float pitch, float zoom, boolean lit, float aspect, CgRecording recording) {
        if (deleted) throw new IllegalStateException("This CgMainPreviewRenderer has been deleted");
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.SHADERGRAPH, "mainPreview.render")) {
            this.recording = recording;
            return renderTraced(graph, master, mesh, yaw, pitch, zoom, lit, aspect);
        } finally {
            this.recording = null;
        }
    }

    /** The recording lent to the current {@link #render}; null outside one. */
    @Nullable
    private CgRecording recording;

    @Nullable
    private CgGraphTexture renderTraced(@Nullable CgShaderGraph graph, CgMasterNode master, CgPreviewMesh mesh,
                                        float yaw, float pitch, float zoom, boolean lit, float aspect) {
        if (!(aspect > 0f) || !Float.isFinite(aspect)) aspect = 1f;

        // A NULL graph is an ordinary editing state, not a caller error: `ShaderGraphBridge.toShaderGraph`
        // returns null when the document has no master node, and deleting the Output node is a perfectly
        // normal thing to do halfway through rewiring a graph. Treated exactly like a graph that will not
        // compile — the panel keeps its last good picture rather than blanking.
        //
        // Guarded here rather than at the call site so the method is total: this ran straight into
        // CgShaderEmitter.emit, which dereferences the graph on its first line, and took the whole harness
        // down from inside a frame ticker.
        if (graph == null) {
            failed = true;
            return drawFallback(mesh, yaw, pitch, zoom, aspect);
        }

        // REUSED while nothing it depends on moved: an animated graph draws every frame, and the emit is a whole
        // compile of the graph -- the same text each time, since it names the time uniform rather than a value.
        CgShaderEmitter.Result emitted = emittedResult;
        if (emitted == null || graph != emittedGraph || lit != emittedLit || master.revision() != emittedMasterRevision) {
            emitted = CgShaderEmitter.emit(graph, master,
                    // PREVIEW_UNLIT, never UNLIT: this panel is looked at, so it wants the sRGB-encoded
                    // output a node thumbnail already produces. UNLIT is what SHIPS, and using it here is
                    // what made the two panels disagree about the same value.
                    lit ? CgShaderEmitter.Shading.PREVIEW_LIT : CgShaderEmitter.Shading.PREVIEW_UNLIT);
            emittedResult = emitted;
            emittedGraph = graph;
            emittedLit = lit;
            emittedMasterRevision = master.revision();
        }
        if (!emitted.ok()) {
            failed = true;
            return drawFallback(mesh, yaw, pitch, zoom, aspect);
        }
        failed = false;

        // ALREADY REFUSED, so do not compile it again -- and RE-ASSERT WHY.
        //
        // The verdict has to be restored here, not merely remembered elsewhere: a successful compile of a
        // different source sets lastDriverError to null, so a graph that is broken, fixed, and broken again
        // the same way arrives back here with the reason gone. The panel then had a preview that would not
        // draw and nothing to say about it, and only for whichever format happened to be memoised -- which
        // is what "pos2 errors but pos3 does not" was.
        if (emitted.source().equals(failedSource)) {
            failed = true;
            lastDriverError = failedSourceError;
            return drawFallback(mesh, yaw, pitch, zoom, aspect);
        }

        // A DIFFERENT SOURCE IS A NEW ATTEMPT. Without this the first source that ever failed is refused
        // for the rest of the session, whatever the graph is edited into afterwards.
        failedSource = null;
        failedSourceError = null;

        CgPreviewTarget acquired = targets.acquire(key);
        boolean unchanged = emitted.source().equals(renderedSource)
                && mesh == renderedMesh
                && yaw == renderedYaw
                && pitch == renderedPitch
                && zoom == renderedZoom
                // The aspect is part of the picture, not of how it is drawn -- it is baked into the
                // projection. Leaving it out of the memo means resizing the panel keeps redrawing the old
                // framing until something else happens to invalidate.
                && aspect == renderedAspect
                // A recycled target holds another preview's picture.
                && acquired == target;
        // An animated graph names a uniform rather than baking a value, so its source is byte-identical
        // frame to frame while its picture is not. Same carve-out CgPreviewRenderer documents.
        if (unchanged && !renderedAnimated) {
            CgTrace.add(CgChannels.SHADERGRAPH, "mainPreview.unchanged", 1);
            return target.texture();
        }
        CgTrace.add(CgChannels.SHADERGRAPH, unchanged ? "mainPreview.draw.animated" : "mainPreview.draw.changed", 1);

        CgMaterial material;
        try {
            material = readyMaterial(emitted.source());
            if (material == null) {
                // STILL COMPILING, and the frame does not wait: the last good material draws, at this camera.
                CgTrace.add(CgChannels.SHADERGRAPH, "mainPreview.compiling", 1);
                if (failed) return drawFallback(mesh, yaw, pitch, zoom, aspect);
                if (heldMaterial == null) return acquired == target ? target.texture() : null;
                drawInto(acquired, heldMaterial, mesh, yaw, pitch, zoom, aspect);
                return target.texture();
            }
            lastDriverError = null;
            drawInto(acquired, material, mesh, yaw, pitch, zoom, aspect);
        } catch (RuntimeException broken) {
            // A preview is a convenience. One material that will not compile -- the emitter is happy and the
            // DRIVER refuses the GLSL, which the compile request reports -- must not take the editor down, nor
            // be retried every frame.
            failed = true;
            lastDriverError = broken.getMessage() == null ? broken.toString() : broken.getMessage();
            failedSource = emitted.source();
            failedSourceError = lastDriverError;
            return drawFallback(mesh, yaw, pitch, zoom, aspect);
        }
        failed = false;

        renderedSource = emitted.source();
        renderedMesh = mesh;
        renderedYaw = yaw;
        renderedPitch = pitch;
        renderedZoom = zoom;
        renderedAspect = aspect;
        renderedAnimated = isAnimated(graph);
        return target.texture();
    }

    /**
     * Draws the mesh in flat unlit white, at the camera as it is now — what a graph that will not compile
     * looks like.
     *
     * <h3>Why NOT the last shader that worked</h3>
     * <p>That was the first answer and it is a lie. The panel would carry on showing a material the graph
     * no longer describes, so a broken graph looks like a working one — and the moment the user believes
     * that, every subsequent edit is being judged against a picture that stopped tracking it. A preview's
     * whole job is to be a truthful answer to "what does this graph do", and "the previous one" is not an
     * answer to that question.</p>
     *
     * <p>Flat white is: it is visibly not anybody's shader, it is what a driver-rejected material already
     * degrades to on screen, and it needs no explanation to read as "nothing came out of this".</p>
     *
     * <h3>What it is really for: the camera stays live</h3>
     * <p>The failure paths used to return {@link #currentTexture()} — the picture exactly as it was. That
     * keeps the panel from blanking and freezes the <b>camera</b> along with it, because orbit, zoom and
     * the mesh menu all feed this same method. A shader you cannot turn around is hardest to inspect
     * precisely when it has just broken. Drawing something — anything — every frame is what puts those
     * gestures back.</p>
     */
    @Nullable
    private CgGraphTexture drawFallback(CgPreviewMesh mesh, float yaw, float pitch, float zoom, float aspect) {
        CgTrace.add(CgChannels.SHADERGRAPH, "mainPreview.fallback", 1);
        try {
            // INSIDE the guard: creating the target reads the context's capabilities, and every failure route in
            // this class comes through here. This is already the failure path.
            drawInto(targets.acquire(key), fallbackMaterial(), mesh, yaw, pitch, zoom, aspect);
        } catch (RuntimeException broken) {
            // The last thing a preview may do is take the editor down from inside a frame ticker, and this
            // is already the failure path -- there is nowhere further to fall.
            return currentTexture();
        }
        // NULLED, not updated. Nothing valid is on screen, so the next compile that succeeds must redraw
        // whatever it produces -- and no real source is ever equal to null, which is what guarantees it.
        renderedSource = null;
        renderedMesh = mesh;
        renderedYaw = yaw;
        renderedPitch = pitch;
        renderedZoom = zoom;
        renderedAspect = aspect;
        renderedAnimated = false;
        return target.texture();
    }

    /**
     * The flat-white stand-in, built once.
     *
     * <p>Written out rather than assembled through {@link CgShaderEmitter}: it must compile when the thing
     * the emitter produced did not, so it cannot share a code path with it. Deliberately the plainest file
     * the format allows — one pass, no properties, no includes, no keywords.</p>
     */
    static final String FALLBACK_SOURCE = String.join("\n",
            "// The main preview's stand-in for a graph that will not compile.",
            "#type spatial",
            "",
            "Tags { \"RenderType\" = \"Opaque\" }",
            "Queue = \"Geometry\"",
            "",
            "struct v2f {",
            "    float unused;",
            "};",
            "",
            "Pass {",
            "    Tags { \"LightMode\" = \"Forward\" }",
            "",
            "    void vertex(out v2f o) {",
            "        gl_Position = CG_MATRIX_MVP * vec4(cg_Position, 1.0);",
            "    }",
            "",
            "    void fragment(in v2f i, out vec4 fragColor) {",
            "        fragColor = vec4(1.0, 1.0, 1.0, 1.0);",
            "    }",
            "}",
            "");

    private CgMaterial fallbackMaterial() {
        if (fallback == null) fallback = CgMaterial.fromSource(FALLBACK_SOURCE);
        return fallback;
    }

    /** The last picture drawn, without drawing. Null before the first successful render. */
    @Nullable
    public CgGraphTexture currentTexture() {
        CgPreviewTarget held = targets.peek(key);
        return held == null || held != target ? null : held.texture();
    }

    /** Whether anything in the graph redraws on its own — {@code Time} and friends. */
    private static boolean isAnimated(CgShaderGraph graph) {
        for (CgShaderGraph.Instance instance : graph.instances()) {
            if (instance.type().isAnimated()) return true;
        }
        return false;
    }

    /**
     * What the driver said about the last generated source, or null.
     *
     * <p>The graph can emit GLSL it believes in and have the driver refuse it — a builtin that does not
     * exist on this profile, a swizzle the emitter got wrong. Until this was exposed that produced a blank
     * panel and a log line: the editor reported "compiled 12n/9e" while nothing rendered.</p>
     */
    @Nullable
    public String lastDriverError() {
        return lastDriverError;
    }

    @Nullable
    private String lastDriverError;

    /**
     * The source the driver last refused, so it is not compiled again every frame.
     *
     * <h3>The latch on the material is not enough here</h3>
     *
     * <p>{@code CgMaterial.hasCompileFailed} stops <em>that material</em> retrying — but this method builds
     * a <b>new</b> material from the source on every call, so each frame got a fresh object with a fresh
     * latch and compiled the same doomed GLSL again. The log filled at frame rate, and the message was not
     * even stable: the pass reported one line and its keyword variant another, so consumers watching the
     * error saw it alternate. A Problems panel rebuilt its rows on every frame because of it.</p>
     *
     * <p>Kept beside {@code lastDriverError} rather than folded into {@code renderedSource}: that one means
     * "what is currently drawn", and a source that never drew anything must not claim to be it.</p>
     */
    @Nullable
    private String failedSource;

    /** Why {@link #failedSource} was refused, kept with it so the short-circuit can restate it. */
    @Nullable
    private String failedSourceError;

    private void drawInto(CgPreviewTarget into, CgMaterial material, CgPreviewMesh mesh, float yaw, float pitch,
                          float zoom, float aspect) {
        target = into;
        try (CgTrace.Zone traced = CgTrace.zone(CgChannels.SHADERGRAPH, "mainPreview.draw")) {
            applyCamera(camera, mesh, yaw, pitch, zoom, aspect);
            // Cleared to transparent, so the panel's own backdrop shows through -- which is what makes a graph's
            // Alpha visible at all. Blending ON, unlike a node thumbnail: Alpha is a real master port, and an
            // opaque preview of a transparent material is a preview of something else.
            CgRasterPass pass = target.begin(recording, camera, PASS_STATE);
            CgPreviewDraw.object(recording, pass, material, meshFor(mesh));
            target.end(recording, pass);
        }
    }

    /**
     * An orthographic camera orbited by {@code yaw}/{@code pitch}.
     *
     * <p>Orthographic rather than perspective on purpose: a perspective preview makes the same material
     * look different depending on how near the camera was put, and there is no scene here to give that
     * distance any meaning.</p>
     */
    private void applyCamera(CgPassConstants camera, CgPreviewMesh mesh, float yaw, float pitch,
                             float zoom, float aspect) {
        // PULLED BACK, then oriented. The translation is applied first so the camera orbits AROUND the
        // mesh rather than the mesh being pushed away along the rotated axis.
        //
        // A CONVENTIONAL camera: at +Z, looking down -Z, like every other one in the engine. Moving it to
        // the far side to match Unity's object-space thumbnails was tried and cannot work -- the two
        // conventions differ by a mirror, not a rotation. See CgPreviewRenderer.applyCamera for the
        // measurements. @see CAMERA_DISTANCE
        camera.view.translation(0f, 0f, -CAMERA_DISTANCE).rotateX(pitch).rotateY(yaw);
        // Clamped rather than trusted: a zero or negative zoom collapses the ortho box and the driver
        // draws nothing at all, which reads as "the shader broke" rather than "the gesture went wrong".
        float r = mesh.viewRadius() / Math.max(0.05f, zoom);
        // THE SHORT AXIS IS THE ONE THAT FITS, and the long axis simply shows more. Scaling the short one
        // down instead would fit the mesh to the panel's diagonal and shrink it every time the panel was
        // widened, which is the opposite of what widening a preview is for.
        float rx = aspect >= 1f ? r * aspect : r;
        float ry = aspect >= 1f ? r : r / aspect;
        // Depth range spans well past the shape in both directions: the view rotation moves geometry
        // through Z, and a box fitted to the un-rotated extent would clip a corner into view as it turned.
        // Deep enough to hold CAMERA_DISTANCE plus the largest mesh's own extent, now that the camera is
        // no longer sitting inside the mesh. The near/far ORDER is unchanged and deliberately so —
        // reversing it flips which hemisphere the depth test keeps, which is a separate change that was
        // tried, made every coordinate thumbnail worse, and was reverted.
        // NEAR AND FAR IN THIS ORDER. Swapping them flips the projection's determinant, which flips
        // triangle winding, which makes back-face culling keep the opposite set -- so it cancels its own
        // effect on the depth test and changes nothing on screen. See CgPreviewRenderer.applyCamera.
        camera.projection.setOrtho(-rx, rx, -ry, ry, -128f, 128f);
        camera.resolution(size, size).time(CgFrameClock.seconds()).cameraFromView();
    }

    /**
     * The material for {@code source}, or null while the driver is still compiling it. The held one is kept while
     * its source is -- an animated graph draws every frame -- and replaced only once the new one is ready. The
     * compile is a request recorded again each frame until one answers, since the last may not have executed yet.
     *
     * @throws IllegalStateException when the driver refused the source
     */
    @Nullable
    private CgMaterial readyMaterial(String source) {
        if (heldMaterial != null && source.equals(heldSource)) return heldMaterial;
        if (compilingMaterial == null || !source.equals(compilingSource)) {
            if (compilingMaterial != null) compilingMaterial.delete();
            compilingMaterial = CgMaterial.fromSource(source);
            compilingSource = source;
            compileRequest = null;
        }
        if (compileRequest != null && compileRequest.failed()) throw new IllegalStateException(compileRequest.failure());
        if (compileRequest == null || !compileRequest.done()) {
            CgPipeline pipeline = compilingMaterial.pipeline(CgInstanceKind.OBJECT);
            if (pipeline == null) {
                String error = compilingMaterial.lastCompileError();
                throw new IllegalStateException(error != null ? error : "the preview shader does not parse");
            }
            compileRequest = recording.compile(pipeline);
            return null;
        }
        compileRequest = null;
        if (heldMaterial != null) heldMaterial.delete();
        heldMaterial = compilingMaterial;
        heldSource = compilingSource;
        compilingMaterial = null;
        compilingSource = null;
        return heldMaterial;
    }

    /** The last emit, and what it was emitted from. @see #renderTraced */
    @Nullable
    private CgShaderEmitter.Result emittedResult;
    @Nullable
    private CgShaderGraph emittedGraph;
    private boolean emittedLit;
    private int emittedMasterRevision;

    @Nullable
    private CgMaterial heldMaterial;

    @Nullable
    private String heldSource;

    /** A newer source's material while the driver compiles it. @see #readyMaterial */
    @Nullable
    private CgMaterial compilingMaterial;

    @Nullable
    private String compilingSource;

    /** The latest compile recorded for {@link #compilingMaterial}. */
    @Nullable
    private CgRequest compileRequest;

    /** Built on first use, uploaded by the render thread, and kept: switching back to a shape must not re-upload it. */
    private CgMesh meshFor(CgPreviewMesh mesh) {
        return meshes.computeIfAbsent(mesh, m -> CgMesh.upload(m.build(CgVertexFormat.SPATIAL)));
    }

    /**
     * Gives its target back to the pool and frees every mesh built so far. Any thread: the meshes go on the render
     * thread, and the target stays in the pool for the next preview.
     */
    public void delete() {
        if (deleted) return;
        targets.releaseAllWithin(scope);
        target = null;
        for (CgMesh mesh : meshes.values()) mesh.delete();
        meshes.clear();
        if (heldMaterial != null) heldMaterial.delete();
        heldMaterial = null;
        heldSource = null;
        if (compilingMaterial != null) compilingMaterial.delete();
        compilingMaterial = null;
        compilingSource = null;
        renderedSource = null;
        renderedMesh = null;
        // Borrowed from CgMaterialRegistry, never owned here -- dropping the reference is the whole of it,
        // and deleting it would be a double free when the registry sweeps at context teardown.
        fallback = null;
        deleted = true;
    }
}
