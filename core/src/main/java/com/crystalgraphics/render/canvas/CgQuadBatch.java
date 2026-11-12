package com.crystalgraphics.render.canvas;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.api.buffer.CgGpuType;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.gpu.resource.CgShaderBuffer;
import com.crystalgraphics.gpu.resource.CgShaderBufferRegistry;
import com.crystalgraphics.gpu.staging.CgBufferWriter;
import com.crystalgraphics.gpu.staging.CgStagingBuffer;
import com.crystalgraphics.render.draw.CgBindingTable;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.property.CgPalette;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import javax.annotation.Nullable;

/**
 * A canvas's quads: what {@link CgCanvas#quad} builds. One instance record per quad ({@link CgQuadRecord}), batched
 * by the canvas into draws on {@code CgMesh.quads}.
 *
 * <pre>{@code
 * canvas.quad(material).at(x, y).size(w, h).uv(u0, v0, u1, v1).color(argb).submit();       // flat
 * canvas.quad(material).at(-w / 2, -h / 2).size(w, h).pose(spin).submit();                // transformed
 * canvas.quad(glyphs).texture(0, page).atlasLayer(3).custom0(stroke).submit();            // per-quad parameters
 * canvas.quad(spark).world(x, y, z).pose(facingCamera).at(-0.1f, -0.1f).size(0.2f, 0.2f).submit();
 * }</pre>
 *
 * <p>A material it draws declares the record, which {@code cg_env.glsl}'s macros read:</p>
 *
 * <pre>{@code
 * #type none
 * #pragma cg_use quad
 * ...
 * gl_Position = cg_ProjMatrix * cg_ViewMatrix * vec4(CG_QUAD_WORLD_POS, 1.0);
 * o.uv = CG_QUAD_UV;
 * o.color = CG_QUAD_COLOR;
 * }</pre>
 *
 * <p>The pragma attaches the record during parsing, and the parser rejects a shader that uses {@code CG_QUAD_*} or
 * {@code QUAD_DATA} without it.</p>
 */
public final class CgQuadBatch {

    private static final CgBufferLayout INSTANCE_FORMAT = CgInstanceKind.QUAD.format();
    private static final String GPU_BUFFER_NAME = "CgQuadBatchInstances";
    /** The macro name the {@code quad} token binds the record under; {@code cg_env.glsl}'s macros name it. */
    public static final String MACRO_NAME = "QUAD_DATA";
    private static final int INITIAL_CAPACITY_INSTANCES = 64;

    /** The instance buffer the {@code quad} pragma token names, made at its first ask, after the graphics start. */
    private static final class Buffer {
        static final CgShaderBuffer INSTANCES = CgShaderBufferRegistry.get()
                .getOrCreateInternal(GPU_BUFFER_NAME, INSTANCE_FORMAT, CgBindingPoints.QUAD_RENDERER, CgBufferLifetime.FRAME);
    }

    /** The instance buffer the {@code quad} pragma token names. Engine. */
    public static CgShaderBuffer instanceBuffer() {
        return Buffer.INSTANCES;
    }

    private final CgCanvas canvas;
    private final CgStagingBuffer staging;
    private final CgBufferWriter writer;
    private final CgInstanceRun run;
    private final Quad scratch = new Quad();
    /** Field offsets into the record, resolved once: a named write per field per glyph was the text path's cost. */
    private final int offOrigin, offFade, offRight, offUp, offUv0, offUv1, offColor, offAtlasLayer;
    private final int offCustom0, offCustom1, offCustom2, offClip, offNode;

    CgQuadBatch(CgCanvas canvas) {
        this.canvas = canvas;
        this.staging = new CgStagingBuffer(INSTANCE_FORMAT.getFloatCount(), Math.max(1, INITIAL_CAPACITY_INSTANCES / 4));
        this.writer = new CgBufferWriter(staging, INSTANCE_FORMAT);
        this.run = new CgInstanceRun(CgInstanceKind.QUAD, canvas, staging);
        this.offOrigin = writer.offsetOf("origin", CgGpuType.VEC3);
        this.offFade = writer.offsetOf("fade", CgGpuType.FLOAT);
        this.offRight = writer.offsetOf("right", CgGpuType.VEC3);
        this.offUp = writer.offsetOf("up", CgGpuType.VEC3);
        this.offUv0 = writer.offsetOf("uv0", CgGpuType.VEC2);
        this.offUv1 = writer.offsetOf("uv1", CgGpuType.VEC2);
        this.offColor = writer.offsetOf("color", CgGpuType.VEC4);
        this.offAtlasLayer = writer.offsetOf("atlasLayer", CgGpuType.FLOAT);
        this.offCustom0 = writer.offsetOf("custom0", CgGpuType.VEC4);
        this.offCustom1 = writer.offsetOf("custom1", CgGpuType.VEC4);
        this.offCustom2 = writer.offsetOf("custom2", CgGpuType.FLOAT);
        this.offClip = writer.offsetOf("clip", CgGpuType.FLOAT);
        this.offNode = writer.offsetOf("node", CgGpuType.FLOAT);
    }

