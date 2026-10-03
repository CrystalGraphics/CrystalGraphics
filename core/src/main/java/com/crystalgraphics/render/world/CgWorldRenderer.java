package com.crystalgraphics.render.world;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.material.CgRenderQueue;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.api.state.CgColorMask;
import com.crystalgraphics.api.state.CgDepthState;
import com.crystalgraphics.api.state.CgRenderState;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.mc.compat.CgIrisCompat;
import com.crystalgraphics.render.CgViewFrustum;
import com.crystalgraphics.render.draw.CgBufferHandle;
import com.crystalgraphics.render.draw.CgChunkBuilder;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.draw.CgInstanceKind;
import com.crystalgraphics.render.draw.CgOrder;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.render.draw.CgPipeline;
import com.crystalgraphics.render.graph.CgLoad;
import com.crystalgraphics.render.graph.CgRasterPass;
import com.crystalgraphics.render.graph.CgRecording;
import com.crystalgraphics.render.stage.CgHostView;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.stage.CgStageFrame;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Draws meshes into a host's world at its two world stages, under the host's own camera: submitted at absolute
 * positions in doubles, culled against the view, sorted, and instanced where neighbours share a mesh and a material.
 *
 * <pre>{@code
 * CgWorldRenderer world = CgWorldRenderer.get();
 * world.onFrame(view -> {                                // once a frame, before the world records
 *     world.draw(mesh, material)                         // scratch: build and submit in one expression
 *          .at(x, y, z)                                  // absolute, in doubles
 *          .transform(rotationScale)                     // optional, about that position
 *          .custom(0, r, g, b, a)                        // CG_OBJECT_CUSTOM0
 *          .submit();
 * });
 *
 * // A material's authored queue decides opaque or transparent; a draw may override it.
 * world.draw(pane, glass).at(x, y, z).queue(CgRenderQueue.TRANSPARENT).submit();
 *
 * // How much of the mesh draws comes from a count a kernel wrote this frame
 * world.draw(CgMesh.quads(capacity), sparks).indirect(live, 0, CgIndirect.INDICES, 6).at(x, y, z).bounds(box).submit();
 * }</pre>
 *
 * <ul>
 *   <li>Render thread. A draw lives for the frame it was submitted in; every world stage that frame fires draws it,
 *       under that stage's view, so a host drawing the world twice (1.7.10's anaglyph) needs nothing more.</li>
 *   <li>Shaders see camera-relative world space: {@code CG_CAMERA_WORLD_POS} is the origin, and
 *       {@code CG_ABSOLUTE_WORLD_POS(p)} adds the camera back for an effect that must not move with it.</li>
 *   <li>A mesh with no bounds (a format whose positions are not floats) is never culled.</li>
 * </ul>
 */
public final class CgWorldRenderer {

    /** Where the world renderer records in each world stage: after renderers at the default order, which may submit. */
    public static final int ORDER = 1000;

    private static final Logger LOGGER = LogManager.getLogger("CgWorldRenderer");
    private static final CgWorldRenderer INSTANCE = new CgWorldRenderer();

    private static final CgRenderState OPAQUE_STATE = CgRenderState.builder()
            .depth(CgDepthState.TEST_WRITE).blend(CgBlendState.DISABLED).build();
    private static final CgRenderState TRANSPARENT_STATE = CgRenderState.builder()
            .depth(CgDepthState.TEST_ONLY).blend(CgBlendState.ALPHA).build();

    /** Called once a frame, before the first world stage records, with the host's camera. */
    @FunctionalInterface
    public interface FrameListener {
        void frame(CgHostView view);
    }

    private final List<FrameListener> listeners = new CopyOnWriteArrayList<>();
    private final Draw scratch = new Draw();

