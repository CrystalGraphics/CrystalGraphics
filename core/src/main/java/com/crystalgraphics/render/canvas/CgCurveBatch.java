package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgShaderBufferRegistry;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.api.buffer.CgGpuType;
import com.crystalgraphics.render.property.CgPalette;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * A canvas's vector items: strokes ({@link Curve}), filled triangles ({@link Triangle}) and tessellation cells
 * ({@link Cell}), each one instance record drawn by its signed distance or exact area. What {@link CgCanvas#curve},
 * {@link CgCanvas#triangle} and {@link CgCanvas#cell} build.
 *
 * <pre>{@code
 * canvas.curve(CURVE).line(x0, y0, x1, y1).width(2f).color(argb).submit();
 * canvas.curve(CURVE).from(x0, y0).via(cx, cy).to(x1, y1).width(4f, 1f).colors(a, b).cap(CAP_ROUND, CAP_ARROW).submit();
 * canvas.curve(CURVE).cubic(x0, y0, c1x, c1y, c2x, c2y, x1, y1).width(2f).submit();     // split into quadratics
 * canvas.triangle(CURVE).points(x0, y0, x1, y1, x2, y2).color(argb).submit();
 * canvas.cell(FILL).points(x0, y0, x1, y1, x2, y2, x3, y3).softEdges(CELL_LEFT | CELL_RIGHT).color(argb).submit();
 * }</pre>
 *
 * <p>A material it draws declares the record; {@code crystalgraphics:shaders/curve.shader} is the reference:</p>
 *
 * <pre>{@code
 * #type none
 * #pragma cg_use curve      // CG_CURVE_WORLD_POS, CG_CURVE_P0..P2, _COLOR0/1, _WIDTHS, _FEATHER, _FLAGS
 * }</pre>
 *
 * <ul>
 *   <li>A stroke is a quadratic Bézier, whose distance has a closed form; a cubic is split on the CPU.</li>
 *   <li>Widths are half-widths in post-pose units: a pose scales them with the points (by its larger axis scale).
 *       The default feather, {@link #FEATHER_ANTIALIAS}, is the antialiasing filter and does not scale.</li>
 *   <li>Planar: distances are taken in XY, Z only orders.</li>
 * </ul>
 */
public final class CgCurveBatch {

    /** Fixed per-instance record format shared by every {@code CgCurveBatch} instance. */
    private static final CgBufferFormat INSTANCE_FORMAT = CgInstanceKind.CURVE.format();

    private static final String GPU_BUFFER_NAME = "CgCurveBatchInstances";

    /** The macro the {@code curve} token binds the record under; {@code cg_env.glsl}'s macros name it. */
    public static final String MACRO_NAME = "CURVE_DATA";

    /**
     * The default feather: {@value #AA_FILTER_PX} device pixels of antialiasing whatever the pose, as
     * {@code CG_QUAD_EDGE_FILTER} for quads, so a stroke's edge and a box's match. A feather set by hand is a softness
     * and scales with the pose.
     *
     * <pre>{@code
     * canvas.curve(CURVE).line(x0, y0, x1, y1).width(1f).color(argb).submit();     // antialiased
     * canvas.curve(CURVE).line(x0, y0, x1, y1).feather(4f).color(argb).submit();   // a glow
     * }</pre>
     */
    public static final float FEATHER_ANTIALIAS = -1f;

    /** What {@link #FEATHER_ANTIALIAS} resolves to. Equal to {@code CG_QUAD_EDGE_FILTER}. */
    private static final float AA_FILTER_PX = 1.5f;

    /** Butt cap: the stroke ends at {@code p0}/{@code p2}. The default. */
    public static final int CAP_BUTT = 0;
    /** Round cap — a half-disc of the local half-width is added at each end. */
    public static final int CAP_ROUND = 1;
    /** Square cap — the stroke is extended by its local half-width past each end. */
    public static final int CAP_SQUARE = 2;
    /** Arrowhead cap: a wedge past the end, wider than the stroke at its base. */
    public static final int CAP_ARROW = 3;

    /** Flags bit: a {@link Triangle}. Matches {@code CG_STROKE_FLAG_FILL} in {@code stroke.glsl}. */
    static final int FLAG_FILL = 16;

    /** Flags bit: {@code gradient} holds an axis. Matches {@code CG_STROKE_FLAG_GRADIENT}. */
    static final int FLAG_GRADIENT = 32;

    /**
     * Flags bits 6-7: which triangle edge is on the outline and antialiased; the others are seams, which a
     * feather would turn into soft lines. Matches {@code CG_STROKE_FILL_EDGE_SHIFT}.
     */
    static final int FILL_EDGE_SHIFT = 6;

    /** No edge on the outline: every edge a seam. */
    public static final int EDGE_NONE = 0;
    /** {@code p1 -> p2} is the silhouette. */
    public static final int EDGE_P1_P2 = 1;
    /** {@code p2 -> p0} is the silhouette. */
    public static final int EDGE_P2_P0 = 2;

    /** Flags bit: a {@link Cell}. Matches {@code CG_STROKE_FLAG_CELL} and {@code cg_env.glsl}'s hull test. */
    static final int FLAG_CELL = 256;

    /** Flags bits 9-12: a cell's soft edges. @see Cell#softEdges */
    static final int CELL_EDGE_SHIFT = 9;

    /**
     * Flags bits 13-14: this end is a split cubic's interior joint, cut hard and half-open so the two
     * segments partition it; two antialiased butt ends would overlap into a bright spot. Matches
     * {@code CG_STROKE_FLAG_JOINT_*}.
     */
    static final int FLAG_JOINT_START = 1 << 13;

    /** @see #FLAG_JOINT_START */
    static final int FLAG_JOINT_END = 1 << 14;


    /** Cell edge {@code p0 -> p1}. */
    public static final int CELL_TOP = 1;
    /** Cell edge {@code p1 -> p2}. */
    public static final int CELL_RIGHT = 2;
    /** Cell edge {@code p2 -> p3}. */
    public static final int CELL_BOTTOM = 4;
    /** Cell edge {@code p3 -> p0}. */
    public static final int CELL_LEFT = 8;

    /** The most quadratics one {@link Curve#cubic} splits into. */
    public static final int MAX_CUBIC_SEGMENTS = CgCurveSplitter.MAX_CUBIC_SEGMENTS;

    private static final int INITIAL_CAPACITY_INSTANCES = 64;


    /** The instance buffer the {@code curve} pragma token names, made at its first ask. @see CgQuadBatch */
    private static final class Buffer {
        static final CgShaderBuffer INSTANCES = CgShaderBufferRegistry.get()
                .getOrCreateInternal(GPU_BUFFER_NAME, INSTANCE_FORMAT, CgBindingPoints.CURVE_RENDERER, CgBufferLifetime.FRAME);
    }

    private final CgStagingBuffer accumStaging;
    private final CgBufferWriter accumWriter;

    private final Curve scratchCurve = new Curve();

    private final Cell scratchCell = new Cell();

    private final CgInstanceRun run;

    /** Field offsets into the record, resolved once. */
    private final int offP0, offP1, offP2, offColor0, offColor1, offWidths, offFeather, offFlags,
            offGradient, offClip, offNode;

    /**
     * The last pose's uniform scale, keyed on its components: {@code PoseStack} mutates one matrix in
     * place, so a reference check would go stale.
     */
    private float poseKeyA, poseKeyB, poseKeyC, poseKeyD, poseKeyE, poseKeyF;
    private float cachedPoseScale = 1f;
    private boolean poseScaleValid;

    private static float min3(float a, float b, float c) {
        return Math.min(a, Math.min(b, c));
    }

    private static float max3(float a, float b, float c) {
        return Math.max(a, Math.max(b, c));
    }

    private final CgCanvas canvas;
    private static final CgTexture[] NO_TEXTURES = new CgTexture[0];

    CgCurveBatch(CgCanvas canvas) {
        this.canvas = canvas;
        this.accumStaging = new CgStagingBuffer(INSTANCE_FORMAT.getFloatCount(), Math.max(1, INITIAL_CAPACITY_INSTANCES / 4));
        this.accumWriter = new CgBufferWriter(accumStaging, INSTANCE_FORMAT);
        this.run = new CgInstanceRun(CgInstanceKind.CURVE, canvas, accumStaging);
        this.offP0 = accumWriter.offsetOf("p0", CgGpuType.VEC3);
        this.offP1 = accumWriter.offsetOf("p1", CgGpuType.VEC3);
        this.offP2 = accumWriter.offsetOf("p2", CgGpuType.VEC3);
        this.offColor0 = accumWriter.offsetOf("color0", CgGpuType.VEC4);
        this.offColor1 = accumWriter.offsetOf("color1", CgGpuType.VEC4);
        this.offWidths = accumWriter.offsetOf("widths", CgGpuType.VEC2);
        this.offFeather = accumWriter.offsetOf("feather", CgGpuType.FLOAT);
        this.offFlags = accumWriter.offsetOf("flags", CgGpuType.FLOAT);
        this.offGradient = accumWriter.offsetOf("gradient", CgGpuType.VEC4);
        this.offClip = accumWriter.offsetOf("clip", CgGpuType.FLOAT);
        this.offNode = accumWriter.offsetOf("node", CgGpuType.FLOAT);
    }

    /** The instance buffer the {@code curve} pragma token names. Engine. */
    public static CgShaderBuffer instanceBuffer() {
        return Buffer.INSTANCES;
    }

    boolean pending() {
        return run.pending();
    }

    Curve curve(CgMaterial material) {
        return scratchCurve.reset().material(material);
    }

    Curve retainedCurve() {
        return new Curve();
    }

    /** {@link #FEATHER_ANTIALIAS} as the filter in post-pose units; a stated feather scales like a width. */
    private static float resolveFeather(float feather, float poseScale) {
        return feather < 0f ? AA_FILTER_PX : feather * poseScale;
    }

    /**
     * One stroke: build it and {@link #submit()} in one expression. Geometry is required ({@link #line},
     * {@link #to} or {@link #cubic}); the rest defaults to a half-width of 1, opaque white, butt caps and no pose.
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
        private int clip;
        private float node;
        private int spatial;

        private int packedCaps;

        private int cubicSegments;
        private final float[] cubicScratch = new float[MAX_CUBIC_SEGMENTS * 9];
        private final float[] cubicRaw = new float[8];
        private boolean cubicPending;

        @Nullable
        private CgMaterial material;
        @Nullable
        private CgRenderState state;

        private Curve() {
            reset();
        }

        /** What it draws with. */
        public Curve material(CgMaterial material) {
            this.material = material;
            return this;
        }

        /** The render state it draws under in place of its pass's. */
        public Curve state(@Nullable CgRenderState state) {
            this.state = state;
            return this;
        }

        Curve reset() {
            material = null;
            state = null;
            p0x = p0y = p0z = 0f;
            p1x = p1y = p1z = 0f;
            p2x = p2y = p2z = 0f;
            geometrySet = false;
            widthStart = 1f;
            widthEnd = 1f;
            argb0 = 0xFFFFFFFF;
            argb1 = 0xFFFFFFFF;
            feather = FEATHER_ANTIALIAS;
            capStart = CAP_BUTT;
            capEnd = CAP_BUTT;
            pose = null;
            clip = 0;
            node = 0f;
            spatial = 0;
            cubicSegments = 0;
            cubicPending = false;
            return this;
        }

        /** A straight segment: a quadratic with its control point at the middle. */
        public Curve line(float x0, float y0, float x1, float y1) {
            return from(x0, y0).via((x0 + x1) * 0.5f, (y0 + y1) * 0.5f).to(x1, y1);
        }

        /** Start point. */
        public Curve from(float x, float y) {
            return from(x, y, 0f);
        }

        /** Start point with a depth, which only orders. */
        public Curve from(float x, float y, float z) {
            p0x = x; p0y = y; p0z = z;
            return this;
        }

        /** The control point. */
        public Curve via(float x, float y) {
            return via(x, y, 0f);
        }

        public Curve via(float x, float y, float z) {
            p1x = x; p1y = y; p1z = z;
            return this;
        }

        /** End point; completes the geometry. */
        public Curve to(float x, float y) {
            return to(x, y, 0f);
        }

        public Curve to(float x, float y, float z) {
            p2x = x; p2y = y; p2z = z;
            geometrySet = true;
            cubicSegments = 0;
            return this;
        }

        /**
         * A cubic Bézier, split at {@link #submit()} into up to {@value #MAX_CUBIC_SEGMENTS} quadratics by
         * the pose's scale; a gentle S is one or two.
         */
        public Curve cubic(float x0, float y0,
                           float c1x, float c1y,
                           float c2x, float c2y,
                           float x1, float y1) {
            // Split at submit: the count depends on the pose, which may be set after.
            cubicRaw[0] = x0; cubicRaw[1] = y0;
            cubicRaw[2] = c1x; cubicRaw[3] = c1y;
            cubicRaw[4] = c2x; cubicRaw[5] = c2y;
            cubicRaw[6] = x1; cubicRaw[7] = y1;
            cubicPending = true;
            cubicSegments = 0;
            p0x = x0; p0y = y0;
            p2x = x1; p2y = y1;
            geometrySet = true;
            return this;
        }

        /** Half-width along the whole curve. */
        public Curve width(float halfWidth) {
            return width(halfWidth, halfWidth);
        }

        /** Half-width tapering from {@code p0} to {@code p2}. */
        public Curve width(float halfWidthStart, float halfWidthEnd) {
            this.widthStart = halfWidthStart;
            this.widthEnd = halfWidthEnd;
            return this;
        }

        /** Colour, ARGB. */
        public Curve color(int argb) {
            return colors(argb, argb);
        }

        /** Colour from {@code p0} to {@code p2}. */
        public Curve colors(int argbStart, int argbEnd) {
            this.argb0 = argbStart;
            this.argb1 = argbEnd;
            return this;
        }

        /** Edge softness in post-pose units. @see #FEATHER_ANTIALIAS */
        public Curve feather(float feather) {
            this.feather = feather;
            return this;
        }

        /** Cap at both ends: {@link #CAP_BUTT}, {@link #CAP_ROUND}, {@link #CAP_SQUARE} or {@link #CAP_ARROW}. */
        public Curve cap(int cap) {
            return cap(cap, cap);
        }

        /**
         * Cap per end: {@code cap(CAP_ROUND, CAP_ARROW)} for a wire pointing at its target. A split cubic takes
         * them at its own two ends only.
         */
        public Curve cap(int capStart, int capEnd) {
            this.capStart = capStart;
            this.capEnd = capEnd;
            return this;
        }

        /** A transform baked into the points at {@link #submit()}, scaling widths and a stated feather too. */
        public Curve pose(Matrix4f pose) {
            this.pose = pose;
            return this;
        }

        /** The {@link CgClipTable} entry it is clipped by; 0 is none. */
        public Curve clip(int entry) {
            this.clip = entry;
            return this;
        }

        /** Its spatial and effect nodes; 0 and 0 are the pass's own space at full opacity. @see CgPalette */
        public Curve node(int spatial, int effect) {
            this.node = CgPalette.pack(spatial, effect);
            this.spatial = spatial;
            return this;
        }

        /**
         * Queues it on its canvas; a cubic as a record per piece.
         *
         * @throws IllegalStateException without geometry or a material
         */
        public CgCanvas submit() {
            if (!geometrySet) throw new IllegalStateException(
                    "CgCurveBatch.Curve requires geometry — call line(...), to(...) or cubic(...) before submit()");
            if (material == null) throw new IllegalStateException("a curve with no material");

            float widthScale = pose != null ? poseScaleOf(pose) : 1f;
            float feath = resolveFeather(feather, widthScale);
            if (cubicPending) {
                cubicSegments = CgCurveSplitter.splitCubic(
                        cubicRaw[0], cubicRaw[1], cubicRaw[2], cubicRaw[3],
                        cubicRaw[4], cubicRaw[5], cubicRaw[6], cubicRaw[7],
                        cubicScratch, widthScale);
            }

            if (cubicSegments > 0) {
                for (int i = 0; i < cubicSegments; i++) {
                    int o = i * 9;
                    // The caller's caps at the cubic's own ends; joints cut hard (FLAG_JOINT_START).
                    int segStartCap = (i == 0) ? capStart : CAP_BUTT;
                    int segEndCap = (i == cubicSegments - 1) ? capEnd : CAP_BUTT;
                    packedCaps = CgCurveSplitter.packCaps(segStartCap, segEndCap);
                    if (i > 0) packedCaps |= FLAG_JOINT_START;
                    if (i < cubicSegments - 1) packedCaps |= FLAG_JOINT_END;
                    // Taper and colour over the whole cubic, each piece its slice.
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
                            feath);
                }
            } else {
                packedCaps = CgCurveSplitter.packCaps(capStart, capEnd);
                writeRecord(p0x, p0y, p0z, p1x, p1y, p1z, p2x, p2y, p2z,
                        widthStart * widthScale, widthEnd * widthScale,
                        argb0, argb1, feath);
            }

            return canvas;
        }

        private void writeRecord(float ax, float ay, float az,
                                 float bx, float by, float bz,
                                 float cx, float cy, float cz,
                                 float wStart, float wEnd,
                                 int colorStart, int colorEnd, float feath) {
            if (pose != null) {
                // JOML's transformPosition, inlined: this runs per segment.
                float m00 = pose.m00(), m10 = pose.m10(), m20 = pose.m20(), m30 = pose.m30();
                float m01 = pose.m01(), m11 = pose.m11(), m21 = pose.m21(), m31 = pose.m31();
                float m02 = pose.m02(), m12 = pose.m12(), m22 = pose.m22(), m32 = pose.m32();
                float nax = m00 * ax + m10 * ay + m20 * az + m30;
                float nay = m01 * ax + m11 * ay + m21 * az + m31;
                float naz = m02 * ax + m12 * ay + m22 * az + m32;
                float nbx = m00 * bx + m10 * by + m20 * bz + m30;
                float nby = m01 * bx + m11 * by + m21 * bz + m31;
                float nbz = m02 * bx + m12 * by + m22 * bz + m32;
                float ncx = m00 * cx + m10 * cy + m20 * cz + m30;
                float ncy = m01 * cx + m11 * cy + m21 * cz + m31;
                float ncz = m02 * cx + m12 * cy + m22 * cz + m32;
                ax = nax; ay = nay; az = naz;
                bx = nbx; by = nby; bz = nbz;
                cx = ncx; cy = ncy; cz = ncz;
            }

            // A square cap reaches a half-width past the end along the tangent and across it: 1.41 half-widths.
            float reach = Math.max(wStart, wEnd) * 1.5f + feath + 1f;
            run.item(material, state, NO_TEXTURES, 0);
            run.spatial(spatial);
            run.bounds(min3(ax, bx, cx) - reach, min3(ay, by, cy) - reach, max3(ax, bx, cx) + reach,
                    max3(ay, by, cy) + reach);
            accumWriter.beginRecord()
                    .vec3At(offP0, ax, ay, az)
                    .vec3At(offP1, bx, by, bz)
                    .vec3At(offP2, cx, cy, cz)
                    .colorAt(offColor0, colorStart)
                    .colorAt(offColor1, colorEnd)
                    .vec2At(offWidths, wStart, wEnd)
                    .floatAt(offFeather, feath)
                    .floatAt(offFlags, packedCaps)
                    .vec4At(offGradient, 0f, 0f, 0f, 0f)
                    .floatAt(offClip, clip)
                    .floatAt(offNode, node)
                    .endRecord();
        }
    }

    private final Triangle scratchTriangle = new Triangle();

    Triangle triangle(CgMaterial material) {
        return scratchTriangle.reset().material(material);
    }

    Triangle retainedTriangle() {
        return new Triangle();
    }

    /**
     * One filled triangle: the same record as a stroke, read as a fill. Defaults to opaque white, sharp
     * corners and the antialiasing feather; either winding.
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
        private int silhouetteEdge;
        private float feather;
        private Matrix4f pose;
        private int clip;
        private float node;
        private int spatial;

        private final Vector3f scratchP0 = new Vector3f();
        private final Vector3f scratchP1 = new Vector3f();
        private final Vector3f scratchP2 = new Vector3f();

        @Nullable
        private CgMaterial material;
        @Nullable
        private CgRenderState state;

        private Triangle() {
            reset();
        }

        /** What it draws with. */
        public Triangle material(CgMaterial material) {
            this.material = material;
            return this;
        }

        /** The render state it draws under in place of its pass's. */
        public Triangle state(@Nullable CgRenderState state) {
            this.state = state;
            return this;
        }

        Triangle reset() {
            material = null;
            state = null;
            p0x = p0y = p0z = 0f;
            p1x = p1y = p1z = 0f;
            p2x = p2y = p2z = 0f;
            argb = 0xFFFFFFFF;
            argbEnd = 0xFFFFFFFF;
            gradient = false;
            gradOx = gradOy = gradDx = gradDy = 0f;
            cornerRadius = 0f;
            silhouetteEdge = EDGE_NONE;
            feather = FEATHER_ANTIALIAS;
            pose = null;
            clip = 0;
            node = 0f;
            spatial = 0;
            return this;
        }

        /** First vertex. */
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

        /** Third vertex. */
        public Triangle p2(float x, float y) {
            return p2(x, y, 0f);
        }

        public Triangle p2(float x, float y, float z) {
            p2x = x; p2y = y; p2z = z;
            return this;
        }

        /** All three vertices. */
        public Triangle points(float x0, float y0, float x1, float y1, float x2, float y2) {
            return p0(x0, y0).p1(x1, y1).p2(x2, y2);
        }

        /** Flat colour, ARGB. */
        public Triangle color(int argb) {
            this.argb = argb;
            this.argbEnd = argb;
            return this;
        }

        /**
         * A linear gradient per pixel: {@code start} at {@code t = 0}, {@code end} at 1, where
         * {@code t = dot(p - origin, dir)} and clamps. {@code dir} is the axis over its length squared, so a ramp is stated once.
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
         * The one edge on the shape's outline, antialiased; the other two stay hard. For a tessellated mesh,
         * whose other edges are seams; a lone triangle leaves it {@link #EDGE_NONE}.
         *
         * @param edge {@link #EDGE_NONE}, {@link #EDGE_P1_P2} or {@link #EDGE_P2_P0}
         */
        public Triangle silhouetteEdge(int edge) {
            this.silhouetteEdge = edge;
            return this;
        }

        /** Rounds the corners by growing the triangle outward by {@code radius}. 0, sharp, by default. */
        public Triangle cornerRadius(float radius) {
            this.cornerRadius = radius;
            return this;
        }

        /** Edge softness in post-pose units. @see #FEATHER_ANTIALIAS */
        public Triangle feather(float feather) {
            this.feather = feather;
            return this;
        }

        /**
         * A transform baked into the points at {@link #submit()}, scaling the corner radius and a stated feather too.
         */
        public Triangle pose(Matrix4f pose) {
            this.pose = pose;
            return this;
        }

        /** The {@link CgClipTable} entry it is clipped by; 0 is none. */
        public Triangle clip(int entry) {
            this.clip = entry;
            return this;
        }

        /** Its spatial and effect nodes; 0 and 0 are the pass's own space at full opacity. @see CgPalette */
        public Triangle node(int spatial, int effect) {
            this.node = CgPalette.pack(spatial, effect);
            this.spatial = spatial;
            return this;
        }

        /**
         * Queues it on its canvas.
         *
         * @throws IllegalStateException without a material
         */
        public CgCanvas submit() {
            if (material == null) throw new IllegalStateException("a triangle with no material");

            float ax = p0x, ay = p0y, az = p0z;
            float bx = p1x, by = p1y, bz = p1z;
            float cx = p2x, cy = p2y, cz = p2z;
            float radius = cornerRadius;
            float poseScale = 1f;

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

                poseScale = poseScaleOf(pose);
                radius *= poseScale;
            }
            float feath = resolveFeather(feather, poseScale);

            float ox = gradOx, oy = gradOy, dxg = gradDx, dyg = gradDy;
            if (gradient && pose != null) {
                float[] axis = poseGradient(pose, ox, oy, dxg, dyg);
                ox = axis[0]; oy = axis[1]; dxg = axis[2]; dyg = axis[3];
            }

            float reach = radius + feath + 1f;
            run.item(material, state, NO_TEXTURES, 0);
            run.spatial(spatial);
            run.bounds(min3(ax, bx, cx) - reach, min3(ay, by, cy) - reach, max3(ax, bx, cx) + reach,
                    max3(ay, by, cy) + reach);
            accumWriter.beginRecord()
                    .vec3At(offP0, ax, ay, az)
                    .vec3At(offP1, bx, by, bz)
                    .vec3At(offP2, cx, cy, cz)
                    .colorAt(offColor0, argb)
                    .colorAt(offColor1, argbEnd)
                    .vec2At(offWidths, radius, radius)
                    .floatAt(offFeather, feath)
                    .floatAt(offFlags, (gradient ? (FLAG_FILL | FLAG_GRADIENT) : FLAG_FILL)
                            | (silhouetteEdge << FILL_EDGE_SHIFT))
                    .vec4At(offGradient, ox, oy, dxg, dyg)
                    .floatAt(offClip, clip)
                    .floatAt(offNode, node)
                    .endRecord();

            return canvas;
        }
    }

    Cell cell(CgMaterial material) {
        return scratchCell.reset().material(material);
    }

    Cell retainedCell() {
        return new Cell();
    }

    /**
     * One span of a scanline tessellation: a convex quad, corners in order either winding, whose edges are
     * each the shape's outline or a seam with the next cell.
     *
     * <pre>
     *      p0 ────────── p1        top: a cut (seam)
     *      /              \        right: the contour (outline)
     *     p3 ──────────────── p2    bottom: a cut (seam); left: the contour (outline)
     * </pre>
     *
     * <ul>
     *   <li>A {@linkplain #softEdges soft} edge covers the exact area of the pixel inside it; a seam is decided at the
     *       pixel centre, half-open, so exactly one of two neighbours claims each pixel on it.</li>
     *   <li>Use cells rather than two triangles: a triangle knows one wall, and its seams show as bright rows.</li>
     *   <li>Coverage is in post-pose units as pixels: for a 2D material in window pixels, as {@code gui_curve.shader}.</li>
     * </ul>
     */
    public final class Cell {

        private float x0, y0, x1, y1, x2, y2, x3, y3;
        private int argb;
        private int argbEnd;
        private boolean gradient;
        private float gradOx, gradOy, gradDx, gradDy;
        private int softEdges;
        private Matrix4f pose;
        private int clip;
        private float node;
        private int spatial;

        private final Vector3f scratch = new Vector3f();

        @Nullable
        private CgMaterial material;
        @Nullable
        private CgRenderState state;

        private Cell() {
            reset();
        }

        /** What it draws with. */
        public Cell material(CgMaterial material) {
            this.material = material;
            return this;
        }

        /** The render state it draws under in place of its pass's. */
        public Cell state(@Nullable CgRenderState state) {
            this.state = state;
            return this;
        }

        Cell reset() {
            material = null;
            state = null;
            x0 = y0 = x1 = y1 = x2 = y2 = x3 = y3 = 0f;
            argb = 0xFFFFFFFF;
            argbEnd = 0xFFFFFFFF;
            gradient = false;
            gradOx = gradOy = gradDx = gradDy = 0f;
            softEdges = 0;
            pose = null;
            clip = 0;
            node = 0f;
            spatial = 0;
            return this;
        }

        /** The four corners, in order round the shape. */
        public Cell points(float x0, float y0, float x1, float y1,
                           float x2, float y2, float x3, float y3) {
            this.x0 = x0; this.y0 = y0;
            this.x1 = x1; this.y1 = y1;
            this.x2 = x2; this.y2 = y2;
            this.x3 = x3; this.y3 = y3;
            return this;
        }

        /** Flat colour, ARGB. */
        public Cell color(int argb) {
            this.argb = argb;
            this.argbEnd = argb;
            return this;
        }

        /** A per-pixel linear gradient. @see Triangle#gradient */
        public Cell gradient(int start, int end, float originX, float originY,
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
         * The edges on the outline, antialiased: any of {@link #CELL_TOP}, {@link #CELL_RIGHT},
         * {@link #CELL_BOTTOM}, {@link #CELL_LEFT}. The rest are seams. None by default.
         */
        public Cell softEdges(int mask) {
            this.softEdges = mask & 15;
            return this;
        }

        /** A transform baked into the corners at {@link #submit()}. */
        public Cell pose(Matrix4f pose) {
            this.pose = pose;
            return this;
        }

        /** The {@link CgClipTable} entry it is clipped by; 0 is none. */
        public Cell clip(int entry) {
            this.clip = entry;
            return this;
        }

        /** Its spatial and effect nodes; 0 and 0 are the pass's own space at full opacity. @see CgPalette */
        public Cell node(int spatial, int effect) {
            this.node = CgPalette.pack(spatial, effect);
            this.spatial = spatial;
            return this;
        }

        /**
         * Queues it on its canvas.
         *
         * @throws IllegalStateException without a material
         */
        public CgCanvas submit() {
            if (material == null) throw new IllegalStateException("a cell with no material");

            float ax = x0, ay = y0, bx = x1, by = y1, cx = x2, cy = y2, dx = x3, dy = y3;
            float z = 0f;
            if (pose != null) {
                scratch.set(ax, ay, 0f); pose.transformPosition(scratch); ax = scratch.x(); ay = scratch.y(); z = scratch.z();
                scratch.set(bx, by, 0f); pose.transformPosition(scratch); bx = scratch.x(); by = scratch.y();
                scratch.set(cx, cy, 0f); pose.transformPosition(scratch); cx = scratch.x(); cy = scratch.y();
                scratch.set(dx, dy, 0f); pose.transformPosition(scratch); dx = scratch.x(); dy = scratch.y();
            }

            float ox = gradOx, oy = gradOy, dxg = gradDx, dyg = gradDy;
            if (gradient && pose != null) {
                float[] axis = poseGradient(pose, ox, oy, dxg, dyg);
                ox = axis[0]; oy = axis[1]; dxg = axis[2]; dyg = axis[3];
            }

            run.item(material, state, NO_TEXTURES, 0);
            run.spatial(spatial);
            run.bounds(Math.min(min3(ax, bx, cx), dx) - 1f, Math.min(min3(ay, by, cy), dy) - 1f,
                    Math.max(max3(ax, bx, cx), dx) + 1f, Math.max(max3(ay, by, cy), dy) + 1f);
            accumWriter.beginRecord()
                    .vec3At(offP0, ax, ay, z)
                    .vec3At(offP1, bx, by, z)
                    .vec3At(offP2, cx, cy, z)
                    .colorAt(offColor0, argb)
                    .colorAt(offColor1, argbEnd)
                    .vec2At(offWidths, dx, dy)
                    .floatAt(offFeather, 0f)
                    .floatAt(offFlags, (gradient ? (FLAG_CELL | FLAG_GRADIENT) : FLAG_CELL)
                            | (softEdges << CELL_EDGE_SHIFT))
                    .vec4At(offGradient, ox, oy, dxg, dyg)
                    .floatAt(offClip, clip)
                    .floatAt(offNode, node)
                    .endRecord();

            return canvas;
        }
    }

    private final float[] gradientScratch = new float[4];
    private final Vector3f gradientOrigin = new Vector3f();
    private final Vector3f gradientDirection = new Vector3f();

    /**
     * A gradient axis through a pose: the origin as a point, the direction scaled the inverse way, since it
     * already holds 1/length.
     */
    private float[] poseGradient(Matrix4f pose, float ox, float oy, float dxg, float dyg) {
        gradientOrigin.set(ox, oy, 0f);
        pose.transformPosition(gradientOrigin);
        gradientDirection.set(dxg, dyg, 0f);
        pose.transformDirection(gradientDirection);
        float scale = gradientDirection.length();
        float unitX = scale > 1e-9f ? gradientDirection.x() / scale : 0f;
        float unitY = scale > 1e-9f ? gradientDirection.y() / scale : 0f;
        float magnitude = (float) Math.sqrt(dxg * dxg + dyg * dyg);
        float poseScale = magnitude > 1e-9f ? scale / magnitude : 1f;
        gradientScratch[0] = gradientOrigin.x();
        gradientScratch[1] = gradientOrigin.y();
        gradientScratch[2] = poseScale > 1e-9f ? unitX * magnitude / poseScale : 0f;
        gradientScratch[3] = poseScale > 1e-9f ? unitY * magnitude / poseScale : 0f;
        return gradientScratch;
    }

    /** The pose's larger axis scale, so an anisotropic zoom never thins a stroke away. */
    private float poseScaleOf(Matrix4f pose) {
        if (poseScaleValid && pose.m00() == poseKeyA && pose.m01() == poseKeyB && pose.m02() == poseKeyC
                && pose.m10() == poseKeyD && pose.m11() == poseKeyE && pose.m12() == poseKeyF) {
            return cachedPoseScale;
        }
        poseKeyA = pose.m00(); poseKeyB = pose.m01(); poseKeyC = pose.m02();
        poseKeyD = pose.m10(); poseKeyE = pose.m11(); poseKeyF = pose.m12();
        float sx = (float) Math.sqrt(poseKeyA * poseKeyA + poseKeyB * poseKeyB + poseKeyC * poseKeyC);
        float sy = (float) Math.sqrt(poseKeyD * poseKeyD + poseKeyE * poseKeyE + poseKeyF * poseKeyF);
        cachedPoseScale = Math.max(sx, sy);
        poseScaleValid = true;
        return cachedPoseScale;
    }

}
