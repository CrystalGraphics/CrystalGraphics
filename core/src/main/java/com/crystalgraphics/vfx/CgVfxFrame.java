package com.crystalgraphics.vfx;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.material.CgRenderPassVariant;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshLods;
import com.crystalgraphics.api.mesh.CgMeshTopology;
import com.crystalgraphics.api.state.CgBlendState;
import com.crystalgraphics.compute.ops.CgGpuCount;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.render.draw.CgIndirect;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.fluid.CgVfxFluidAtlas;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.grid.CgVfxGridAtlas;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.path.CgVfxPath;
import com.crystalgraphics.vfx.render.CgVfxQuads;
import com.crystalgraphics.vfx.render.CgVfxRibbons;
import com.crystalgraphics.vfx.trail.CgVfxTrail;
import com.crystalgraphics.vfx.trail.CgVfxTrailTexture;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What a {@link CgVfxEffect} draws through in one frame, handed to it by {@link CgVfxSystem#submit}: each call draws
 * one slot, every layer its look has on it.
 *
 * <pre>{@code
 * @Override protected void submit(CgVfxFrame frame) {
 *     int row = frame.path(path, seed, age);
 *     frame.tube(this, BODY, path, row);                                        // every layer on BODY
 *     frame.mesh(this, HEAD, x, y, z, transform, halfWidth, halfLength, 1f, 0f);   // every layer on HEAD
 *     frame.particles(this, sparks);                                           // its emitter's slot
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A slot no layer of the look draws on costs nothing: a look without its realistic body draws no tube.</li>
 *   <li>A layer above this frame's quality tier ({@link CgVfxLayer#from}) draws nothing.</li>
 *   <li>Every draw's {@code CG_OBJECT_CUSTOM3} is its header ({@code fx_draw.glsl}): its row of bound values, the
 *       effect's age, its seed and its presence. The other customs are the slot kind's ({@link CgVfxSlot.Kind}).</li>
 *   <li>Calling a method of another kind than the slot's throws.</li>
 * </ul>
 */
public final class CgVfxFrame {

    private static final int DRAWS_MESH = CgTrace.name("vfx.draws.mesh"), DRAWS_RIBBONS = CgTrace.name("vfx.draws.ribbons"),
            DRAWS_BILLBOARD = CgTrace.name("vfx.draws.billboard"), DRAWS_PATH_RIBBONS = CgTrace.name("vfx.draws.path-ribbons"),
            DRAWS_TRAIL = CgTrace.name("vfx.draws.trail"), DRAWS_VOLUME = CgTrace.name("vfx.draws.volume"),
            DRAWS_FLUID = CgTrace.name("vfx.draws.fluid"),
            DRAWS_PARTICLE_GPU = CgTrace.name("vfx.draws.particle-gpu"), DRAWS_PARTICLE_SETS = CgTrace.name("vfx.draws.particle-sets"),
            GPU_ZONE = CgTrace.name("vfx.particles.gpu");
    /**
     * GPU particle slots drawn as one draw a pool and material ({@code Draw.indirectEach}), in one group for the system:
     * each material's slots blend as one run over the others' rather than effect by effect. False draws a slot a draw,
     * per effect.
     */
    private static final CgVar.Bool MERGED = CgVar.bool("crystalgraphics.vfx.mergeParticles", true, CgVar.DEV,
            "GPU particles one draw a pool and layer (indirectEach); false draws a slot a draw, sorted per effect: the"
                    + " comparison for overlaps.");
    private final CgVfxSystem system;
    private final CgVfxMaterials materials;
    private final Matrix4f scaled = new Matrix4f(), sized = new Matrix4f();
    /** A gas's box, from its middle: lo xyz then hi xyz. */
    private final float[] volumeBox = new float[6];
    private final Vector3f scale = new Vector3f();
    private final float[] trailBounds = new float[8];
    /** Quads between two points unless a layer gives its own: a trail slot's sparse points, a particle's dense ones. */
    private static final int TRAIL_QUADS = 4, PARTICLE_STRIP_QUADS = 2;
    /** How far the unit cube's corners are from its middle: what a ribbons or billboard draw reaches. */
    private static final float CUBE_REACH = 1.7320508f;
    private CgWorldRenderer world;
    private float alpha, particleAlpha;
    /** Where the merged particle draws sort as one group: the middle of the system's effects. */
    private double groupX, groupY, groupZ;
    /** This frame's merged particle slots, a set per pool and material, the first {@link #setsUsed} in use. */
    private final List<SlotSet> sets = new ArrayList<>();
    private int setsUsed;

    CgVfxFrame(CgVfxSystem system, CgVfxMaterials materials) {
        this.system = system;
        this.materials = materials;
    }

    void begin(CgWorldRenderer world, float alpha, float particleAlpha, double groupX, double groupY, double groupZ) {
        this.world = world;
        this.alpha = alpha;
        this.particleAlpha = particleAlpha;
        this.groupX = groupX;
        this.groupY = groupY;
        this.groupZ = groupZ;
        setsUsed = 0;
    }

    /** Draws the merged particle slots: one draw a pool and material, each slot a record of it. After every submit. */
    void end() {
        for (int i = 0; i < setsUsed; i++) {
            SlotSet set = sets.get(i);
            CgVfxRange range = CgVfxRange.of(set.pool);
            CgMesh mesh = set.strip == CgVfxLayer.Strip.NONE ? slotMesh(set.renderer, set.capacity)
                    : CgMesh.vertices(sizeClass(set.capacity, 1) * set.factor, CgMeshTopology.TRIANGLES);
            CgWorldRenderer.Draw draw = world.draw(mesh, set.material)
                    .buffer(CgBindingPoints.PARTICLES, range.drawn())
                    .indirectEach(range.visible(), set.mode, set.factor)
                    .gpuCulled()
                    .at(groupX, groupY, groupZ);
            CgVfxSystem.place(draw, set.placed, groupX, groupY, groupZ);
            draw.group(groupX, groupY, groupZ).batchKey(materials.key(set.material));
            for (int r = 0; r < set.records; r++) {
                draw.each(set.at[r * 3], set.at[r * 3 + 1], set.at[r * 3 + 2], set.count[r]);
                for (int c = 0; c < 4; c++) {
                    int f = r * 16 + c * 4;
                    draw.custom(c, set.custom[f], set.custom[f + 1], set.custom[f + 2], set.custom[f + 3]);
                }
            }
            draw.submit();
            CgVfxTrace.count(DRAWS_PARTICLE_SETS, 1);
        }
    }

    /**
     * {@code pool}'s set for {@code material} this frame, drawing {@code factor} elements of {@code mode} a visible
     * particle, begun empty the first time it is asked for.
     */
    private SlotSet set(CgVfxParticlePool pool, CgMaterial material, CgVfxLook.Placed placed, CgVfxEmitter.Renderer renderer,
                        CgVfxLayer.Strip strip, CgIndirect mode, int factor) {
        for (int i = 0; i < setsUsed; i++) {
            SlotSet set = sets.get(i);
            if (set.pool == pool && set.material == material && set.renderer == renderer && set.strip == strip
                    && set.factor == factor) {
                return set;
            }
        }
        if (setsUsed == sets.size()) sets.add(new SlotSet());
        SlotSet set = sets.get(setsUsed++);
        set.pool = pool;
        set.material = material;
        set.placed = placed;
        set.renderer = renderer;
        set.strip = strip;
        set.mode = mode;
        set.factor = factor;
        set.capacity = 0;
        set.records = 0;
        return set;
    }

    /** One pool's slots drawn through one material: each a record's position, customs and count offset. */
    private static final class SlotSet {
        CgVfxParticlePool pool;
        CgMaterial material;
        /** The first layer drawn through it: what places the set's draw. */
        CgVfxLook.Placed placed;
        CgVfxEmitter.Renderer renderer;
        /** How it draws its particles, and each one's elements: quads' indices, an arc's or a strip's vertices. */
        CgVfxLayer.Strip strip;
        CgIndirect mode;
        int factor;
        /** The largest slot's: the mesh is sized for it. */
        int capacity;
        int records;
        double[] at = new double[16 * 3];
        float[] custom = new float[16 * 16];
        long[] count = new long[16];

        /** A record, its customs zeroed; answers its first custom float. */
        int add(double x, double y, double z, long countOffset) {
            if (records == count.length) {
                int n = records * 2;
                at = Arrays.copyOf(at, n * 3);
                custom = Arrays.copyOf(custom, n * 16);
                count = Arrays.copyOf(count, n);
            }
            at[records * 3] = x;
            at[records * 3 + 1] = y;
            at[records * 3 + 2] = z;
            count[records] = countOffset;
            Arrays.fill(custom, records * 16, records * 16 + 16, 0f);
            return records++ * 16;
        }
    }

    /** How far this frame is between the last tick and the next, 0..1: draw positions moved on by this much. */
    public float alpha() {
        return alpha;
    }

    /**
     * How far this frame is between a particle's last two steps, 0..1: what Range draws a particle at.
     * Particles step every {@link CgVfxSystem#particleStep()} ticks, so this is not {@link #alpha()}.
     */
    public float particleAlpha() {
        return particleAlpha;
    }

    public CgWorldRenderer world() {
        return world;
    }

    /** Whether {@code effect}'s look draws anything on {@code slot}: skip working out a slot nothing draws. */
    public boolean draws(CgVfxEffect<?> effect, CgVfxSlot slot) {
        return effect.look().draws(slot);
    }

    /**
     * Whether {@code effect}'s tier this frame (the system's, lowered by its distance) is below {@code layer}'s, or
     * {@link CgVfxSystem#skipped()} names it.
     */
    private boolean skips(CgVfxEffect<?> effect, CgVfxLayer layer) {
        if (!system.quality(effect.lowered).atLeast(layer.from())) return true;
        for (String token : CgVfxSystem.skippedParts()) {
            if (layer.shader().contains(token)) return true;
        }
        return false;
    }

    private static void kind(CgVfxSlot slot, CgVfxSlot.Kind kind) {
        if (slot.kind() != kind) throw new IllegalArgumentException(slot + " is not a " + kind + " slot");
    }

    /** Puts {@code path} in this frame's path texture and answers its row. */
    public int path(CgVfxPath path, float seed, float age) {
        return system.paths().add(path, seed, age);
    }

    /** Grows {@code effect}'s bounds by {@code path}'s rings, each {@code size} times its own radius across. */
    private static void boundPath(CgVfxEffect<?> effect, CgVfxPath path, float size) {
        for (int i = 0; i < path.count(); i++) effect.bound(path.x(i), path.y(i), path.z(i), path.radius(i) * size);
    }

    /** Draws every layer on {@code slot}, a tube slot, as tubes along {@code path} at {@code row}, round the origin. */
    public void tube(CgVfxEffect<?> effect, CgVfxSlot slot, CgVfxPath path, int row) {
        kind(slot, CgVfxSlot.Kind.TUBE);
        CgVfxLook look = effect.look();
        CgVfxLook.Placed[] on = look.on(slot);
        for (CgVfxLook.Placed placed : on) {
            CgVfxLayer layer = placed.layer();
            if (skips(effect, layer)) continue;
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            boundPath(effect, path, CgVfxMaterials.size(effect, r));
            CgMesh mesh = layer.isVolume() ? system.volumeMesh() : layer.isClosed() ? system.closedTubeMesh() : system.tubeMesh();
            system.tube().submit(world, mesh, materials.material(r, CgVfxSlot.Kind.TUBE), placed, path, row, effect,
                    CgVfxMaterials.size(effect, r), materials.row(effect, r), effect.age, effect.seed, effect.presence());
        }
    }

    /**
     * Draws every layer on {@code slot}, a mesh slot, on a unit sphere (or the layer's own mesh, {@code CgVfxLayer.mesh})
     * placed by {@code transform} (rotation and scale, radius 1 before it) at {@code (x, y, z)} from the origin, scaled
     * again by each layer's size. What its shader reads:
     * <ul>
     *   <li>{@code CG_OBJECT_CUSTOM0}: the layer's size, 0, the effect's age and its seed.</li>
     *   <li>{@code CG_OBJECT_CUSTOM1}: {@code (ex, ey, ez, ew)}, which the effect documents for the slot.</li>
     * </ul>
     * The model matrix's columns are the sphere's axes, its +z the effect's forward where it has one.
     */
    public void mesh(CgVfxEffect<?> effect, CgVfxSlot slot, float x, float y, float z, Matrix4fc transform,
                     float ex, float ey, float ez, float ew) {
        mesh(effect, slot, x, y, z, transform, 1f, ex, ey, ez, ew);
    }

    /**
     * {@link #mesh(CgVfxEffect, CgVfxSlot, float, float, float, Matrix4fc, float, float, float, float)} at {@code presence}
     * of the effect's own: what {@code FX_PRESENCE} reads is their product, so a prefab node fades with its subtree.
     */
    public void mesh(CgVfxEffect<?> effect, CgVfxSlot slot, float x, float y, float z, Matrix4fc transform,
                     float presence, float ex, float ey, float ez, float ew) {
        mesh(effect, slot, null, x, y, z, transform, presence, ex, ey, ez, ew);
    }

    /**
     * {@link #mesh(CgVfxEffect, CgVfxSlot, float, float, float, Matrix4fc, float, float, float, float, float)} with
     * {@code mesh} in place of each layer's own: one material's layer drawing many shapes, as a prefab's nodes do. Null
     * draws each layer's own.
     */
    public void mesh(CgVfxEffect<?> effect, CgVfxSlot slot, @Nullable CgMeshLods mesh, float x, float y, float z,
                     Matrix4fc transform, float presence, float ex, float ey, float ez, float ew) {
        kind(slot, CgVfxSlot.Kind.MESH);
        CgVfxLook look = effect.look();
        for (CgVfxLook.Placed placed : look.on(slot)) {
            if (skips(effect, placed.layer())) continue;
            CgVfxTrace.count(DRAWS_MESH, 1);
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            CgMaterial material = materials.material(r, CgVfxSlot.Kind.MESH);
            CgMeshLods own = mesh != null ? mesh : placed.layer().mesh();
            dress(own != null ? world.draw(own, material) : world.draw(system.sphereMesh(), material), effect, r, x, y, z,
                    transform, own != null ? own.reach() : 1f, presence, ex, ey, ez, ew).submit();
        }
    }

    /**
     * Draws every layer on {@code slot}, a ribbons slot, on the ribbon mesh ({@code CgVfxRibbons}), its unit cube placed
     * by {@code transform} at {@code (x, y, z)} from the origin and scaled by each layer's size: stateless particles the
     * shader places inside that cube. Its shader reads the same per-draw data as {@link #mesh}'s.
     */
    public void ribbons(CgVfxEffect<?> effect, CgVfxSlot slot, float x, float y, float z, Matrix4fc transform,
                        float ex, float ey, float ez, float ew) {
        kind(slot, CgVfxSlot.Kind.RIBBONS);
        CgVfxLook look = effect.look();
        for (CgVfxLook.Placed placed : look.on(slot)) {
            if (skips(effect, placed.layer())) continue;
            CgVfxTrace.count(DRAWS_RIBBONS, 1);
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            dress(world.draw(system.ribbonMesh(), materials.material(r, CgVfxSlot.Kind.RIBBONS)), effect, r, x, y, z,
                    transform, CUBE_REACH, 1f, ex, ey, ez, ew).bounds(CgAABBc.UNIT).submit();
        }
    }

    /**
     * Draws {@code emitter}'s particles through every layer on its slot ({@code emitter.emitter().slot()}), as its
     * renderer says, from its GPU slot's records as Range wrote them for this view:
     * <ul>
     *   <li>{@code MESHES}: a mesh per particle, Range's object records, turned by its spin and sized by its size over
     *       life; {@code CG_OBJECT_CUSTOM0.xyz} is its velocity, {@code CG_OBJECT_CUSTOM1} its life 0..1, its seed, its
     *       opacity and its heat.</li>
     *   <li>{@code QUADS} and {@code ARCS}: the slot's particle records ({@code #pragma cg_use particle}), as many as
     *       Range kept. {@code CG_OBJECT_CUSTOM0}: the first record, the slot's capacity, the layer's size and 0;
     *       {@code CG_OBJECT_CUSTOM1}: the draw's centre minus the instance's origin, and the effect's age, so
     *       {@code CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz} is the origin the records are relative to. An
     *       {@code ARCS} draw is centred on the emitter's source, so {@code CG_OBJECT_TO_WORLD[3]} is the source.</li>
     * </ul>
     * Its events' children are drawn with it, each through the layers on its own definition's slot. An instance with
     * no slot yet (booked, not admitted) draws nothing.
     *
     * <pre>{@code
     * for (CgVfxEmitterInstance e : emitters) frame.particles(this, e);
     * }</pre>
     */
    public void particles(CgVfxEffect<?> effect, CgVfxEmitterInstance emitter) {
        // Its particles' bounds: a sphere round the source no particle can leave, as Niagara gives a GPU emitter fixed ones.
        CgVfxEmitter def = emitter.emitter();
        effect.bound((float) (emitter.originX() - effect.originX) + emitter.sourceX(),
                (float) (emitter.originY() - effect.originY) + emitter.sourceY(),
                (float) (emitter.originZ() - effect.originZ) + emitter.sourceZ(),
                def.reach(system.air().maxSpeed()) + def.largestSize() + emitter.books().entityBoxReach());
        for (int e = 0; e < emitter.emitter().events().size(); e++) {
            CgVfxEmitterInstance child = emitter.child(e);
            if (child != null) particles(effect, child);
        }
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, GPU_ZONE)) {
            slotParticles(effect, emitter);
        }
    }

    /** {@code emitter}'s own slot, per layer: one draw of its records, or one record of the merged draw. */
    private void slotParticles(CgVfxEffect<?> effect, CgVfxEmitterInstance emitter) {
        CgVfxGpuSteps.Tenant tenant = system.gpuTenant(emitter);
        if (tenant == null) return;
        CgVfxParticlePool pool = tenant.pool;
        int slot = tenant.slot, capacity = pool.capacity(slot);
        CgVfxEmitter.Renderer renderer = emitter.emitter().renderer();
        boolean arcs = renderer == CgVfxEmitter.Renderer.ARCS, meshes = renderer == CgVfxEmitter.Renderer.MESHES;
        float cx = arcs ? emitter.sourceX() : 0f, cy = arcs ? emitter.sourceY() : 0f, cz = arcs ? emitter.sourceZ() : 0f;
        // The instance's origin, not the effect's: a world-space instance stays where it was first stepped.
        double ox = emitter.originX(), oy = emitter.originY(), oz = emitter.originZ();
        CgVfxLook look = effect.look();
        CgVfxRange range = CgVfxRange.of(pool);
        float reach = 0f;
        boolean strips = false;
        for (CgVfxLook.Placed placed : look.on(emitter.emitter().slot())) {
            if (skips(effect, placed.layer())) continue;
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            CgMaterial material = placed.layer().strip() == CgVfxLayer.Strip.TRAILS ? materials.material(r, pool)
                    : materials.material(r, CgVfxSlot.Kind.PARTICLES);
            float size = CgVfxMaterials.size(effect, r);
            int row = materials.row(effect, r);
            if (!meshes && placed.layer().hasMesh()) {
                throw new IllegalStateException(placed.layer() + " draws a mesh, which only a mesh slot or a MESHES emitter takes");
            }
            if (placed.layer().strip() != CgVfxLayer.Strip.NONE) {
                strips = true;
                reach = Math.max(reach, size);
                strip(effect, emitter, placed, material, size, row, pool, range, slot, capacity, ox, oy, oz);
                continue;
            }
            boolean inOrder = blendsInOrder(material);
            if (inOrder) range.sorted(slot);
            if (meshes) {
                // One record set for every layer: the cull stamps each draw's customs and scale onto it, leaving
                // custom 0, Range's velocity.
                CgMeshLods own = placed.layer().mesh();
                reach = Math.max(reach, size * placed.layer().meshRadius());
                CgWorldRenderer.Draw draw = world.draw(own != null ? own : system.particleSphere(), material)
                        .instances(range.objects(), range.base(slot),
                                CgGpuCount.at(range.visible(), range.visibleWord(slot), capacity))
                        .instanceScale(size)
                        .gpuCulled()
                        .at(ox, oy, oz)
                        .custom(3, row, effect.age, effect.seed, effect.presence());
                CgVfxSystem.place(draw, placed, effect).submit();
                CgVfxTrace.count(DRAWS_PARTICLE_GPU, 1);
                continue;
            }
            reach = Math.max(reach, Math.max(size, 1f) * 4f);
            if (MERGED.get()) {
                SlotSet set = set(pool, material, placed, renderer, CgVfxLayer.Strip.NONE,
                        arcs ? CgIndirect.VERTICES : CgIndirect.INDICES, arcs ? CgVfxRibbons.VERTICES : 6);
                set.capacity = Math.max(set.capacity, capacity);
                int f = set.add(ox + cx, oy + cy, oz + cz, range.visibleWord(slot) * 4L);
                float[] c = set.custom;
                c[f] = range.base(slot);
                c[f + 1] = capacity;
                c[f + 2] = size;
                c[f + 4] = cx;
                c[f + 5] = cy;
                c[f + 6] = cz;
                c[f + 7] = effect.age;
                c[f + 12] = row;
                c[f + 13] = effect.age;
                c[f + 14] = effect.seed;
                c[f + 15] = effect.presence();
                CgVfxTrace.count(DRAWS_PARTICLE_GPU, 1);
                continue;
            }
            CgMesh mesh = slotMesh(renderer, capacity);
            CgWorldRenderer.Draw draw = world.draw(mesh, material)
                    .buffer(CgBindingPoints.PARTICLES, range.drawn())
                    .indirect(range.visible(), range.visibleWord(slot) * 4L, arcs ? CgIndirect.VERTICES : CgIndirect.INDICES,
                            arcs ? CgVfxRibbons.VERTICES : 6)
                    .gpuCulled()
                    .at(ox + cx, oy + cy, oz + cz)
                    .custom(0, range.base(slot), capacity, size, 0f)
                    .custom(1, cx, cy, cz, effect.age)
                    .custom(3, row, effect.age, effect.seed, effect.presence());
            CgVfxSystem.place(draw, placed, effect).submit();
            CgVfxTrace.count(DRAWS_PARTICLE_GPU, 1);
        }
        if (reach <= 0f) return;
        if (arcs || strips) {
            // An arc reaches from its source to its particle, which no radius about the particle covers.
            CgVfxEmitter def = emitter.emitter();
            pool.cullAbout(slot, def.reach(system.air().maxSpeed()) + emitter.books().entityBoxReach() + def.largestSize() * reach);
        } else {
            pool.cullScale(slot, reach);
        }
    }

    /**
     * One strip layer's draw of {@code slot}, vertex-pulled ({@code fx_trail.glsl}): a trail behind each visible particle
     * through its ring, or one ribbon through them newest first; a record of its pool's merged draw unless
     * {@code mergeParticles} is off. What its shader reads:
     * <ul>
     *   <li>{@code CG_OBJECT_CUSTOM0}: the first record, the slot's capacity, the layer's size, quads a segment.</li>
     *   <li>{@code CG_OBJECT_CUSTOM1}: the particle alpha, the particle step's seconds, 0, the effect's age.</li>
     *   <li>{@code CG_OBJECT_CUSTOM2}, trails: the slot's first ring point, rings, points a ring, the newest step.</li>
     * </ul>
     * Drawn at the instance's origin; culled as one sphere about it, the slot whole.
     */
    private void strip(CgVfxEffect<?> effect, CgVfxEmitterInstance emitter, CgVfxLook.Placed placed, CgMaterial material,
                       float size, int row, CgVfxParticlePool pool, CgVfxRange range, int slot, int capacity, double ox,
                       double oy, double oz) {
        CgVfxLayer layer = placed.layer();
        boolean trails = layer.strip() == CgVfxLayer.Strip.TRAILS;
        int subs = layer.subdivisions() > 0 ? layer.subdivisions() : PARTICLE_STRIP_QUADS, points = 0;
        if (trails) {
            points = pool.ringPoints(slot);
            if (points < 2) {
                throw new IllegalStateException(layer + " draws trails, but " + emitter.emitter().name()
                        + " keeps no positions: give it trail(points)");
            }
        } else {
            range.ribbon(slot, emitter.books().spawnedSoFar());
        }
        int per = (trails ? points - 1 : 1) * subs * 6;
        float firstRing = trails ? pool.ringFirst(slot) : 0f, step = trails ? pool.lastStep() : 0f;
        CgVfxTrace.count(DRAWS_PARTICLE_GPU, 1);
        if (MERGED.get()) {
            SlotSet set = set(pool, material, placed, CgVfxEmitter.Renderer.QUADS, layer.strip(), CgIndirect.VERTICES, per);
            set.capacity = Math.max(set.capacity, capacity);
            int f = set.add(ox, oy, oz, range.visibleWord(slot) * 4L);
            float[] c = set.custom;
            c[f] = range.base(slot);
            c[f + 1] = capacity;
            c[f + 2] = size;
            c[f + 3] = subs;
            c[f + 4] = particleAlpha;
            c[f + 5] = system.particleDt();
            c[f + 7] = effect.age;
            c[f + 8] = firstRing;
            c[f + 9] = capacity;
            c[f + 10] = points;
            c[f + 11] = step;
            c[f + 12] = row;
            c[f + 13] = effect.age;
            c[f + 14] = effect.seed;
            c[f + 15] = effect.presence();
            return;
        }
        CgWorldRenderer.Draw draw = world.draw(CgMesh.vertices(sizeClass(capacity, 1) * per, CgMeshTopology.TRIANGLES), material)
                .buffer(CgBindingPoints.PARTICLES, range.drawn())
                .indirect(range.visible(), range.visibleWord(slot) * 4L, CgIndirect.VERTICES, per)
                .gpuCulled()
                .at(ox, oy, oz)
                .custom(0, range.base(slot), capacity, size, subs)
                .custom(1, particleAlpha, system.particleDt(), 0f, effect.age)
                .custom(2, firstRing, capacity, points, step)
                .custom(3, row, effect.age, effect.seed, effect.presence());
        CgVfxSystem.place(draw, placed, effect).submit();
    }

    /**
     * Whether {@code material}'s Forward pass blends so that order matters: over, not added. An additive blend commutes,
     * and so does none; a material still compiling answers no. A look drawn through OIT must answer no too: it needs
     * neither the sort nor ordered instances (plan vfx-gpu decision 10).
     */
    private static boolean blendsInOrder(CgMaterial material) {
        CgBlendState blend = material.getPassRenderState(CgRenderPassVariant.FORWARD).getBlend();
        return blend != null && blend.enabled() && blend.dstRgb() != CgGL.GL_ONE;
    }

    /**
     * The shared mesh a GPU slot of {@code capacity} draws a {@code QUADS} or {@code ARCS} layer on, made on its first
     * ask; null for {@code MESHES}, which draw the particle sphere.
     */
    static CgMesh slotMesh(CgVfxEmitter.Renderer renderer, int capacity) {
        return switch (renderer) {
            case QUADS -> CgMesh.quads(sizeClass(capacity, CgVfxQuads.COUNT));
            case ARCS -> CgMesh.vertices(sizeClass(capacity, CgVfxRibbons.COUNT) * CgVfxRibbons.VERTICES,
                    CgMeshTopology.TRIANGLES);
            case MESHES -> null;
        };
    }

    /** The mesh size a slot of {@code capacity} draws on: powers of two from {@code least}, so slots share meshes. */
    private static int sizeClass(int capacity, int least) {
        return Math.max(least, capacity <= 1 ? 1 : Integer.highestOneBit(capacity - 1) << 1);
    }

    /**
     * Draws every layer on {@code slot}, a billboard slot, on one camera-facing quad ({@code CgMesh.quads(1)}) at
     * {@code (x, y, z)} from the origin, {@code size} times the layer's size from its centre to an edge: one particle.
     * Each is its own draw, so the world renderer sorts alpha-blended ones back to front and instances neighbours. Its
     * shader reads the same per-draw data as {@link #mesh}'s, {@code (ex, ey, ez, ew)} being the particle's own, and
     * turns the quad itself:
     *
     * <pre>{@code
     * vec3 right = vec3(cg_ViewMatrix[0][0], cg_ViewMatrix[1][0], cg_ViewMatrix[2][0]);
     * vec3 up = vec3(cg_ViewMatrix[0][1], cg_ViewMatrix[1][1], cg_ViewMatrix[2][1]);
     * vec3 world = CG_OBJECT_TO_WORLD[3].xyz
     *         + (right * FX_QUAD_CORNER.x + up * FX_QUAD_CORNER.y) * length(CG_OBJECT_TO_WORLD[0].xyz);
     * }</pre>
     */
    public void billboard(CgVfxEffect<?> effect, CgVfxSlot slot, float x, float y, float z, float size,
                          float ex, float ey, float ez, float ew) {
        kind(slot, CgVfxSlot.Kind.BILLBOARD);
        CgVfxLook look = effect.look();
        for (CgVfxLook.Placed placed : look.on(slot)) {
            if (skips(effect, placed.layer())) continue;
            CgVfxTrace.count(DRAWS_BILLBOARD, 1);
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            // Its bounds: the cube its transform scales, whichever way the shader turns it.
            dress(world.draw(CgMesh.quads(1), materials.material(r, CgVfxSlot.Kind.BILLBOARD)), effect, r, x, y, z,
                    sized.scaling(size), CUBE_REACH, 1f, ex, ey, ez, ew).bounds(CgAABBc.UNIT).submit();
        }
    }

    /**
     * Draws every layer on {@code slot}, a path-ribbons slot, on the ribbon mesh spread over {@code path} at
     * {@code row}: stateless particles riding the path itself, reading it through {@code fx_tube.glsl}
     * ({@code fx_ring_at}). What its shader reads:
     * <ul>
     *   <li>{@code _FxPath}; the header gives the effect's age and seed.</li>
     *   <li>{@code CG_OBJECT_CUSTOM0}: the path's row, 0, the layer's size, 0.</li>
     *   <li>{@code CG_OBJECT_CUSTOM1}: the draw's centre minus the effect's origin, so
     *       {@code CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz} is the origin; {@code .w} an intensity.</li>
     * </ul>
     * The draw's bounds are the path's, grown by its widest ring times the layer's size.
     */
    public void pathRibbons(CgVfxEffect<?> effect, CgVfxSlot slot, CgVfxPath path, int row, float intensity) {
        kind(slot, CgVfxSlot.Kind.PATH_RIBBONS);
        int count = path.count();
        CgVfxLook look = effect.look();
        CgVfxLook.Placed[] on = look.on(slot);
        if (count < 2 || on.length == 0) return;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE, widest = 0f;
        for (int i = 0; i < count; i++) {
            minX = Math.min(minX, path.x(i)); maxX = Math.max(maxX, path.x(i));
            minY = Math.min(minY, path.y(i)); maxY = Math.max(maxY, path.y(i));
            minZ = Math.min(minZ, path.z(i)); maxZ = Math.max(maxZ, path.z(i));
            widest = Math.max(widest, path.radius(i));
        }
        float cx = (minX + maxX) * 0.5f, cy = (minY + maxY) * 0.5f, cz = (minZ + maxZ) * 0.5f;
        for (CgVfxLook.Placed placed : on) {
            if (skips(effect, placed.layer())) continue;
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            float size = CgVfxMaterials.size(effect, r);
            float reach = widest * Math.max(size, 1f) * 2f;
            effect.boundBox(minX - reach, minY - reach, minZ - reach, maxX + reach, maxY + reach, maxZ + reach);
            // The ribbon mesh spans -1..1: half the extent each way.
            scaled.scaling((maxX - minX) * 0.5f + reach, (maxY - minY) * 0.5f + reach, (maxZ - minZ) * 0.5f + reach);
            CgWorldRenderer.Draw draw = world.draw(system.ribbonMesh(), materials.material(r, CgVfxSlot.Kind.PATH_RIBBONS))
                    .bounds(CgAABBc.UNIT)
                    .at(effect.originX + cx, effect.originY + cy, effect.originZ + cz).transform(scaled)
                    .subject(effect.subject)
                    .custom(0, row, 0f, size, 0f)
                    .custom(1, cx, cy, cz, intensity)
                    .custom(3, materials.row(effect, r), effect.age, effect.seed, effect.presence());
            CgVfxSystem.place(draw, placed, effect).submit();
            CgVfxTrace.count(DRAWS_PATH_RIBBONS, 1);
        }
    }

    /**
     * Draws every layer on {@code slot}, a trail slot, along {@code trail}: a strip each layer's shader builds per vertex
     * from the trail's points ({@code fx_trail.glsl}), its head where the trail's thing is this frame. A layer's size is
     * a ribbon's half-width in blocks, or how far a blade reaches from its base toward its tip (1 to the tip). What its
     * shader reads is {@link CgVfxSlot.Kind#TRAIL}'s.
     *
     * <pre>{@code
     * frame.trail(this, STREAK, streak);
     * }</pre>
     */
    public void trail(CgVfxEffect<?> effect, CgVfxSlot slot, CgVfxTrail trail) {
        kind(slot, CgVfxSlot.Kind.TRAIL);
        CgVfxLook look = effect.look();
        CgVfxLook.Placed[] on = look.on(slot);
        if (on.length == 0) return;
        double ax = trail.anchorX(), ay = trail.anchorY(), az = trail.anchorZ();
        CgVfxTrailTexture trails = system.trails();
        int first = trails.add(trail, alpha, ax, ay, az, trailBounds);
        if (first < 0) return;
        int points = trails.points(first);
        float[] b = trailBounds;
        float cx = (b[0] + b[3]) * 0.5f, cy = (b[1] + b[4]) * 0.5f, cz = (b[2] + b[5]) * 0.5f;
        float hx = (b[3] - b[0]) * 0.5f, hy = (b[4] - b[1]) * 0.5f, hz = (b[5] - b[2]) * 0.5f;
        boolean blade = trail.mode() == CgVfxTrail.Mode.BLADE;
        float ox = (float) (ax - effect.originX), oy = (float) (ay - effect.originY), oz = (float) (az - effect.originZ);
        for (CgVfxLook.Placed placed : on) {
            if (skips(effect, placed.layer())) continue;
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            float size = CgVfxMaterials.size(effect, r);
            int subs = placed.layer().subdivisions() > 0 ? placed.layer().subdivisions() : TRAIL_QUADS;
            CgMesh mesh = CgMesh.vertices(sizeClass((points - 1) * subs, 16) * 6, CgMeshTopology.TRIANGLES);
            // A ribbon reaches its widest half-width past its points; a blade past its tips as far as its size goes.
            float reach = blade ? Math.max(size - 1f, 0f) * 2f * Math.max(hx, Math.max(hy, hz)) : size * trail.widest();
            reach += 0.05f;
            effect.boundBox(ox + b[0] - reach, oy + b[1] - reach, oz + b[2] - reach,
                    ox + b[3] + reach, oy + b[4] + reach, oz + b[5] + reach);
            scaled.scaling(hx + reach, hy + reach, hz + reach);
            CgWorldRenderer.Draw draw = world.draw(mesh, materials.material(r, CgVfxSlot.Kind.TRAIL))
                    .bounds(CgAABBc.UNIT)
                    .at(ax + cx, ay + cy, az + cz).transform(scaled)
                    .subject(effect.subject)
                    .custom(0, first, points, size, trail.life())
                    .custom(1, cx, cy, cz, 0f)
                    .custom(2, subs, trail.mode().ordinal(), b[6], b[6] - b[7])
                    .custom(3, materials.row(effect, r), effect.age, effect.seed, effect.presence());
            CgVfxSystem.place(draw, placed, effect).submit();
            CgVfxTrace.count(DRAWS_TRAIL, 1);
        }
    }

    /**
     * Draws every layer on {@code slot}, a fluid slot, through {@code fluid}: a box round the tiles it holds, which each
     * layer's shader marches. What its shader reads is {@link CgVfxSlot.Kind#FLUID}'s. A fluid holding no tile draws
     * nothing.
     *
     * <pre>{@code
     * frame.fluid(this, CgVfxFluid.SLOT, fire);      // what CgVfxFluid.submit does
     * }</pre>
     */
    public void fluid(CgVfxEffect<?> effect, CgVfxSlot slot, CgVfxFluid fluid) {
        kind(slot, CgVfxSlot.Kind.FLUID);
        CgVfxFluidAtlas atlas = CgVfxFluidAtlas.get();
        CgGraphTexture shade = atlas.shade(), restA = atlas.restA(), restB = atlas.restB(), pages = atlas.pages();
        int index = fluid.index();
        if (index < 0 || shade == null || !fluid.bounds(volumeBox)) return;
        CgVfxLook look = effect.look();
        float cell = fluid.cellSize(), grid = -CgVfxFluidAtlas.SPAN * 0.5f * cell, weight = fluid.detailWeight();
        float cx = (volumeBox[0] + volumeBox[3]) * 0.5f, cy = (volumeBox[1] + volumeBox[4]) * 0.5f, cz = (volumeBox[2] + volumeBox[5]) * 0.5f;
        float ox = (float) (fluid.x() - effect.originX), oy = (float) (fluid.y() - effect.originY),
                oz = (float) (fluid.z() - effect.originZ);
        for (CgVfxLook.Placed placed : look.on(slot)) {
            if (skips(effect, placed.layer())) continue;
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            CgMaterial material = materials.material(r, CgVfxSlot.Kind.FLUID);
            materials.fluid(material, shade, restA, restB, pages);
            effect.boundBox(ox + volumeBox[0], oy + volumeBox[1], oz + volumeBox[2], ox + volumeBox[3], oy + volumeBox[4], oz + volumeBox[5]);
            CgWorldRenderer.Draw draw = world.draw(system.cubeMesh(), material)
                    .at(fluid.x() + cx, fluid.y() + cy, fluid.z() + cz)
                    .transform(sized.scaling(volumeBox[3] - volumeBox[0], volumeBox[4] - volumeBox[1], volumeBox[5] - volumeBox[2]))
                    .subject(effect.subject)
                    .halfResolution()
                    .custom(0, index, cell, weight, CgVfxMaterials.size(effect, r))
                    .custom(1, grid - cx, grid - cy, grid - cz, 0f)
                    .custom(3, materials.row(effect, r), effect.age, effect.seed, effect.presence());
            CgVfxSystem.place(draw, placed, effect).submit();
            CgVfxTrace.count(DRAWS_FLUID, 1);
        }
    }

    /**
     * Draws every layer on {@code slot}, a grid slot, through {@code grid}: the box it spans, which each layer's shader
     * marches. What its shader reads is {@link CgVfxSlot.Kind#GRID}'s. A grid waiting for a place draws nothing.
     *
     * <pre>{@code
     * frame.grid(this, CgVfxGrid3D.SLOT, trail);      // what CgVfxGrid3D.submit does
     * }</pre>
     */
    public void grid(CgVfxEffect<?> effect, CgVfxSlot slot, CgVfxGrid3D grid) {
        kind(slot, CgVfxSlot.Kind.GRID);
        CgVfxGridAtlas atlas = CgVfxGridAtlas.get();
        CgGraphTexture view = atlas.view(), light = atlas.light(), pages = atlas.pages();
        int index = grid.index();
        if (index < 0 || view == null || !grid.bounds(volumeBox)) return;
        CgVfxLook look = effect.look();
        float cell = grid.cellSize(), low = -CgVfxGridAtlas.SPAN * 0.5f * cell;
        float cx = (volumeBox[0] + volumeBox[3]) * 0.5f, cy = (volumeBox[1] + volumeBox[4]) * 0.5f, cz = (volumeBox[2] + volumeBox[5]) * 0.5f;
        float ox = (float) (grid.x() - effect.originX), oy = (float) (grid.y() - effect.originY),
                oz = (float) (grid.z() - effect.originZ);
        for (CgVfxLook.Placed placed : look.on(slot)) {
            if (skips(effect, placed.layer())) continue;
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            CgMaterial material = materials.material(r, CgVfxSlot.Kind.GRID);
            materials.grid(material, view, light, pages);
            effect.boundBox(ox + volumeBox[0], oy + volumeBox[1], oz + volumeBox[2], ox + volumeBox[3], oy + volumeBox[4], oz + volumeBox[5]);
            CgWorldRenderer.Draw draw = world.draw(system.cubeMesh(), material)
                    .at(grid.x() + cx, grid.y() + cy, grid.z() + cz)
                    .transform(sized.scaling(volumeBox[3] - volumeBox[0], volumeBox[4] - volumeBox[1], volumeBox[5] - volumeBox[2]))
                    .subject(effect.subject)
                    .halfResolution()
                    .custom(0, index, cell, 0f, CgVfxMaterials.size(effect, r))
                    .custom(1, low - cx, low - cy, low - cz, grid.extinction())
                    .custom(3, materials.row(effect, r), effect.age, effect.seed, effect.presence());
            CgVfxSystem.place(draw, placed, effect).submit();
            CgVfxTrace.count(DRAWS_VOLUME, 1);
        }
    }

    /**
     * {@code draw} placed and given the per-draw data every mesh-like slot's shader reads, for the caller to submit; its
     * mesh, {@code radius} across before its transform, grows the effect's bounds.
     */
    private CgWorldRenderer.Draw dress(CgWorldRenderer.Draw draw, CgVfxEffect<?> effect, CgVfxMaterials.Resolved r, float x,
                                       float y, float z, Matrix4fc transform, float radius, float presence, float ex,
                                       float ey, float ez, float ew) {
        float size = CgVfxMaterials.size(effect, r);
        scaled.set(transform).scale(size);
        scaled.getScale(scale);
        effect.bound(x, y, z, radius * Math.max(scale.x, Math.max(scale.y, scale.z)));
        draw.at(effect.originX + x, effect.originY + y, effect.originZ + z).transform(scaled).subject(effect.subject)
                .custom(0, size, 0f, effect.age, effect.seed)
                .custom(1, ex, ey, ez, ew)
                .custom(3, materials.row(effect, r), effect.age, effect.seed, effect.presence() * presence);
        return CgVfxSystem.place(draw, r.placed, effect);
    }
}