    // The frame's draws, flat: what a pooled command object per draw used to hold.
    private long frame = -1;
    private long notified = -1;
    private int count;
    private CgMesh[] meshes = new CgMesh[64];
    private CgMeshLods[] lods = new CgMeshLods[64];
    private final float[] meshBounds = new float[6];
    /** Per draw: submesh, first, count of its mesh; its stated bounds (6) and whether set; its padding. */
    private int[] ranges = new int[64 * 3];
    private float[] drawBounds = new float[64 * 6];
    private boolean[] boundsStated = new boolean[64];
    private float[] pads = new float[64];
    /** Per draw: the buffer an indirect draw's count is in, else null; the count's byte offset, mode and factor. */
    private CgBufferHandle[] counts = new CgBufferHandle[64];
    private long[] countOffsets = new long[64];
    private CgIndirect[] countModes = new CgIndirect[64];
    private int[] countFactors = new int[64];
    private CgMaterial[] materials = new CgMaterial[64];
    private double[] positions = new double[64 * 3];
    private float[] transforms = new float[64 * 16];
    private float[] customs = new float[64 * 16];
    private int[] queues = new int[64];
    private int[] orders = new int[64];
    private CgSortLayer[] layers = new CgSortLayer[64];
    /** Per draw: whether it is in a group, and the group's absolute position (3). */
    private boolean[] grouped = new boolean[64];
    private double[] groupPositions = new double[64 * 3];

    // Per recording, reused: nothing here allocates per frame once warm.
    private final Matrix4f model = new Matrix4f();
    private final Matrix4f normal = new Matrix4f();
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f toWorld = new Matrix4f();
    private final Vector3f eye = new Vector3f();
    private final Vector3f forward = new Vector3f();
    private final Vector3f min = new Vector3f();
    private final Vector3f max = new Vector3f();
    private final CgViewFrustum frustum = new CgViewFrustum();
    private final IdentityHashMap<CgMaterial, Integer> bindings = new IdentityHashMap<>();
    private final IdentityHashMap<CgRenderState, CgRenderState> depthOnly = new IdentityHashMap<>();

    private boolean installed;
    private boolean irisWarned;

    private CgWorldRenderer() {
    }

    public static CgWorldRenderer get() {
        return INSTANCE;
    }

    /** Registers on the world stages, once. The engine calls it when it starts. */
    public void install() {
        if (installed) return;
        installed = true;
        CgRenderStage.WORLD_OPAQUE.register(ORDER, this::recordOpaque);
        CgRenderStage.WORLD_TRANSPARENT.register(ORDER, this::recordTransparent);
    }

    /** Drops every draw. At context teardown. */
    public void release() {
        clear();
        frame = -1;
        depthOnly.clear();
    }

