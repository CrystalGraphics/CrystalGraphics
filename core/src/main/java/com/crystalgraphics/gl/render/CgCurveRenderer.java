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
 * Instanced curve/stroke renderer — the line-and-curve counterpart to {@link CgQuadRenderer},
 * built on the same SSBO/TBO-backed instancing foundation and deliberately mirroring its API
 * shape method-for-method.
 *
 * <h3>The primitive is a quadratic Bézier, and that is the whole design</h3>
 * <p>Everything this renderer draws is one quadratic Bézier stroke:</p>
 * <ul>
 *   <li>A <b>straight line</b> is a quadratic whose control point is the midpoint.</li>
 *   <li>A <b>cubic</b> splits into 1–{@value #MAX_CUBIC_SEGMENTS} quadratics on the CPU
 *       ({@link Curve#cubic}), exactly as font rasterizers do.</li>
 *   <li><b>Arcs, polylines and rounded elbows</b> are sequences of quadratics.</li>
 * </ul>
 *
 * <p>The reason to stop at quadratic rather than take cubic as the primitive is that a quadratic
 * has an <em>exact analytic</em> signed distance function — {@code sdf_bezier} in
 * {@code shaders/lib/sdf.glsl}, one closed-form cubic solve. A cubic's distance is a quintic with
 * no closed form, so making cubic the primitive would mean approximating per pixel forever.
 * Splitting once on the CPU is both cheaper and exact.</p>
 *
 * <p>That is also why the class is {@code CgCurveRenderer} and not {@code CgLineRenderer}:
 * {@code curve().line(...)} is a natural convenience, whereas {@code line().curve(...)} would read
 * as a contradiction.</p>
 *
 * <h3>Fixed instance schema</h3>
 * <pre>
 * vec3 p0, p1, p2       // quadratic control points, pose baked in (see Curve#pose)
 * vec4 color0, color1   // gradient along the curve, p0 -&gt; p2
 * vec2 widths           // start/end HALF-width — tapered strokes
 * float feather         // edge softness, in the same units as widths
 * float flags           // cap style, packed (see CAP_*)
 * </pre>
 * <p>Under STD430 each {@code vec3} pads to 16 bytes, so this record occupies exactly 96 bytes —
 * the same stride as {@link CgQuadRenderer}'s, with no trailing waste. The three padding floats
 * are the price of keeping the control points {@code vec3}; see the planar note below for why
 * they are {@code vec3} at all.</p>
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
public final class CgCurveRenderer extends CgAbstractRenderer {

    /** Same shared unit-quad vertex format as {@link CgQuadRenderer} — the mesh is a bounding box carrier. */
    private static final CgVertexFormat CURVE_MESH_FORMAT = CgVertexFormat.POS2_UV2_COL4UB;

    /** Fixed per-instance record format shared by every {@code CgCurveRenderer} instance. */
    private static final CgBufferFormat INSTANCE_FORMAT = CgBufferFormat
            .builder("CurveInstance", CgBufferFormat.MemoryLayout.STD430)
            .vec3("p0").vec3("p1").vec3("p2")
            .vec4("color0").vec4("color1")
            .vec2("widths")
            .float_("feather")
            .float_("flags")
            .build();

    private static final String GPU_BUFFER_NAME = "CgCurveRendererInstances";

    /**
     * Fixed {@code attach()} macro name every {@code CgCurveRenderer} consumer uses. Shaders declare
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
     * Shared static shader buffer — one SSBO/TBO backs every {@code CgCurveRenderer} instance,
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
     * Creates a new {@code CgCurveRenderer}. Each instance owns its own CPU accumulation buffer but
     * shares the static mesh, format and GPU buffer with every other instance.
     */
    public static CgCurveRenderer create() {
        CgStagingBuffer staging = new CgStagingBuffer(INSTANCE_FORMAT.getFloatCount(),
                Math.max(1, INITIAL_CAPACITY_INSTANCES / 4));
        return new CgCurveRenderer(staging, new CgBufferWriter(staging, INSTANCE_FORMAT));
    }

    private CgCurveRenderer(CgStagingBuffer accumStaging, CgBufferWriter accumWriter) {
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
    public CgCurveRenderer useMaterial(CgMaterial material) {
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
        private int cap;
        private Matrix4f pose;

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
            cap = CAP_BUTT;
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

        /** Cap style — one of {@link #CAP_BUTT}, {@link #CAP_ROUND}, {@link #CAP_SQUARE}. */
        public Curve cap(int cap) {
            this.cap = cap;
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
         * <p>Queues only. Call {@link CgCurveRenderer#flush()} to upload and draw.</p>
         *
         * @throws IllegalStateException if {@link #begin()} was not called, if no geometry was set,
         *                               or if {@link #useMaterial(CgMaterial)} was never called
         */
        public CgCurveRenderer submit() {
            if (!begun) throw new IllegalStateException("CgCurveRenderer not begun");
            if (!geometrySet) throw new IllegalStateException(
                    "CgCurveRenderer.Curve requires geometry — call line(...), to(...) or cubic(...) before submit()");
            if (currentMaterial == null) throw new IllegalStateException(
                    "CgCurveRenderer.Curve requires useMaterial(material) before submit() — "
                            + "without it, this renderer's buffer may not be attached to whatever material is bound");

            float widthScale = poseScale();

            if (cubicSegments > 0) {
                for (int i = 0; i < cubicSegments; i++) {
                    int o = i * 9;
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
                writeRecord(p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z,
                        widthStart * widthScale, widthEnd * widthScale,
                        argb0, argb1, feather * widthScale);
            }

            return CgCurveRenderer.this;
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
                    .float_("flags", cap)
                    .endRecord();
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