    Quad quad(CgMaterial material) {
        return scratch.reset().material(material);
    }

    Quad retainedQuad() {
        return new Quad();
    }

    boolean pending() {
        return run.pending();
    }

    void records(CgMaterial material, @Nullable CgRenderState state, @Nullable CgTexture texture, float[] src, int from,
                 int count, int spatial) {
        if (count <= 0) return;
        float x0 = Float.POSITIVE_INFINITY, y0 = Float.POSITIVE_INFINITY;
        float x1 = Float.NEGATIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
        int stride = CgQuadRecord.FLOATS;
        for (int o = from * stride, end = (from + count) * stride; o < end; o += stride) {
            float ox = src[o + CgQuadRecord.ORIGIN], oy = src[o + CgQuadRecord.ORIGIN + 1];
            float rx = src[o + CgQuadRecord.RIGHT], ry = src[o + CgQuadRecord.RIGHT + 1];
            float ux = src[o + CgQuadRecord.UP], uy = src[o + CgQuadRecord.UP + 1];
            // A pixel past the corners, as a built quad's.
            x0 = Math.min(x0, ox + Math.min(0f, rx) + Math.min(0f, ux) - 1f);
            y0 = Math.min(y0, oy + Math.min(0f, ry) + Math.min(0f, uy) - 1f);
            x1 = Math.max(x1, ox + Math.max(0f, rx) + Math.max(0f, ux) + 1f);
            y1 = Math.max(y1, oy + Math.max(0f, ry) + Math.max(0f, uy) + 1f);
        }
        if (texture != null) recordTexture[0] = texture;
        run.item(material, state, texture != null ? recordTexture : NO_TEXTURES, NO_SAMPLERS, texture != null ? 1 : 0);
        run.spatial(spatial);
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.GL, "canvas.quadRecords")) {
            CgTrace.add(CgChannels.GL, "canvas.quads", count);
            run.records(src, from * stride, count, x0, y0, x1, y1);
        }
    }

    private static final CgTexture[] NO_TEXTURES = new CgTexture[0];
    private static final CgSampler[] NO_SAMPLERS = new CgSampler[CgBindingTable.MAX_TEXTURES];
    /** Unit 0 of a run of baked records. */
    private final CgTexture[] recordTexture = new CgTexture[1];

    /**
     * One quad: build it and {@link #submit()} in one expression. Allocation-free; a retained one
     * ({@link CgCanvas#retainedQuad}) is the caller's to hold across frames.
     *
     * <ul>
     *   <li>{@link #size} is required; {@code at} defaults to the origin, {@code uv} to the whole texture, {@code color}
     *       to opaque white.</li>
     *   <li>{@link #pose} is baked on the CPU into the record's corner and edges: rotation, scale and skew act on the
     *       quad as on its four corners.</li>
     *   <li>{@link #world} anchors it at an absolute position, in doubles, made relative to the canvas's
     *       {@linkplain CgCanvas#origin origin}: where {@code at} and the pose place it from.</li>
     * </ul>
     */
    public final class Quad {

        private CgMaterial material;
        @Nullable
        private CgRenderState state;
        private final CgTexture[] textures = new CgTexture[CgBindingTable.MAX_TEXTURES];
        private final CgSampler[] samplers = new CgSampler[CgBindingTable.MAX_TEXTURES];
        private int textureUnits;
        private float x, y, z;
        private float width, height;
        private boolean sizeSet;
        private float u0, v0, u1, v1;
        private int argb;
        private float fade;
        private float atlasLayer;
        private float c0x, c0y, c0z, c0w;
        private float c1x, c1y, c1z, c1w;
        private float c2;
        private int clip;
        private float node;
        private int spatial;
        @Nullable
        private Matrix4f pose;
        private boolean anchored;
        private double anchorX, anchorY, anchorZ;

        private final Vector3f scratchOrigin = new Vector3f();
        private final Vector3f scratchRight = new Vector3f();
        private final Vector3f scratchUp = new Vector3f();
        private final float[] placed = new float[9];

        private Quad() {
            reset();
        }

        Quad reset() {
            material = null;
            state = null;
            for (int i = 0; i < textureUnits; i++) {
                textures[i] = null;
                samplers[i] = null;
            }
            textureUnits = 0;
            x = y = z = 0f;
            width = height = 0f;
            sizeSet = false;
            u0 = v0 = 0f;
            u1 = v1 = 1f;
            argb = 0xFFFFFFFF;
            fade = 0f;
            atlasLayer = 0f;
            c0x = c0y = c0z = c0w = 0f;
            c1x = c1y = c1z = c1w = 0f;
            c2 = 0f;
            clip = 0;
            node = 0f;
            spatial = 0;
            pose = null;
            anchored = false;
            return this;
        }

        /** What it draws with. */
        public Quad material(CgMaterial material) {
            this.material = material;
            return this;
        }

        /** The render state it draws under in place of its pass's: for a slot a shader cannot decide, as text's depth. */
        public Quad state(@Nullable CgRenderState state) {
            this.state = state;
            return this;
        }

        /** {@code texture} at {@code unit}, in place of the material's own there, sampled as its spec says. */
        public Quad texture(int unit, CgTexture texture) {
            return texture(unit, texture, null);
        }

        /**
         * {@code texture} at {@code unit}, sampled through {@code sampler} in place of its spec's filter and wrap; null
         * samples as the spec says. Quads differing only in sampler are different draws.
         *
         * <pre>{@code
         * canvas.quad(image).texture(0, layer, CgSampler.BILINEAR_CLAMP).at(x, y).size(w, h).pose(turn).submit();
         * }</pre>
         */
        public Quad texture(int unit, CgTexture texture, @Nullable CgSampler sampler) {
            textures[unit] = texture;
            samplers[unit] = sampler;
            textureUnits = Math.max(textureUnits, unit + 1);
            return this;
        }

        /** Its corner, in the pass's space or from its {@link #world} anchor. */
        public Quad at(float x, float y) {
            return at(x, y, 0f);
        }

        /** Its corner with a depth: a translation along Z, not a tilt; a tilt is {@link #pose}'s. */
        public Quad at(float x, float y, float z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** Its extent. Required. */
        public Quad size(float width, float height) {
            this.width = width;
            this.height = height;
            this.sizeSet = true;
            return this;
        }

        /** Its texture rect, top-left then bottom-right. */
        public Quad uv(float u0, float v0, float u1, float v1) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            return this;
        }

        /** Its colour, ARGB. */
        public Quad color(int argb) {
            this.argb = argb;
            return this;
        }

        /**
         * Fades the whole item, 1 by default: what {@code CG_QUAD_OPACITY} multiplies in, with its effect node's
         * opacity. Unlike the colour's alpha it reaches everything the material draws, a box's border as well as its
         * fill, and quads of different opacity still batch.
         *
         * <pre>{@code
         * canvas.quad(box).custom2(shape).at(x, y).size(w, h).opacity(0.5f).submit();   // border and fill at half
         * }</pre>
         *
         * A material that does not read {@code CG_QUAD_OPACITY} ignores it; every material {@link CgCanvasMaterials}
         * ships reads it but the coverage rasters.
         */
        public Quad opacity(float opacity) {
            this.fade = 1f - Math.max(0f, Math.min(1f, opacity));
            return this;
        }

        /** The layer of a {@code sampler2DArray} it samples: {@code CG_QUAD_ATLAS_LAYER}. */
        public Quad atlasLayer(int layer) {
            this.atlasLayer = layer;
            return this;
        }

        /**
         * Free per-quad parameters, read as {@code CG_QUAD_CUSTOM0} and {@code CG_QUAD_CUSTOM1}: what varies per quad
         * without breaking the batch, as a property would. Zero unless set.
         *
         * <pre>{@code
         * canvas.quad(text).at(x, y).size(w, h).custom0(strokeArgb).custom1(widthPx, align, over, 0f).submit();
         * }</pre>
         */
        public Quad custom0(float x, float y, float z, float w) {
            this.c0x = x; this.c0y = y; this.c0z = z; this.c0w = w;
            return this;
        }

        /** @see #custom0(float, float, float, float) */
        public Quad custom1(float x, float y, float z, float w) {
            this.c1x = x; this.c1y = y; this.c1z = z; this.c1w = w;
            return this;
        }

        /** A free scalar, read as {@code CG_QUAD_CUSTOM2}. */
        public Quad custom2(float value) {
            this.c2 = value;
            return this;
        }

        /** {@code custom0} from an ARGB colour, unpacked to rgba in 0..1. */
        public Quad custom0(int argb) {
            return custom0(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                    (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
        }

        /** {@code custom1} from an ARGB colour, unpacked to rgba in 0..1. */
        public Quad custom1(int argb) {
            return custom1(((argb >> 16) & 0xFF) / 255f, ((argb >> 8) & 0xFF) / 255f,
                    (argb & 0xFF) / 255f, ((argb >>> 24) & 0xFF) / 255f);
        }

        /** The {@code CgClipTable} entry it is clipped by; 0 is none. */
        public Quad clip(int entry) {
            this.clip = entry;
            return this;
        }

        /** The spatial and effect nodes it is drawn in; 0 and 0 are the pass's own space at full opacity. @see CgPalette */
        public Quad node(int spatial, int effect) {
            this.node = CgPalette.pack(spatial, effect);
            this.spatial = spatial;
            return this;
        }

        /** A transform baked into its corner and edges at {@link #submit()}; null for none. */
        public Quad pose(@Nullable Matrix4f pose) {
            this.pose = pose;
            return this;
        }

        /**
         * Anchors it at an absolute position: the canvas's origin taken away in doubles, so it stays exact far from
         * the world's origin. {@code at} and the pose place it from here.
         */
        public Quad world(double x, double y, double z) {
            this.anchored = true;
            this.anchorX = x;
            this.anchorY = y;
            this.anchorZ = z;
            return this;
        }

        /**
         * Where it lands in its canvas's space: its corner, then the edges across its width and down its height, nine
         * floats from {@code out[0]}. What {@link CgCanvasBackdrop} projects to find the pixels a pane covers.
         */
        void place(float[] out) {
            float ox = x, oy = y, oz = z;
            float rx = width, ry = 0f, rz = 0f;
            float ux = 0f, uy = height, uz = 0f;
            if (pose != null) {
                scratchOrigin.set(ox, oy, oz);
                scratchRight.set(rx, ry, rz);
                scratchUp.set(ux, uy, uz);
                pose.transformPosition(scratchOrigin);
                pose.transformDirection(scratchRight);
                pose.transformDirection(scratchUp);
                ox = scratchOrigin.x(); oy = scratchOrigin.y(); oz = scratchOrigin.z();
                rx = scratchRight.x(); ry = scratchRight.y(); rz = scratchRight.z();
                ux = scratchUp.x(); uy = scratchUp.y(); uz = scratchUp.z();
            }
            if (anchored) {
                ox += (float) (anchorX - canvas.originX());
                oy += (float) (anchorY - canvas.originY());
                oz += (float) (anchorZ - canvas.originZ());
            }
            out[0] = ox; out[1] = oy; out[2] = oz;
            out[3] = rx; out[4] = ry; out[5] = rz;
            out[6] = ux; out[7] = uy; out[8] = uz;
        }

        /** Its size, as {@link #size} set it. */
        float width() {
            return width;
        }

        float height() {
            return height;
        }

        /**
         * Queues it into its canvas, where it lands with the items around it.
         *
         * @throws IllegalStateException without a material or a {@link #size}
         */
        public CgCanvas submit() {
            if (material == null) throw new IllegalStateException("a quad with no material");
            if (!sizeSet) throw new IllegalStateException("a quad needs size(width, height)");

            float[] p = placed;
            place(p);
            float ox = p[0], oy = p[1], oz = p[2];
            float rx = p[3], ry = p[4], rz = p[5];
            float ux = p[6], uy = p[7], uz = p[8];

            run.item(material, state, textures, samplers, textureUnits);
            run.spatial(spatial);
            // A pixel past the corners: an edge antialiased off the grid reaches half of one.
            run.bounds(ox + Math.min(0f, rx) + Math.min(0f, ux) - 1f, oy + Math.min(0f, ry) + Math.min(0f, uy) - 1f,
                    ox + Math.max(0f, rx) + Math.max(0f, ux) + 1f, oy + Math.max(0f, ry) + Math.max(0f, uy) + 1f);
            writer.beginRecord()
                    .vec3At(offOrigin, ox, oy, oz)
                    .floatAt(offFade, fade)
                    .vec3At(offRight, rx, ry, rz)
                    .vec3At(offUp, ux, uy, uz)
                    .vec2At(offUv0, u0, v0)
                    .vec2At(offUv1, u1, v1)
                    .colorAt(offColor, argb)
                    .floatAt(offAtlasLayer, atlasLayer)
                    .floatAt(offCustom2, c2)
                    .floatAt(offClip, clip)
                    .floatAt(offNode, node)
                    .vec4At(offCustom0, c0x, c0y, c0z, c0w)
                    .vec4At(offCustom1, c1x, c1y, c1z, c1w)
                    .endRecord();
            return canvas;
        }
    }
}