    /** Calls {@code listener} once a frame, before the first world stage records. Closing the registration stops it. */
    public CgRenderStage.Registration onFrame(FrameListener listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Starts a draw of {@code mesh} under {@code material}: the shared scratch, so build and submit in one expression. */
    public Draw draw(CgMesh mesh, CgMaterial material) {
        return scratch.start(mesh, material);
    }

    /** As {@link #draw(CgMesh, CgMaterial)}, of the level of {@code lods} for how tall the draw stands on screen. */
    public Draw draw(CgMeshLods lods, CgMaterial material) {
        Draw draw = scratch.start(lods.finest(), material);
        draw.lods = lods;
        return draw;
    }

    /** One draw being built. Never hold it: the next {@link #draw} reuses it. */
    public final class Draw {

        private CgMesh mesh;
        private CgMeshLods lods;
        private CgMaterial material;
        private double x, y, z;
        private final Matrix4f transform = new Matrix4f();
        private final float[] custom = new float[16];
        private int queue;
        private int order;
        private CgSortLayer layer;
        private boolean inGroup;
        private double groupX, groupY, groupZ;
        private int submesh, first, count;
        private final float[] bounds = new float[6];
        private boolean boundsSet;
        private float pad;
        private CgBufferHandle indirect;
        private long indirectOffset;
        private CgIndirect indirectMode;
        private int indirectFactor;

        private Draw start(CgMesh mesh, CgMaterial material) {
            this.mesh = mesh;
            this.lods = null;
            this.material = material;
            x = y = z = 0;
            transform.identity();
            Arrays.fill(custom, 0f);
            queue = material.getRenderQueue();
            order = 0;
            layer = CgSortLayer.DEFAULT;
            inGroup = false;
            submesh = -1;
            first = 0;
            count = -1;
            boundsSet = false;
            pad = 0f;
            indirect = null;
            return this;
        }

        /** Draws only submesh {@code i} of the mesh, whole. */
        public Draw submesh(int i) {
            submesh = i;
            return this;
        }

        /**
         * Draws {@code count} of the submesh's indices from {@code first} (submesh 0 unless {@link #submesh} named
         * one); for a mesh without indices, its vertices. {@code CG_VERTEX_ID} is not moved by it.
         *
         * <pre>{@code
         * world.draw(CgMesh.quads(capacity), sparks).indices(0, live * 6).at(x, y, z).bounds(box).submit();
         * }</pre>
         */
        public Draw indices(int first, int count) {
            if (submesh < 0) submesh = 0;
            this.first = first;
            this.count = count;
            return this;
        }

        /**
         * The box this draw covers, in its own space (transformed with it, as a mesh's bounds are): what it is culled
         * by, in place of its mesh's. A mesh the shader places, {@code CgMesh.quads(n)}, has none of its own.
         */
        public Draw bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            bounds[0] = minX;
            bounds[1] = minY;
            bounds[2] = minZ;
            bounds[3] = maxX;
            bounds[4] = maxY;
            bounds[5] = maxZ;
            boundsSet = true;
            return this;
        }

        /**
         * Draws as much of the mesh as a count written on the GPU says: the {@code uint} at byte {@code offset} in
         * {@code count}, times {@code factor}, read as {@code mode}, within the range {@link #indices} or
         * {@link #submesh} chose. A graph buffer's count is read after the pass that writes it in the stage's frame.
         *
         * <pre>{@code
         * world.draw(CgMesh.quads(capacity), sparks).indirect(live, 0, CgIndirect.INDICES, 6).at(x, y, z).bounds(box).submit();
         * world.draw(billow, smoke).indirect(live, 0, CgIndirect.INSTANCES, 1).at(x, y, z).bounds(box).submit();
         * }</pre>
         *
         * <p>Under {@code INSTANCES} every instance reads this draw's record, and the shader places each from
         * {@code CG_DRAW_INSTANCE}. The count is unknown on the CPU, so cull by {@link #bounds} covering every element.</p>
         */
        public Draw indirect(CgBufferHandle count, long offset, CgIndirect mode, int factor) {
            this.indirect = count;
            this.indirectOffset = offset;
            this.indirectMode = mode;
            this.indirectFactor = factor;
            return this;
        }

        /** Grows the bounds it is culled by on every side, for a vertex shader that displaces. */
        public Draw pad(float radius) {
            pad = radius;
            return this;
        }

        /** Its absolute world position. */
        public Draw at(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
            return this;
        }

        /** A rotation and scale (or any affine transform) about its position. */
        public Draw transform(Matrix4fc transform) {
            this.transform.set(transform);
            return this;
        }

        /** {@code CG_OBJECT_CUSTOM<slot>}, slot 0 to 3. */
        public Draw custom(int slot, float x, float y, float z, float w) {
            int at = slot * 4;
            custom[at] = x;
            custom[at + 1] = y;
            custom[at + 2] = z;
            custom[at + 3] = w;
            return this;
        }

        /** Overrides the material's authored queue: {@link CgRenderQueue} values. */
        public Draw queue(int queue) {
            this.queue = queue;
            return this;
        }

        /** The {@link CgSortLayer} it sorts in; {@link CgSortLayer#DEFAULT} unless named. */
        public Draw layer(CgSortLayer layer) {
            this.layer = Objects.requireNonNull(layer, "layer");
            return this;
        }

        /** 0 to 15; higher draws later within its layer, or within its {@link #group}. */
        public Draw order(int order) {
            if (order < 0 || order > 15) throw new IllegalArgumentException("order " + order + " is outside 0..15");
            this.order = order;
            return this;
        }

        /**
         * Sorts it with the group at absolute {@code (x, y, z)} as one, as Niagara sorts a system's emitters: a
         * transparent group draws whole, back to front by its position among the other groups and draws of its
         * {@link #layer}, and its draws by their own {@link #order}, then distance, within it: back to front only among
         * the draws of one order. Every draw of a group gives
         * the same position and layer. Opaque draws ignore it.
         *
         * <pre>{@code
         * world.draw(mesh, haze).at(x, y, z).layer(CgSortLayer.EFFECTS).group(ox, oy, oz).order(4).submit();
         * }</pre>
         */
        public Draw group(double x, double y, double z) {
            inGroup = true;
            groupX = x;
            groupY = y;
            groupZ = z;
            return this;
        }

        public void submit() {
            add(this);
        }
    }

