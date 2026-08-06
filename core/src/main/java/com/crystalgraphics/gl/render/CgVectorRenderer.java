package com.crystalgraphics.gl.render;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.gl.mesh.CgMeshRegistry;
import com.crystalgraphics.util.profiling.CgProfiler;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Instanced 2D vector-primitive renderer — CrystalGraphics' general "hand me a few points and a
 * colour, and say whether to stroke or fill them" engine seam, and the vector-graphics counterpart
 * to {@link CgQuadRenderer}'s box-model one. Built on the same SSBO/TBO-backed instancing
 * foundation and deliberately mirroring its API shape method-for-method.
 *
 * <h3>What it draws today is a list; what it IS is a shared record</h3>
 * <p>{@link #curve()} strokes a quadratic Bézier:</p>
 * <ul>
 *   <li>A <b>straight line</b> is a quadratic whose control point is the midpoint.</li>
 *   <li>A <b>cubic</b> splits into 1–{@value #MAX_CUBIC_SEGMENTS} quadratics on the CPU
 *       ({@link Curve#cubic}), exactly as font rasterizers do.</li>
 *   <li><b>Arcs, polylines and rounded elbows</b> are sequences of quadratics.</li>
 * </ul>
 * <p>{@link #triangle()} fills the same three points instead of stroking a path through them —
 * see {@link Triangle}'s own doc for why that is a second thing this renderer does rather than a
 * second renderer. This is the ordinary SVG/Canvas2D/Skia model: one path, filled or stroked at
 * draw time, not a zoo of unrelated primitive classes each with their own buffer.</p>
 *
 * <p>That is the actual reason for the name, and the thing worth internalising before adding a
 * third capability: this class is not "the curve-and-triangle renderer" any more than {@link
 * CgQuadRenderer} is "the UI-box-and-text-glyph-and-rounded-rect renderer" — it is named for its
 * <b>instance record</b> (a handful of points, a colour pair, a width pair, a feather, a flags
 * field), not for the enumerable set of things that happen to be drawn with it today. A stroke and
 * a fill are two <em>readings</em> of one schema; the next vector primitive this engine needs is
 * very likely a third reading of the same schema rather than a reason to write a new renderer.
 * Concretely: {@code color1} and {@code widths.y} both sit unused in fill mode, and only 5 of the
 * available {@code flags} bits are claimed (see the schema table below) — that headroom is not an
 * oversight, it is what makes the next addition cheap. Plausible candidates that would fit here
 * exactly the way {@link #triangle()} did: a dashed-stroke flag once arc-length parameterisation is
 * worth the fragment cost, a gradient or per-vertex-coloured fill, a richer join/cap vocabulary
 * alongside {@code CAP_ARROW}, or a fourth point for a filled quad. None of that is built — this
 * paragraph is a map of where it would go, not a promise of what is coming.</p>
 *
 * <p>The reason to stop a stroke at quadratic rather than take cubic as the primitive is that a
 * quadratic has an <em>exact analytic</em> signed distance function — {@code sdf_bezier} in
 * {@code shaders/lib/sdf.glsl}, one closed-form cubic solve. A cubic's distance is a quintic with
 * no closed form, so making cubic the primitive would mean approximating per pixel forever.
 * Splitting once on the CPU is both cheaper and exact.</p>
 *
 * <p>The class itself has already been renamed once, from {@code CgCurveRenderer} — an accurate
 * name right up until {@link #triangle()} made it not one. {@code CgVectorRenderer} was chosen over
 * alternatives like {@code CgPathRenderer} or {@code CgShapeRenderer} specifically to name the
 * general capability rather than any one visual result: "vector" commits to nothing about whether
 * the output is stroked or filled, curved or straight, which is the property that let this survive
 * its first extension without a second rename and should let it survive its next one too. If a
 * future addition makes the name feel wrong again, that itself is a signal the addition belongs in
 * a genuinely different renderer instead.</p>
 *
 * <h3>Fixed instance schema — one field set, several readings</h3>
 * <pre>
 * vec3 p0, p1, p2       // STROKE: quadratic control points, pose baked in (see Curve#pose)
 *                       // FILL:   triangle vertices, pose baked in (see Triangle#pose)
 * vec4 color0, color1   // STROKE: gradient along the curve, p0 -&gt; p2
 *                       // FILL:   color0 is the flat fill colour; color1 unused
 * vec2 widths           // STROKE: start/end HALF-width — tapered strokes
 *                       // FILL:   widths.x is corner radius; widths.y unused
 * float feather         // edge softness, in the same units as widths — same meaning either way
 * float flags           // cap style (see CAP_*), packed; bit 4 (FLAG_FILL) selects fill over stroke
 * </pre>
 * <p>Under STD430 each {@code vec3} pads to 16 bytes, so this record occupies exactly 96 bytes —
 * the same stride as {@link CgQuadRenderer}'s, with no trailing waste. The three padding floats
 * are the price of keeping the control points {@code vec3}; see the planar note below for why
 * they are {@code vec3} at all.</p>
 * <p>The fill reading exists because it costs nothing once the stroke reading does: three points, a
 * colour and a feather were already here for {@link Curve}'s sake, so a filled {@link Triangle}
 * needs one bit and one repurposed field, not a second buffer. See {@link Triangle}'s own doc.</p>
 *
 * <p>Deliberately <b>no dash field.</b> Dashing needs arc length along the curve, which is a
 * materially larger fragment shader than everything else here, and nothing consumes it yet.
 * An unused field that silently does nothing is worse than an absent one — so it is absent, and
 * adding it later is an ordinary additive schema change.</p>
 *
 * <h3>v1 is planar</h3>
 * <p>The vertex shader derives its own bounding quad from the convex hull of the three control
 * points (a Bézier is contained by its control hull), expanded by {@code max(widths) + feather}.
 * That hull box is axis-aligned in the space the control points are already in, which is correct
 * and conservative for any 2D pose including a rotating one — but degenerates for a pose that
 * rotates the curve out of the XY plane. <b>Curves are 2D:</b> the SDF is evaluated in XY and Z is
 * carried through for depth ordering only. The control points stay {@code vec3} so that a genuine
 * 3D stroke is an additive change rather than a schema break.</p>
 *
 * <h3>Widths are post-pose units</h3>
 * <p>{@link Curve#pose(Matrix4f)} bakes the transform into the control points on the CPU, exactly
 * as {@code CgQuadRenderer.Quad} does. A scalar half-width sitting beside them does not transform
 * on its own, so a scaled pose would otherwise give a correctly-scaled curve wearing a
 * wrong-thickness stroke. {@link Curve#submit()} therefore scales {@code widths} and
 * {@code feather} by the pose's uniform scale factor. Widths are consequently expressed in
 * <em>post-pose</em> units — the same space the baked control points end up in.</p>
 * <p>For a non-uniform scale there is no single right answer, and inventing one silently is worse
 * than picking one loudly: the larger of the X/Y scales is used, which keeps a stroke from
 * disappearing under an anisotropic zoom.</p>
 *
 * <h3>Per-frame lifecycle — identical to {@link CgQuadRenderer}</h3>
 * <pre>{@code
 * renderer.useMaterial(material);  // every frame; rebinds on every call
 * renderer.begin();
 * renderer.curve().line(x0, y0, x1, y1).width(2f).color(argb).submit();
 * renderer.curve().from(x0, y0).via(cx, cy).to(x1, y1).width(4f, 1f).colors(a, b).submit();
 * renderer.curve().cubic(x0,y0, c1x,c1y, c2x,c2y, x1,y1).width(2f).submit(); // -> N instances
 * renderer.flush();                // the only upload + draw
 * renderer.end();
 * }</pre>
 *
 * <h3>Shader side</h3>
 * <p>Declare the buffer, exactly as quad consumers do:</p>
 * <pre>{@code
 * #type pos2_uv2_col4ub
 * #pragma cg_use curve
 * }</pre>
 * <p>{@code cg_env.glsl} then provides {@code CG_CURVE_WORLD_POS} (vertex stage — it consumes
 * {@code cg_Position} to place the derived bounding quad) plus {@code CG_CURVE_P0}/{@code _P1}/
 * {@code _P2}/{@code _COLOR0}/{@code _COLOR1}/{@code _WIDTHS}/{@code _FEATHER}/{@code _FLAGS},
 * which resolve in <em>both</em> stages. The fragment stage re-reads the instance record directly
 * through {@code CG_INSTANCE_ID} rather than receiving control points as varyings — the
 * {@code .shader} v2f DSL has no {@code flat} qualifier to offer (only the compiler-generated
 * {@code cg_InstanceId} is flat), and the SDF wants the raw control points anyway.</p>
 *
 * <p>The shipped {@code crystalgraphics:shaders/curve.shader} is the reference consumer, in the
 * same way {@code text.shader} is {@code CgQuadRenderer}'s.</p>
 */
public final class CgVectorRenderer extends CgAbstractRenderer {

    /** Same shared unit-quad vertex format as {@link CgQuadRenderer} — the mesh is a bounding box carrier. */
    private static final CgVertexFormat CURVE_MESH_FORMAT = CgVertexFormat.POS2_UV2_COL4UB;

    /** Fixed per-instance record format shared by every {@code CgVectorRenderer} instance. */
    private static final CgBufferFormat INSTANCE_FORMAT = CgBufferFormat
            .builder("CurveInstance", CgBufferFormat.MemoryLayout.STD430)
            .vec3("p0").vec3("p1").vec3("p2")
            .vec4("color0").vec4("color1")
            .vec2("widths")
            .float_("feather")
            .float_("flags")
            // Linear-gradient axis for a FILL reading: (originX, originY, dirX, dirY), in the same space
            // as p0/p1/p2 and scaled so t = dot(p - origin, dir) runs 0..1 across color0 -> color1.
            // Zero for every stroke and for any flat fill; FLAG_GRADIENT is what says to read it.
            .vec4("gradient")
            .build();

    private static final String GPU_BUFFER_NAME = "CgVectorRendererInstances";

    /**
     * Fixed {@code attach()} macro name every {@code CgVectorRenderer} consumer uses. Shaders declare
     * {@code #pragma cg_use curve} rather than naming this string, so it never appears at a call
     * site. {@code CgShaderParser}'s reverse check derives the convenience-macro family
     * ({@code CG_CURVE_}) from this name automatically, so no parser change is needed to police it.
     */
    public static final String MACRO_NAME = "CURVE_DATA";

    /** Butt cap — the stroke ends exactly at {@code p0}/{@code p2}. The default. */
    public static final int CAP_BUTT = 0;
    /** Round cap — a half-disc of the local half-width is added at each end. */
    public static final int CAP_ROUND = 1;
    /** Square cap — the stroke is extended by its local half-width past each end. */
    public static final int CAP_SQUARE = 2;
    /**
     * Arrowhead cap — a filled wedge past the endpoint, wider than the stroke at its base and
     * tapering to a point. The one cap style with its own shape rather than an axis-aligned box;
     * see {@code stroke.glsl}'s {@code _stroke_cap_dist} for the wedge SDF. Fits the same 2-bit
     * packing as the other three ({@link #packCaps}) — 4 values is exactly what 2 bits hold.
     */
    public static final int CAP_ARROW = 3;

    /**
     * Bit flag in the packed {@code flags} field marking a {@link Triangle} instance rather than a
     * {@link Curve} one — must match {@code CG_STROKE_FLAG_FILL} in {@code stroke.glsl}, the one
     * place both curve materials decide whether to stroke or fill an instance. Sits one bit above
     * the cap packing's 4 used bits (0-3), so a fill instance's cap bits are simply left at 0/unused
     * rather than needing their own reserved "no cap" value.
     */
    static final int FLAG_FILL = 16;

    /**
     * Set alongside {@link #FLAG_FILL} when {@code gradient} carries a real axis, so the fragment stage
     * interpolates {@code color0}→{@code color1} across the triangle instead of taking {@code color0}
     * flat. Must match {@code CG_STROKE_FLAG_GRADIENT} in {@code stroke.glsl}.
     *
     * <p>A flag rather than "the axis is non-zero": a zero-length axis is a legitimate degenerate
     * gradient, and a branch on a bit is what the stroke reading already does.</p>
     */
    static final int FLAG_GRADIENT = 32;

    /**
     * Upper bound on how many quadratics one {@link Curve#cubic} call may split into — so a caller
     * sizing its own batch knows the worst case. See {@link CgCurveSplitter} for the maths and for
     * why it lives in a separate class.
     */
    public static final int MAX_CUBIC_SEGMENTS = CgCurveSplitter.MAX_CUBIC_SEGMENTS;

    /** Initial CPU accumulation capacity, in instances. Pre-sizing hint only — auto-grows. */
    private static final int INITIAL_CAPACITY_INSTANCES = 64;

    /**
     * Shared static unit quad mesh, {@code [0,0]->[1,1]}. The vertex shader reinterprets these
     * local coordinates as a parameterisation of the derived control-hull bounding box, so this is
     * the identical mesh {@link CgQuadRenderer} uses and is fetched from the same registry entry.
     */
    private static final CgMesh CURVE_MESH = CgMeshRegistry.get().getOrCreate(
            "crystalgraphics:builtin/quad/" + CURVE_MESH_FORMAT.toString(),
            () -> CgMesh.upload(CgMeshBuilder.quad2D(CURVE_MESH_FORMAT, 0f, 0f, 1f, 1f)));

    /**
     * Shared static shader buffer — one SSBO/TBO backs every {@code CgVectorRenderer} instance,
     * at the engine-reserved {@link CgBindingPoints#CURVE_RENDERER} pair.
     *
     * <p>Same lazy-class-init contract as {@code CgQuadRenderer.GPU_BUFFER}:
     * {@link CgBindingPoints#init} must have run before this class is first touched, which is why
     * {@code CgEngineBufferRegistry} seeds the {@code curve} token with a <em>method reference</em>
     * to {@link #instanceBuffer()} rather than a field read.</p>
     */
    private static final CgShaderBuffer GPU_BUFFER = CgShaderBufferRegistry.get()
            .getOrCreateInternal(GPU_BUFFER_NAME, INSTANCE_FORMAT, CgBindingPoints.CURVE_RENDERER);

    /** Standalone CPU accumulation pair — per renderer instance, so callers batch independently. */
    private final CgStagingBuffer accumStaging;
    private final CgBufferWriter accumWriter;

    /** Reused scratch {@link Curve} instance returned by {@link #curve()}. */
    private final Curve scratchCurve = new Curve();

    /** The material {@link #useMaterial(CgMaterial)} last switched to, or {@code null} if never called. */
    private CgMaterial currentMaterial;

    /**
     * Creates a new {@code CgVectorRenderer}. Each instance owns its own CPU accumulation buffer but
     * shares the static mesh, format and GPU buffer with every other instance.
     */
    public static CgVectorRenderer create() {
        CgStagingBuffer staging = new CgStagingBuffer(INSTANCE_FORMAT.getFloatCount(),
                Math.max(1, INITIAL_CAPACITY_INSTANCES / 4));
        return new CgVectorRenderer(staging, new CgBufferWriter(staging, INSTANCE_FORMAT));
    }

    private CgVectorRenderer(CgStagingBuffer accumStaging, CgBufferWriter accumWriter) {
        this.accumStaging = accumStaging;
        this.accumWriter = accumWriter;
    }

    /**
     * The shared instance buffer, without needing a renderer instance.
     *
     * <p>Exists for {@code CgEngineBufferRegistry}, which seeds the {@code curve}
     * {@code #pragma cg_use} token with a reference to this method. Being a method reference it
     * does not trigger this class's static initialization at registration time — which matters,
     * because {@link #GPU_BUFFER} allocates against {@code CgBindingPoints} and is only valid after
     * {@code CgRenderPipeline.init()}.</p>
     */
    public static CgShaderBuffer instanceBuffer() {
        return GPU_BUFFER;
    }

    /**
     * Marks {@code material} as the one this renderer's next {@code curve()...submit()} calls belong
     * to. Required before any {@link Curve#submit()} call, and must be called again every frame.
     *
     * <p>Semantics are identical to {@code CgQuadRenderer.useMaterial}: auto-flush when the material
     * reference actually changes (instances already queued were computed for the previous material),
     * and an unconditional rebind on <em>every</em> call, because other rendering code sharing this
     * GL context can bind a different program between two frames of your own.</p>
     */
    public CgVectorRenderer useMaterial(CgMaterial material) {
        if (material != currentMaterial) {
            flush();
            if (currentMaterial != null) currentMaterial.unbind();
            currentMaterial = material;
        }
        material.bind();
        return this;
    }

    @Override
    protected void onBegin() {
        accumStaging.reset();
    }

    @Override
    protected boolean hasPendingWork() {
        return !accumStaging.isEmpty();
    }

    /**
     * Starts a fluent curve submission using this renderer's single reused scratch instance — zero
     * allocation per call. Build it and call {@link Curve#submit()} in the same expression; do not
     * hold the returned reference past that, since any other {@code curve()} call on this renderer
     * resets and reuses the same instance. For a descriptor you want to hold across frames, use
     * {@link #retainedCurve()}.
     *
     * <p><strong>{@link Curve#submit()} only queues instance records on the CPU</strong> — it does
     * not upload or draw. Call {@link #flush()} once for everything batched together.</p>
     */
    public Curve curve() {
        return scratchCurve.reset();
    }

    /**
     * Allocates a standalone, retained-mode {@link Curve} the caller owns and may hold across
     * frames — build once, then {@link Curve#submit()} every tick, mutating only what changed.
     * Independent of {@link #curve()}'s shared immediate-mode scratch instance.
     */
    public Curve retainedCurve() {
        return new Curve();
    }

    /**
     * Fluent, mutable curve submission request.
     *
     * <h3>Defaults</h3>
     * <p>Control points default to the origin; {@link #width(float)} defaults to a half-width of
     * {@code 1}; {@link #colors(int, int)} defaults to opaque white at both ends; {@link #feather}
     * defaults to {@code 1} (roughly one pixel of edge softness at UI scale); {@link #cap} defaults
     * to {@link #CAP_BUTT}; {@link #pose(Matrix4f)} defaults to {@code null}.</p>
     *
     * <p>Geometry has no default — {@link #submit()} throws {@link IllegalStateException} if none of
     * {@link #line}, {@link #via}/{@link #to}, or {@link #cubic} was called.</p>
     *
     * <h3>Allocation-free</h3>
     * <p>{@link #submit()} never allocates: pose baking writes into this instance's own reused
     * {@link Vector3f} scratch fields, and {@link #cubic} splits into a reused float array.</p>
     */
    public final class Curve {

        private float p0x, p0y, p0z;
        private float p1x, p1y, p1z;
        private float p2x, p2y, p2z;
        private boolean geometrySet;

        private float widthStart, widthEnd;
        private int argb0, argb1;
        private float feather;
        private int capStart, capEnd;
        private Matrix4f pose;

        /** Start/end caps for the record currently being written, packed by {@link #packCaps}. */
        private int packedCaps;

        /** Set by {@link #cubic}; when non-zero, submit() emits this many quadratics instead of one. */
        private int cubicSegments;
        /** Flattened (p0,p1,p2) triples for the split cubic — reused, never reallocated per call. */
        private final float[] cubicScratch = new float[MAX_CUBIC_SEGMENTS * 9];

        // Reused across every submit() call on this Curve instance — never reallocated.
        private final Vector3f scratchP0 = new Vector3f();
        private final Vector3f scratchP1 = new Vector3f();
        private final Vector3f scratchP2 = new Vector3f();

        private Curve() {
            reset();
        }

        Curve reset() {
            p0x = p0y = p0z = 0f;
            p1x = p1y = p1z = 0f;
            p2x = p2y = p2z = 0f;
            geometrySet = false;
            widthStart = 1f;
            widthEnd = 1f;
            argb0 = 0xFFFFFFFF;
            argb1 = 0xFFFFFFFF;
            feather = 1f;
            capStart = CAP_BUTT;
            capEnd = CAP_BUTT;
            pose = null;
            cubicSegments = 0;
            return this;
        }

        /**
         * A straight line from {@code (x0,y0)} to {@code (x1,y1)} — submitted as a quadratic whose
         * control point is the midpoint, which is exactly a straight segment.
         */
        public Curve line(float x0, float y0, float x1, float y1) {
            return from(x0, y0).via((x0 + x1) * 0.5f, (y0 + y1) * 0.5f).to(x1, y1);
        }

        /** Start point. Z defaults to {@code 0}; see the planar note on the class doc. */
        public Curve from(float x, float y) {
            return from(x, y, 0f);
        }

        /** Start point including depth. Z is carried for ordering only — the SDF is evaluated in XY. */
        public Curve from(float x, float y, float z) {
            p0x = x; p0y = y; p0z = z;
            return this;
        }

        /** Quadratic control point. Pass the midpoint of the endpoints for a straight line. */
        public Curve via(float x, float y) {
            return via(x, y, 0f);
        }

        /** Quadratic control point including depth. */
        public Curve via(float x, float y, float z) {
            p1x = x; p1y = y; p1z = z;
            return this;
        }

        /** End point. Completes the geometry — {@link #submit()} is legal after this. */
        public Curve to(float x, float y) {
            return to(x, y, 0f);
        }

        /** End point including depth. */
        public Curve to(float x, float y, float z) {
            p2x = x; p2y = y; p2z = z;
            geometrySet = true;
            cubicSegments = 0;
            return this;
        }

        /**
         * A cubic Bézier, split on the CPU into 1–{@value #MAX_CUBIC_SEGMENTS} quadratics — one
         * {@link #submit()} call then queues that many instance records.
         *
         * <p>Segment count is chosen adaptively from the cubic's third difference, which is what
         * bounds the error of the midpoint quadratic approximation. A gentle S-curve — the standard
         * node-graph wire — is typically one or two segments, not four.</p>
         */
        public Curve cubic(float x0, float y0,
                           float c1x, float c1y,
                           float c2x, float c2y,
                           float x1, float y1) {
            cubicSegments = CgCurveSplitter.splitCubic(x0, y0, c1x, c1y, c2x, c2y, x1, y1, cubicScratch);
            // Keep p0/p2 meaningful for anything inspecting the descriptor; the emitted records
            // come from cubicScratch, not from these.
            p0x = x0; p0y = y0;
            p2x = x1; p2y = y1;
            geometrySet = true;
            return this;
        }

        /** Uniform half-width along the whole curve. */
        public Curve width(float halfWidth) {
            return width(halfWidth, halfWidth);
        }

        /** Tapered half-width, interpolated from {@code p0} to {@code p2}. */
        public Curve width(float halfWidthStart, float halfWidthEnd) {
            this.widthStart = halfWidthStart;
            this.widthEnd = halfWidthEnd;
            return this;
        }

        /** Uniform packed-ARGB colour. */
        public Curve color(int argb) {
            return colors(argb, argb);
        }

        /** Gradient colour, interpolated from {@code p0} to {@code p2}. */
        public Curve colors(int argbStart, int argbEnd) {
            this.argb0 = argbStart;
            this.argb1 = argbEnd;
            return this;
        }

        /** Edge softness, in the same post-pose units as the widths. Defaults to {@code 1}. */
        public Curve feather(float feather) {
            this.feather = feather;
            return this;
        }

        /** Cap style at both ends — one of {@link #CAP_BUTT}, {@link #CAP_ROUND}, {@link #CAP_SQUARE},
         * {@link #CAP_ARROW}. */
        public Curve cap(int cap) {
            return cap(cap, cap);
        }

        /**
         * Independent cap style per end — the natural case is a directional curve: {@code cap(
         * CAP_ROUND, CAP_ARROW)} for a node-graph wire that should read as "points at its
         * destination" without an arrowhead sprouting from the source too.
         *
         * <p>For a {@link #cubic} split into several instances, only the curve's two true ends carry
         * these — every interior joint still butts flush regardless of what is asked for here, since
         * two caps of any style stacked at one point double-blend their antialiased rims (see
         * {@link #packCaps}). {@code capEnd} therefore means "the far end of the whole cubic," not
         * "the end of whichever segment happens to be last."</p>
         */
        public Curve cap(int capStart, int capEnd) {
            this.capStart = capStart;
            this.capEnd = capEnd;
            return this;
        }

        /**
         * Optional transform, baked on the CPU at {@link #submit()} time into the control points.
         *
         * <p>Also scales {@code widths} and {@code feather} by the pose's uniform scale — see the
         * class doc. {@code null} (the default) submits the curve untransformed.</p>
         */
        public Curve pose(Matrix4f pose) {
            this.pose = pose;
            return this;
        }

        /**
         * Writes this curve as one instance record — or, after {@link #cubic}, as one record per
         * split segment — into the owning renderer's CPU accumulation buffer.
         *
         * <p>Queues only. Call {@link CgVectorRenderer#flush()} to upload and draw.</p>
         *
         * @throws IllegalStateException if {@link #begin()} was not called, if no geometry was set,
         *                               or if {@link #useMaterial(CgMaterial)} was never called
         */
        public CgVectorRenderer submit() {
            if (!begun) throw new IllegalStateException("CgVectorRenderer not begun");
            if (!geometrySet) throw new IllegalStateException(
                    "CgVectorRenderer.Curve requires geometry — call line(...), to(...) or cubic(...) before submit()");
            if (currentMaterial == null) throw new IllegalStateException(
                    "CgVectorRenderer.Curve requires useMaterial(material) before submit() — "
                            + "without it, this renderer's buffer may not be attached to whatever material is bound");

            float widthScale = poseScale();

            if (cubicSegments > 0) {
                for (int i = 0; i < cubicSegments; i++) {
                    int o = i * 9;
                    // Only the curve's true ends carry the caller's cap; every interior joint butts
                    // flush against its neighbour. Two round caps stacked at one point double-blend
                    // their antialiased rims into a visible disc — see packCaps.
                    int segStartCap = (i == 0) ? capStart : CAP_BUTT;
                    int segEndCap = (i == cubicSegments - 1) ? capEnd : CAP_BUTT;
                    packedCaps = CgCurveSplitter.packCaps(segStartCap, segEndCap);
                    // Taper across the whole cubic, not per segment: each split piece gets the
                    // slice of the [start,end] width ramp that its own t-range covers, so a tapered
                    // cubic tapers smoothly instead of restarting at every segment boundary.
                    float t0 = i / (float) cubicSegments;
                    float t1 = (i + 1) / (float) cubicSegments;
                    writeRecord(
                            cubicScratch[o], cubicScratch[o + 1], cubicScratch[o + 2],
                            cubicScratch[o + 3], cubicScratch[o + 4], cubicScratch[o + 5],
                            cubicScratch[o + 6], cubicScratch[o + 7], cubicScratch[o + 8],
                            CgCurveSplitter.lerp(widthStart, widthEnd, t0) * widthScale,
                            CgCurveSplitter.lerp(widthStart, widthEnd, t1) * widthScale,
                            CgCurveSplitter.lerpArgb(argb0, argb1, t0),
                            CgCurveSplitter.lerpArgb(argb0, argb1, t1),
                            feather * widthScale);
                }
            } else {
                packedCaps = CgCurveSplitter.packCaps(capStart, capEnd);
                writeRecord(p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z,
                        widthStart * widthScale, widthEnd * widthScale,
                        argb0, argb1, feather * widthScale);
            }

            return CgVectorRenderer.this;
        }

        /**
         * The scale factor {@link #pose} applies to a stroke's thickness — the larger of the X/Y
         * column lengths, so an anisotropic zoom thickens rather than thins a stroke toward
         * nothing. {@code 1} when there is no pose.
         */
        private float poseScale() {
            if (pose == null) return 1f;
            float sx = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01() + pose.m02() * pose.m02());
            float sy = (float) Math.sqrt(pose.m10() * pose.m10() + pose.m11() * pose.m11() + pose.m12() * pose.m12());
            return Math.max(sx, sy);
        }

        private void writeRecord(float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 float cx, float cy, float cz,
                                 float wStart, float wEnd,
                                 int colorStart, int colorEnd, float feath) {
            if (pose != null) {
                scratchP0.set(ax, ay, az);
                scratchP1.set(bx, by, bz);
                scratchP2.set(cx, cy, cz);
                pose.transformPosition(scratchP0);
                pose.transformPosition(scratchP1);
                pose.transformPosition(scratchP2);
                ax = scratchP0.x(); ay = scratchP0.y(); az = scratchP0.z();
                bx = scratchP1.x(); by = scratchP1.y(); bz = scratchP1.z();
                cx = scratchP2.x(); cy = scratchP2.y(); cz = scratchP2.z();
            }

            accumWriter.beginRecord()
                    .vec3("p0", ax, ay, az)
                    .vec3("p1", bx, by, bz)
                    .vec3("p2", cx, cy, cz)
                    .color("color0", colorStart)
                    .color("color1", colorEnd)
                    .vec2("widths", wStart, wEnd)
                    .float_("feather", feath)
                    .float_("flags", packedCaps)
                    .vec4("gradient", 0f, 0f, 0f, 0f)
                    .endRecord();
        }
    }

    /** Reused scratch {@link Triangle} instance returned by {@link #triangle()}. */
    private final Triangle scratchTriangle = new Triangle();

    /**
     * Starts a fluent filled-triangle submission using this renderer's single reused scratch
     * instance — same allocation-free contract as {@link #curve()}. Build and {@link
     * Triangle#submit()} in the same expression.
     *
     * <p>Shares this renderer's instance buffer, material and per-frame lifecycle with {@link
     * #curve()} entirely — a triangle is not a second renderer, only a second thing to do with the
     * same three-point instance schema. See the class doc's "Filled triangles" section.</p>
     */
    public Triangle triangle() {
        return scratchTriangle.reset();
    }

    /** Retained-mode twin of {@link #triangle()}, mirroring {@link #retainedCurve()}. */
    public Triangle retainedTriangle() {
        return new Triangle();
    }

    /**
     * Fluent, mutable filled-triangle submission request — the second thing this renderer's shared
     * {@code p0/p1/p2} instance schema can mean, alongside {@link Curve}'s stroked quadratic.
     *
     * <h3>Why a triangle shares {@code CgVectorRenderer} rather than getting its own renderer</h3>
     * <p>This is the standard 2D vector-graphics model — SVG, Canvas2D, Skia and NanoVG all let one
     * path be either filled or stroked at draw time, not two unrelated primitive types. It also
     * costs nothing here specifically: three points, a colour and a feather already exist in the
     * instance record for {@link Curve}'s sake, so a filled triangle needs exactly one new bit
     * ({@link #FLAG_FILL}) and one repurposed field ({@code widths.x} becomes corner radius, since a
     * filled shape has no taper to store there). A second renderer would mean a second engine-
     * reserved buffer visible from the fragment stage — the same scarce-texture-unit cost {@code
     * CgBindingPoints} already documents for {@link #CURVE_RENDERER}'s TBO fallback path — to
     * duplicate fields that already exist.</p>
     *
     * <h3>Defaults</h3>
     * <p>Points default to the origin; {@link #color(int)} defaults to opaque white; {@link
     * #cornerRadius(float)} defaults to {@code 0} (sharp corners); {@link #feather} defaults to
     * {@code 1}, same as {@link Curve}'s.</p>
     */
    public final class Triangle {

        private float p0x, p0y, p0z;
        private float p1x, p1y, p1z;
        private float p2x, p2y, p2z;
        private int argb;
        private int argbEnd;
        private boolean gradient;
        private float gradOx, gradOy, gradDx, gradDy;
        private float cornerRadius;
        private float feather;
        private Matrix4f pose;

        // Reused across every submit() call — never reallocated, mirroring Curve's own scratch trio.
        private final Vector3f scratchP0 = new Vector3f();
        private final Vector3f scratchP1 = new Vector3f();
        private final Vector3f scratchP2 = new Vector3f();

        private Triangle() {
            reset();
        }

        Triangle reset() {
            p0x = p0y = p0z = 0f;
            p1x = p1y = p1z = 0f;
            p2x = p2y = p2z = 0f;
            argb = 0xFFFFFFFF;
            argbEnd = 0xFFFFFFFF;
            gradient = false;
            gradOx = gradOy = gradDx = gradDy = 0f;
            cornerRadius = 0f;
            feather = 1f;
            pose = null;
            return this;
        }

        /** First vertex. Z defaults to {@code 0}; see {@link Curve}'s planar note — the same applies. */
        public Triangle p0(float x, float y) {
            return p0(x, y, 0f);
        }

        public Triangle p0(float x, float y, float z) {
            p0x = x; p0y = y; p0z = z;
            return this;
        }

        /** Second vertex. */
        public Triangle p1(float x, float y) {
            return p1(x, y, 0f);
        }

        public Triangle p1(float x, float y, float z) {
            p1x = x; p1y = y; p1z = z;
            return this;
        }

        /** Third vertex. Either winding order is fine — {@code sdf_triangle} handles both. */
        public Triangle p2(float x, float y) {
            return p2(x, y, 0f);
        }

        public Triangle p2(float x, float y, float z) {
            p2x = x; p2y = y; p2z = z;
            return this;
        }

        /** All three vertices in one call. */
        public Triangle points(float x0, float y0, float x1, float y1, float x2, float y2) {
            return p0(x0, y0).p1(x1, y1).p2(x2, y2);
        }

        /** Flat fill colour. Defaults to opaque white. */
        public Triangle color(int argb) {
            this.argb = argb;
            this.argbEnd = argb;
            return this;
        }

        /**
         * Fills with a linear gradient evaluated <b>per pixel</b> — {@code start} at {@code t = 0},
         * {@code end} at {@code t = 1}, where {@code t = dot(p - origin, dir)}.
         *
         * <p>{@code dir} carries the scale as well as the direction: it is the axis direction divided by
         * the axis length, so a caller states the ramp once rather than normalising at every vertex.
         * Outside {@code [0, 1]} the ramp clamps, which makes a triangle that overhangs its own colour
         * span safe to submit.</p>
         *
         * <p><b>This is what a tessellated gradient is for.</b> Without it a mesh has to approximate a
         * ramp by subdividing until each flat cell is small enough to pass for one, which costs triangles
         * quadratically for a diagonal ramp and still shows seams wherever the cut lines are not the
         * ramp's own iso-lines.</p>
         */
        public Triangle gradient(int start, int end, float originX, float originY,
                                 float dirX, float dirY) {
            this.argb = start;
            this.argbEnd = end;
            this.gradient = true;
            this.gradOx = originX;
            this.gradOy = originY;
            this.gradDx = dirX;
            this.gradDy = dirY;
            return this;
        }

        /**
         * Softens the corners by dilating the sharp triangle outward — see {@code fill_coverage} in
         * {@code stroke.glsl} for why this grows the shape slightly rather than rounding it in place
         * the way {@code border-radius} does for a box. Defaults to {@code 0} (sharp corners).
         */
        public Triangle cornerRadius(float radius) {
            this.cornerRadius = radius;
            return this;
        }

        /** Edge softness. Defaults to {@code 1}, same convention as {@link Curve#feather}. */
        public Triangle feather(float feather) {
            this.feather = feather;
            return this;
        }

        /**
         * Optional transform, baked on the CPU at {@link #submit()} time — identical contract to
         * {@link Curve#pose}, including corner radius and feather scaling by the pose's uniform
         * scale (both are distances in the same space as the points, exactly like a stroke width).
         */
        public Triangle pose(Matrix4f pose) {
            this.pose = pose;
            return this;
        }

        /**
         * Writes this triangle as one instance record into the owning renderer's CPU accumulation
         * buffer. Queues only — call {@link CgVectorRenderer#flush()} to upload and draw.
         *
         * @throws IllegalStateException if {@link #begin()} was not called, or if {@link
         *                               #useMaterial(CgMaterial)} was never called
         */
        public CgVectorRenderer submit() {
            if (!begun) throw new IllegalStateException("CgVectorRenderer not begun");
            if (currentMaterial == null) throw new IllegalStateException(
                    "CgVectorRenderer.Triangle requires useMaterial(material) before submit() — "
                            + "without it, this renderer's buffer may not be attached to whatever material is bound");

            float ax = p0x, ay = p0y, az = p0z;
            float bx = p1x, by = p1y, bz = p1z;
            float cx = p2x, cy = p2y, cz = p2z;
            float radius = cornerRadius;
            float feath = feather;

            if (pose != null) {
                scratchP0.set(ax, ay, az);
                scratchP1.set(bx, by, bz);
                scratchP2.set(cx, cy, cz);
                pose.transformPosition(scratchP0);
                pose.transformPosition(scratchP1);
                pose.transformPosition(scratchP2);
                ax = scratchP0.x(); ay = scratchP0.y(); az = scratchP0.z();
                bx = scratchP1.x(); by = scratchP1.y(); bz = scratchP1.z();
                cx = scratchP2.x(); cy = scratchP2.y(); cz = scratchP2.z();

                // Corner radius and feather are distances in the same space as the points — a
                // scaled pose must scale them too, or a zoomed-in triangle keeps a fixed-pixel
                // radius that reads as sharper (relatively) the further the pose scales it up.
                float sx = (float) Math.sqrt(pose.m00() * pose.m00() + pose.m01() * pose.m01() + pose.m02() * pose.m02());
                float sy = (float) Math.sqrt(pose.m10() * pose.m10() + pose.m11() * pose.m11() + pose.m12() * pose.m12());
                float scale = Math.max(sx, sy);
                radius *= scale;
                feath *= scale;
            }

            float ox = gradOx, oy = gradOy, dxg = gradDx, dyg = gradDy;
            if (gradient && pose != null) {
                // The axis lives in the same space as the points, so a baked pose has to reach it too --
                // the origin as a position, the direction as a vector. Missing the second is the classic
                // version of this bug: the ramp stays put while the shape moves under it.
                scratchP0.set(ox, oy, 0f);
                pose.transformPosition(scratchP0);
                ox = scratchP0.x();
                oy = scratchP0.y();
                scratchP1.set(dxg, dyg, 0f);
                pose.transformDirection(scratchP1);
                // transformDirection scales the direction by the pose; t must stay in 0..1, and the
                // direction already encodes 1/length, so it has to be scaled the OTHER way.
                float scale = scratchP1.length();
                float unitX = scale > 1e-9f ? scratchP1.x() / scale : 0f;
                float unitY = scale > 1e-9f ? scratchP1.y() / scale : 0f;
                float magnitude = (float) Math.sqrt(dxg * dxg + dyg * dyg);
                float poseScale = magnitude > 1e-9f ? scale / magnitude : 1f;
                dxg = poseScale > 1e-9f ? unitX * magnitude / poseScale : 0f;
                dyg = poseScale > 1e-9f ? unitY * magnitude / poseScale : 0f;
            }

            accumWriter.beginRecord()
                    .vec3("p0", ax, ay, az)
                    .vec3("p1", bx, by, bz)
                    .vec3("p2", cx, cy, cz)
                    .color("color0", argb)
                    .color("color1", argbEnd)
                    .vec2("widths", radius, radius)
                    .float_("feather", feath)
                    .float_("flags", gradient ? (FLAG_FILL | FLAG_GRADIENT) : FLAG_FILL)
                    .vec4("gradient", ox, oy, dxg, dyg)
                    .endRecord();

            return CgVectorRenderer.this;
        }
    }

    /**
     * Uploads the accumulated instance data and issues one instanced draw call.
     *
     * <p>State-blind per {@link CgAbstractRenderer}'s contract: never touches
     * shader/texture/blend/depth/cull state.</p>
     */
    @Override
    public void flush() {
        if (!begun || accumStaging.isEmpty()) {
            accumStaging.reset();
            return;
        }
        try (CgProfiler.Scope ignored = CgProfiler.scope("curveRenderer.flush")) {
            int instanceCount = accumStaging.vertexCount();
            CgProfiler.count("curveRenderer.flush.count");
            CgProfiler.sample("curveRenderer.instances", instanceCount);

            try (CgProfiler.Scope ignored2 = CgProfiler.scope("curveRenderer.upload")) {
                GPU_BUFFER.uploadRaw(accumStaging.rawData(), accumStaging.rawCursor());
            }
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("curveRenderer.bindBuffer")) {
                GPU_BUFFER.bind();
            }
            try (CgProfiler.Scope ignored2 = CgProfiler.scope("curveRenderer.drawInstanced")) {
                CURVE_MESH.drawInstanced(instanceCount);
            }
            accumStaging.reset();
        }
    }

    /**
     * Unbinds {@link #currentMaterial}, if {@link #useMaterial(CgMaterial)} left one bound.
     * The mesh and shader buffer are static/registry-owned and outlive any single renderer.
     */
    @Override
    public void delete() {
        if (currentMaterial != null) {
            currentMaterial.unbind();
            currentMaterial = null;
        }
    }

    // Colour unpacking lives on CgBufferWriter#color, and the cubic-splitting maths in
    // CgCurveSplitter — the latter deliberately, because this class holds a static CgShaderBuffer
    // and touching it for a pure-maths helper initializes GL state.
}