    private void add(Draw d) {
        long now = CgFrameRing.frame();
        if (now != frame) {
            clear();
            frame = now;
        }
        if (count == meshes.length) grow();
        meshes[count] = d.mesh;
        lods[count] = d.lods;
        materials[count] = d.material;
        positions[count * 3] = d.x;
        positions[count * 3 + 1] = d.y;
        positions[count * 3 + 2] = d.z;
        d.transform.get(transforms, count * 16);
        System.arraycopy(d.custom, 0, customs, count * 16, 16);
        queues[count] = d.queue;
        orders[count] = d.order;
        layers[count] = d.layer;
        grouped[count] = d.inGroup;
        if (d.inGroup) {
            groupPositions[count * 3] = d.groupX;
            groupPositions[count * 3 + 1] = d.groupY;
            groupPositions[count * 3 + 2] = d.groupZ;
        }
        ranges[count * 3] = d.submesh;
        ranges[count * 3 + 1] = d.first;
        ranges[count * 3 + 2] = d.count;
        boundsStated[count] = d.boundsSet;
        if (d.boundsSet) System.arraycopy(d.bounds, 0, drawBounds, count * 6, 6);
        pads[count] = d.pad;
        counts[count] = d.indirect;
        countOffsets[count] = d.indirectOffset;
        countModes[count] = d.indirectMode;
        countFactors[count] = d.indirectFactor;
        count++;
    }

    private void clear() {
        Arrays.fill(meshes, 0, count, null);
        Arrays.fill(lods, 0, count, null);
        Arrays.fill(materials, 0, count, null);
        Arrays.fill(counts, 0, count, null);
        Arrays.fill(layers, 0, count, null);
        count = 0;
    }

    private void grow() {
        int n = meshes.length * 2;
        meshes = Arrays.copyOf(meshes, n);
        lods = Arrays.copyOf(lods, n);
        materials = Arrays.copyOf(materials, n);
        positions = Arrays.copyOf(positions, n * 3);
        transforms = Arrays.copyOf(transforms, n * 16);
        customs = Arrays.copyOf(customs, n * 16);
        queues = Arrays.copyOf(queues, n);
        orders = Arrays.copyOf(orders, n);
        layers = Arrays.copyOf(layers, n);
        grouped = Arrays.copyOf(grouped, n);
        groupPositions = Arrays.copyOf(groupPositions, n * 3);
        ranges = Arrays.copyOf(ranges, n * 3);
        drawBounds = Arrays.copyOf(drawBounds, n * 6);
        boundsStated = Arrays.copyOf(boundsStated, n);
        pads = Arrays.copyOf(pads, n);
        counts = Arrays.copyOf(counts, n);
        countOffsets = Arrays.copyOf(countOffsets, n);
        countModes = Arrays.copyOf(countModes, n);
        countFactors = Arrays.copyOf(countFactors, n);
    }
    // ── Recording ────────────────────────────────────────────────────────────────────────────────

    private static final int OPAQUE = 0, TRANSPARENT = 1;
    private static final byte SKIP = -1, FORWARD = 0, FORWARD_AND_PREPASS = 1;

    private long[] keys = new long[64];
    private byte[] phase = new byte[64];

    private void recordOpaque(CgStageFrame stage) {
        record(stage, OPAQUE);
    }

    private void recordTransparent(CgStageFrame stage) {
        record(stage, TRANSPARENT);
    }

    private void record(CgStageFrame stage, int which) {
        CgHostView view = stage.host().view();
        long now = CgFrameRing.frame();
        if (now != frame) {
            clear();
            frame = now;
        }
        if (now != notified) {
            notified = now;
            for (FrameListener listener : listeners) listener.frame(view);
        }
        if (count == 0) return;
        if (!irisWarned && CgIrisCompat.isShaderPackActive()) {
            irisWarned = true;
            LOGGER.warn("An Iris/Oculus shader pack is active: the world renderer draws into the main framebuffer, "
                    + "outside its deferred G-buffer, so its geometry is unlit under a deferred pack. cg_DepthBuffer "
                    + "remains valid.");
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, which == OPAQUE ? "world.recordOpaque" : "world.recordTransparent")) {
            prepare(view);
            boolean prepass = false;
            int drawn = 0;
            for (int i = 0; i < count; i++) {
                byte p = classify(i, which, view);
                phase[i] = p;
                if (p == SKIP) continue;
                drawn++;
                prepass |= p == FORWARD_AND_PREPASS;
            }
            CgTrace.counter(CgChannels.WORLD, which == OPAQUE ? "world.opaqueDraws" : "world.transparentDraws", drawn);
            if (drawn == 0) return;

            CgRecording recording = stage.recording();
            CgPassConstants constants = stage.constants();
            bindings.clear();
            if (prepass) recordPass(stage, recording, constants, OPAQUE_STATE, true, view);
            recordPass(stage, recording, constants, which == OPAQUE ? OPAQUE_STATE : TRANSPARENT_STATE, false, view);
        }
    }

    /** The frustum, the eye and its forward, in the view's camera-relative space. */
    private void prepare(CgHostView view) {
        viewProjection.set(view.projection()).mul(view.view());
        frustum.set(viewProjection);
        toWorld.set(view.view()).invert();
        toWorld.transformPosition(eye.set(0f, 0f, 0f));
        toWorld.transformDirection(forward.set(0f, 0f, -1f)).normalize();
        if (keys.length < count) {
            keys = new long[meshes.length];
            phase = new byte[meshes.length];
        }
    }

    /** Whether draw {@code i} takes part in this stage, and its sort key. */
    private byte classify(int i, int which, CgHostView view) {
        int queue = queues[i];
        boolean transparent = queue >= CgRenderQueue.TRANSPARENT_THRESHOLD;
        if (queue >= CgRenderQueue.OVERLAY_THRESHOLD || transparent != (which == TRANSPARENT)) return SKIP;
        modelOf(i, view);
        float cx, cy, cz;
        float[] bounds;
        if (boundsStated[i]) {
            System.arraycopy(drawBounds, i * 6, meshBounds, 0, 6);
            bounds = meshBounds;
        } else {
            bounds = (lods[i] != null ? lods[i].finest() : meshes[i]).bounds(meshBounds);
        }
        if (bounds != null) {
            float p = pads[i];
            model.transformAab(bounds[0] - p, bounds[1] - p, bounds[2] - p, bounds[3] + p, bounds[4] + p, bounds[5] + p,
                    min, max);
            if (!frustum.testAabb(min.x, min.y, min.z, max.x, max.y, max.z)) return SKIP;
            cx = (min.x + max.x) * 0.5f;
            cy = (min.y + max.y) * 0.5f;
            cz = (min.z + max.z) * 0.5f;
            if (lods[i] != null) {
                CgMesh level = lods[i].pick(screenHeight(cx, cy, cz, view));
                if (level == null) return SKIP;
                meshes[i] = level;
            }
        } else {
            if (lods[i] != null) meshes[i] = lods[i].finest();
            cx = model.m30();
            cy = model.m31();
            cz = model.m32();
        }
        float distance = Math.max(0f, (cx - eye.x) * forward.x + (cy - eye.y) * forward.y + (cz - eye.z) * forward.z);
        CgMaterial material = materials[i];
        keys[i] = transparent
                ? transparentKey(i, queue, distance, view)
                : CgSortKey.opaque(queue, layers[i].rank(), orders[i], material.getMaterialId(), System.identityHashCode(meshes[i]), distance);
        boolean prepass = !transparent && (material.hasDepthPass() || queue >= CgRenderQueue.ALPHA_TEST_THRESHOLD);
        return prepass ? FORWARD_AND_PREPASS : FORWARD;
    }

    /** Draw {@code i}'s transparent key: its layer, its group's distance, then its own order and distance. */
    private long transparentKey(int i, int queue, float distance, CgHostView view) {
        int layer = layers[i].rank();
        if (!grouped[i]) return CgSortKey.transparent(queue, layer, distance, orders[i], distance);
        float gx = (float) (groupPositions[i * 3] - view.x()) - eye.x;
        float gy = (float) (groupPositions[i * 3 + 1] - view.y()) - eye.y;
        float gz = (float) (groupPositions[i * 3 + 2] - view.z()) - eye.z;
        float groupDistance = Math.max(0f, gx * forward.x + gy * forward.y + gz * forward.z);
        return CgSortKey.transparent(queue, layer, groupDistance, orders[i], distance);
    }

    /**
     * The fraction of the screen's height the sphere around {@code min}..{@code max}, centred on {@code (cx, cy, cz)},
     * covers: its diameter projected at its centre's depth. Above 1 with the eye inside it.
     */
    private float screenHeight(float cx, float cy, float cz, CgHostView view) {
        float r = 0.5f * (float) Math.sqrt((max.x - min.x) * (max.x - min.x) + (max.y - min.y) * (max.y - min.y)
                + (max.z - min.z) * (max.z - min.z));
        float w = viewProjection.m03() * cx + viewProjection.m13() * cy + viewProjection.m23() * cz + viewProjection.m33();
        if (w <= r) return Float.MAX_VALUE;
        return r * Math.abs(view.projection().m11()) / w;
    }

    /** Draw {@code i}'s model matrix, camera-relative: its position minus the view's, in doubles, then its transform. */
    private void modelOf(int i, CgHostView view) {
        model.set(transforms, i * 16);
        model.m30(model.m30() + (float) (positions[i * 3] - view.x()))
                .m31(model.m31() + (float) (positions[i * 3 + 1] - view.y()))
                .m32(model.m32() + (float) (positions[i * 3 + 2] - view.z()));
    }

    private void recordPass(CgStageFrame stage, CgRecording recording, CgPassConstants constants, CgRenderState state,
                            boolean depthOnlyPass, CgHostView view) {
        CgRasterPass pass = recording.raster(stage.target(), CgLoad.load(), constants, state, CgOrder.SORTED)
                .sceneDepth(CgBindingPoints.DEPTH_TEXTURE_UNIT)
                .sceneColor(CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT);
        CgChunkBuilder chunks = recording.chunks().begin();
        for (int i = 0; i < count; i++) {
            if (phase[i] == SKIP || (depthOnlyPass && phase[i] != FORWARD_AND_PREPASS)) continue;
            modelOf(i, view);
            model.normal(normal);
            for (CgMaterial link = materials[i]; link != null; link = depthOnlyPass ? null : link.getNextPass()) {
                CgPipeline pipeline = depthOnlyPass ? depthPipeline(link) : link.pipeline(CgInstanceKind.OBJECT);
                if (pipeline == null) continue;
                chunks.draw(pipeline, bindingOf(link, recording), meshes[i]).sortKey(keys[i]);
                if (ranges[i * 3] >= 0) chunks.range(ranges[i * 3], ranges[i * 3 + 1], ranges[i * 3 + 2]);
                if (counts[i] != null) chunks.indirect(counts[i], countOffsets[i], countModes[i], countFactors[i]);
                int at = chunks.instance();
                float[] data = chunks.data();
                model.get(data, at);
                normal.get(data, at + 16);
                System.arraycopy(customs, i * 16, data, at + 32, 16);
            }
        }
        pass.add(chunks.end());
        pass.end();
    }

    /** A material's depth pass, or its forward pass writing depth alone. */
    private CgPipeline depthPipeline(CgMaterial material) {
        if (material.hasDepthPass()) return material.pipeline(CgRenderPassVariant.DEPTH, CgInstanceKind.OBJECT);
        CgPipeline forward = material.pipeline(CgInstanceKind.OBJECT);
        if (forward == null) return null;
        CgRenderState own = material.getPassRenderState(CgRenderPassVariant.FORWARD);
        CgRenderState colourless = depthOnly.get(own);
        if (colourless == null) depthOnly.put(own, colourless = own.withColorMask(CgColorMask.NONE));
        return forward.withState(colourless);
    }

    /** A material's snapshot in this recording, taken once per stage. */
    private int bindingOf(CgMaterial material, CgRecording recording) {
        Integer id = bindings.get(material);
        if (id == null) bindings.put(material, id = material.captureBindings(recording.bindings()));
        return id;
    }
}
