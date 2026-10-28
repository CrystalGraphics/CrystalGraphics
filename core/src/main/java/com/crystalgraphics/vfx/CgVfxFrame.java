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
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.particle.renderer.CgVfxArcRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxBeamRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxLightRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxMeshRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxRibbonRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxSequenceRendererProperties;
import com.crystalgraphics.vfx.particle.renderer.CgVfxSpriteRendererProperties;
import com.crystalgraphics.shape.bounds.CgAABBc;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.variable.CgVar;
import com.crystalgraphics.render.graph.CgGraphBuffer;
import com.crystalgraphics.render.graph.CgGraphTexture;
import com.crystalgraphics.vfx.fluid.CgVfxFluid;
import com.crystalgraphics.vfx.fluid.CgVfxFluidAtlas;
import com.crystalgraphics.vfx.grid.CgVfxGrid3D;
import com.crystalgraphics.vfx.grid.CgVfxGridAtlas;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxParticleLights;
import com.crystalgraphics.vfx.particle.gpu.draw.CgVfxRange;
import com.crystalgraphics.vfx.particle.gpu.sim.CgVfxParticlePool;
import com.crystalgraphics.vfx.particle.ribbon.CgVfxRibbonGeneration;
import com.crystalgraphics.vfx.particle.ribbon.CgVfxRibbonShape;
import com.crystalgraphics.vfx.path.CgVfxPath;
import com.crystalgraphics.vfx.prefab.CgVfxPrefab;
import com.crystalgraphics.vfx.render.CgVfxQuads;
import com.crystalgraphics.vfx.render.CgVfxRibbons;
import com.crystalgraphics.vfx.render.CgVfxSlotRenderer;
import com.crystalgraphics.vfx.sequence.CgVfxClipAtlas;
import com.crystalgraphics.vfx.sequence.CgVfxNodeClip;
import com.crystalgraphics.vfx.particle.gpu.CgVfxDrawLayout;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a {@link CgVfxEffect} draws through in one frame, handed to it by {@link CgVfxSystem#submit}: each call draws
 * one slot, every layer its look has on it.
 *
 * <pre>{@code
 * @Override protected void submit(CgVfxFrame frame) {
 *     int row = frame.path(path, seed, age);
 *     frame.tube(this, BODY, path, row);                                        // every layer on BODY
 *     frame.mesh(this, HEAD, x, y, z, transform, halfWidth, halfLength, 1f, 0f);   // every layer on HEAD
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A slot no layer of the look draws on costs nothing: a look without its realistic body draws no tube.</li>
 *   <li>A renderer above this frame's quality tier (its {@code quality}) draws nothing.</li>
 *   <li>Every draw's {@code CG_OBJECT_CUSTOM3} is its header ({@code fx_draw.glsl}): its row of bound values, the
 *       effect's age, its seed and its presence. The other customs are the slot kind's ({@link CgVfxSlot.Kind}).</li>
 *   <li>Calling a method of another kind than the slot's throws.</li>
 * </ul>
 */
public final class CgVfxFrame {

    private static final int DRAWS_MESH = CgTrace.name("vfx.draws.mesh"), DRAWS_RIBBONS = CgTrace.name("vfx.draws.ribbons"),
            DRAWS_BILLBOARD = CgTrace.name("vfx.draws.billboard"), DRAWS_PATH_RIBBONS = CgTrace.name("vfx.draws.path-ribbons"),
            DRAWS_VOLUME = CgTrace.name("vfx.draws.volume"),
            DRAWS_FLUID = CgTrace.name("vfx.draws.fluid"), LIGHTS = CgTrace.name("vfx.lights.slot"),
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
    /**
     * An emitter whose books rule out a live particle submits no draws. Niagara makes no render data for an emitter it
     * knows is empty (NiagaraRendererSprites.cpp GenerateDynamicData, NiagaraSystemRenderData.cpp for complete ones);
     * it submits a GPU emitter's batch regardless only because the count would need a readback, which these books
     * do not. False is the measurement switch.
     */
    private static final CgVar.Bool SKIP_IDLE = CgVar.bool("crystalgraphics.vfx.skipIdleEmitters", true, CgVar.DEV,
            "An emitter before its first spawn or after its last death submits no draws.");
    private final CgVfxSystem system;
    private final CgVfxMaterials materials;
    private final Matrix4f scaled = new Matrix4f(), sized = new Matrix4f();
    /** A gas's box, from its middle: lo xyz then hi xyz. */
    private final float[] volumeBox = new float[6];
    private final Vector3f scale = new Vector3f();
    /** Blocks from its source a beam slot is drawn within: its far ends are on the GPU alone. */
    private static final float BEAM_REACH = 256f;
    /** How far the unit cube's corners are from its middle: what a ribbons or billboard draw reaches. */
    private static final float CUBE_REACH = 1.7320508f;
    private CgWorldRenderer world;
    private float alpha;
    /** Where the merged particle draws sort as one group: the middle of the system's effects. */
    private double groupX, groupY, groupZ;
    /** Every particle proxy made, by what it draws: a renderer finds its own once, when it is made. */
    private final Map<ProxyKey, Proxy> proxies = new HashMap<>();
    /** The proxies with records this frame, in the order first written. */
    private final List<Proxy> used = new ArrayList<>();
    /** Bumped by {@link #begin}: a proxy whose stamp is older holds last frame's records. */
    private int stamp;

    CgVfxFrame(CgVfxSystem system, CgVfxMaterials materials) {
        this.system = system;
        this.materials = materials;
    }

    void begin(CgWorldRenderer world, float alpha, double groupX, double groupY, double groupZ) {
        this.world = world;
        this.alpha = alpha;
        this.groupX = groupX;
        this.groupY = groupY;
        this.groupZ = groupZ;
        stamp++;
        used.clear();
    }

    /**
     * Draws the merged particle proxies: one draw a pool and material, each live slot a record of it. After
     * {@link #particles}. Proxies of a released pool are dropped.
     */
    void end() {
        CgVfxParticleLights.get().publish();
        for (int i = 0; i < used.size(); i++) {
            Proxy proxy = used.get(i);
            // A pool that has not recorded its first step has no storage yet, and no particle to draw.
            if (proxy.key.pool().records() == null) continue;
            CgVfxRange range = CgVfxRange.of(proxy.key.pool());
            boolean beams = proxy.key.strip() == Strip.BEAM;
            if (beams && proxy.key.pool().beamRuns() == null) continue;
            if (proxy.meshCapacity != proxy.capacity) {
                proxy.meshCapacity = proxy.capacity;
                proxy.mesh = proxy.key.strip() == Strip.NONE ? slotMesh(proxy.key.renderer(), proxy.capacity)
                        : CgMesh.vertices(sizeClass(proxy.capacity, 1) * proxy.key.factor(), CgMeshTopology.TRIANGLES);
            }
            CgMesh mesh = proxy.mesh;
            CgWorldRenderer.Draw draw = world.draw(mesh, proxy.key.material())
                    .buffer(CgBindingPoints.PARTICLES, proxy.key.pool().drawn())
                    .buffer(CgBindingPoints.PARTICLE_ORDER, range.order())
                    .indirectEach(range.visible(), proxy.mode, proxy.key.factor())
                    .gpuCulled()
                    .at(groupX, groupY, groupZ);
            if (beams) draw.buffer(CgBindingPoints.PARTICLE_BEAMS, proxy.key.pool().beamRuns());
            CgVfxSystem.place(draw, proxy.placed, groupX, groupY, groupZ);
            draw.group(groupX, groupY, groupZ).batchKey(materials.key(proxy.key.material()));
            for (int r = 0; r < proxy.records; r++) {
                draw.each(proxy.at[r * 3], proxy.at[r * 3 + 1], proxy.at[r * 3 + 2], proxy.count[r]);
                for (int c = 0; c < 4; c++) {
                    int f = r * 16 + c * 4;
                    draw.custom(c, proxy.custom[f], proxy.custom[f + 1], proxy.custom[f + 2], proxy.custom[f + 3]);
                }
            }
            draw.submit();
            CgVfxTrace.count(DRAWS_PARTICLE_SETS, 1);
        }
        if (!proxies.isEmpty() && releasesSeen != CgVfxParticlePool.releases()) {
            releasesSeen = CgVfxParticlePool.releases();   // an iterator a frame otherwise
            proxies.values().removeIf(p -> p.key.pool().isReleased());
        }
    }

    /**
     * A GPU particle draw of {@code effect} placed where its system's merged draws sort, while they merge, so every
     * particle layer of a look keeps its order against the others (a Cascade system's emitters draw in list order);
     * in its effect's group otherwise.
     */
    private CgWorldRenderer.Draw placeParticles(CgWorldRenderer.Draw draw, CgVfxLook.Placed placed, CgVfxEffect<?> effect) {
        return MERGED.get() ? CgVfxSystem.place(draw, placed, groupX, groupY, groupZ) : CgVfxSystem.place(draw, placed, effect);
    }

    /** {@link CgVfxParticlePool#releases()} when its proxies were last checked for a released pool. */
    private int releasesSeen;

    /** Drops every proxy: the system's pools are gone. */
    void clear() {
        proxies.clear();
        used.clear();
    }

    /**
     * What a proxy draws: one pool's slots through one material, each particle as {@code factor} elements. Mutable only
     * as {@link #probe}, the key a lookup is set into, so building renderers allocates no key for a proxy that exists.
     */
    private static final class ProxyKey {
        private CgVfxParticlePool pool;
        private CgMaterial material;
        private CgVfxDrawLayout renderer;
        private Strip strip;
        private int factor;

        ProxyKey set(CgVfxParticlePool pool, CgMaterial material, CgVfxDrawLayout renderer, Strip strip, int factor) {
            this.pool = pool;
            this.material = material;
            this.renderer = renderer;
            this.strip = strip;
            this.factor = factor;
            return this;
        }

        CgVfxParticlePool pool() {
            return pool;
        }

        CgMaterial material() {
            return material;
        }

        CgVfxDrawLayout renderer() {
            return renderer;
        }

        Strip strip() {
            return strip;
        }

        int factor() {
            return factor;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof ProxyKey k && k.pool == pool && k.material == material && k.renderer == renderer
                    && k.strip == strip && k.factor == factor;
        }

        @Override
        public int hashCode() {
            int h = System.identityHashCode(pool);
            h = 31 * h + System.identityHashCode(material);
            h = 31 * h + renderer.ordinal();
            h = 31 * h + strip.ordinal();
            return 31 * h + factor;
        }
    }

    private final ProxyKey probe = new ProxyKey();

    /** What strip a proxy's particles draw as: none (a quad, an arc or a mesh each), or a beam each. */
    private enum Strip { NONE, BEAM }

    /** {@link #probe}'s proxy, made at its first ask with a key of its own: once per renderer, when the renderer is made. */
    private Proxy proxy(ProxyKey probe, CgVfxLook.Placed placed, CgIndirect mode) {
        Proxy proxy = proxies.get(probe);
        if (proxy == null) {
            ProxyKey key = new ProxyKey().set(probe.pool, probe.material, probe.renderer, probe.strip, probe.factor);
            proxies.put(key, proxy = new Proxy(key, placed, mode));
        }
        return proxy;
    }

    /**
     * One pool's merged draw through one material, kept between frames as 04's proxies are: each frame its live slots
     * write a record of their dynamic data, and it draws once. Niagara's merged GPU batch, per pool and material.
     */
    private static final class Proxy {
        final ProxyKey key;
        /** The first layer drawn through it: what places the merged draw. */
        final CgVfxLook.Placed placed;
        final CgIndirect mode;
        /** This frame's largest slot: the mesh is sized for it. */
        int capacity;
        /** The mesh it last drew on, and the capacity it was sized for: looked up again only when that changes. */
        CgMesh mesh;
        int meshCapacity = -1;
        int records, stamp;
        double[] at = new double[16 * 3];
        float[] custom = new float[16 * 16];
        long[] count = new long[16];

        Proxy(ProxyKey key, CgVfxLook.Placed placed, CgIndirect mode) {
            this.key = key;
            this.placed = placed;
            this.mode = mode;
        }

        /** A record for a slot of {@code slotCapacity}, its customs zeroed; answers its first custom float. */
        int add(CgVfxFrame frame, int slotCapacity, double x, double y, double z, long countOffset) {
            if (stamp != frame.stamp) {
                stamp = frame.stamp;
                records = 0;
                capacity = 0;
                frame.used.add(this);
            }
            capacity = Math.max(capacity, slotCapacity);
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
    /** Whether {@code effect} at its tier, with the parts skipped, leaves {@code placed} out. */
    private boolean skips(CgVfxEffect<?> effect, CgVfxLook.Placed placed) {
        CgQuality from = placed.from();
        if (from != null && !system.quality(effect.lowered).atLeast(from)) return true;
        String name = placed.partName();
        for (String token : CgVfxSystem.skippedParts()) {
            if (name.contains(token)) return true;
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
            if (skips(effect, placed)) continue;
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            if (placed.slotRenderer().light() != null) {
                pathLights(effect, r, path, placed.slotRenderer().light().castShadows());
                continue;
            }
            boundPath(effect, path, CgVfxMaterials.size(effect, r));
            CgMesh mesh = placed.volume() ? system.volumeMesh() : placed.closed() ? system.closedTubeMesh() : system.tubeMesh();
            system.tube().submit(world, mesh, materials.material(r, CgVfxSlot.Kind.TUBE), placed, path, row, effect,
                    CgVfxMaterials.size(effect, r), materials.row(effect, r), effect.age, effect.seed, effect.presence());
        }
    }

    /**
     * Draws every slot renderer on {@code slot}, a mesh slot, on a unit sphere (or the renderer's own mesh)
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
     * of the effect's own: what {@code FX_PRESENCE} reads is their product, so a prefab node fades with its effect.
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
            if (skips(effect, placed)) continue;
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            if (placed.slotRenderer().light() != null) {
                meshLight(effect, r, placed.slotRenderer().light(), x, y, z, transform, presence * ez);
                continue;
            }
            CgVfxTrace.count(DRAWS_MESH, 1);
            CgMaterial material = materials.material(r, CgVfxSlot.Kind.MESH);
            CgMeshLods own = mesh != null ? mesh : placed.mesh();
            dress(own != null ? world.draw(own, material) : world.draw(system.sphereMesh(), material), effect, r, x, y, z,
                    transform, own != null ? own.reach() : 1f, presence, ex, ey, ez, ew).submit();
        }
    }

    /**
     * A slot light on a mesh slot, at {@code (x, y, z)} from the origin, its size the transform's x scale: its colour
     * times {@code strength}, the effect's presence and {@code s²}; a spot and a source line along the transform's +Y
     * ({@link CgVfxSlotRenderer.Light}).
     */
    private void meshLight(CgVfxEffect<?> effect, CgVfxMaterials.Resolved r, CgVfxSlotRenderer.Light def, float x,
                           float y, float z, Matrix4fc transform, float strength) {
        float s = (float) Math.sqrt(transform.m00() * transform.m00() + transform.m01() * transform.m01()
                + transform.m02() * transform.m02());
        CgVfxMaterials.light(effect, r, light);
        float reach = light[3] * s, k = strength * effect.presence() * s * s;
        if (reach <= 0f || k <= 0f) return;
        effect.bound(x, y, z, reach);
        world.lights().addLinear(effect.originX + x, effect.originY + y, effect.originZ + z, reach, light[0] * k,
                light[1] * k, light[2] * k, 0f).source(light[4] * s).shadowed(def.castShadows());
        float ax = transform.m10(), ay = transform.m11(), az = transform.m12();
        if (ax * ax + ay * ay + az * az > 0f) {
            if (light[5] > 0f) world.lights().tube(ax, ay, az, light[5] * s);
            if (def.spot()) world.lights().spot(ax, ay, az, light[6], light[7]);
        }
        CgVfxTrace.count(LIGHTS, 1);
    }

    /**
     * A slot light on a tube slot: a chain of tube lights along {@code path}, one per stretch of half a reach, chord to
     * chord so it follows the bend, each at its end ring's radius {@code s}. A tube is brightest across its middle
     * ({@code 1/d²} there, as a point), so one tube the path's length would leave its ends dark; a stretch's colour is
     * times its length and {@code s}, so the chain far off sums to a line of the colour times {@code s} a block.
     */
    private void pathLights(CgVfxEffect<?> effect, CgVfxMaterials.Resolved r, CgVfxPath path, boolean shadows) {
        CgVfxMaterials.light(effect, r, light);
        float presence = effect.presence();
        int last = path.count() - 1;
        if (presence <= 0f || light[3] <= 0f || last < 1) return;
        int from = 0;
        for (int i = 1; i <= last; i++) {
            float s = path.radius(i), since = path.arc(i) - path.arc(from);
            if (s <= 0f || since < 0.5f * light[3] * s && i < last) continue;
            float dx = path.x(i) - path.x(from), dy = path.y(i) - path.y(from), dz = path.z(i) - path.z(from);
            float chord = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float x = 0.5f * (path.x(i) + path.x(from)), y = 0.5f * (path.y(i) + path.y(from)), z = 0.5f * (path.z(i) + path.z(from));
            float reach = light[3] * s + 0.5f * chord, k = presence * since * s;
            from = i;
            if (chord <= 0f) continue;
            effect.bound(x, y, z, reach);
            world.lights().addLinear(effect.originX + x, effect.originY + y, effect.originZ + z, reach, light[0] * k,
                    light[1] * k, light[2] * k, 0f).tube(dx, dy, dz, chord).source(light[4] * s).shadowed(shadows);
            CgVfxTrace.count(LIGHTS, 1);
        }
    }

    /** A slot light's colour, reach, source radius and length and cone angles, filled per light. */
    private final float[] light = new float[8];

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
            if (skips(effect, placed)) continue;
            CgVfxTrace.count(DRAWS_RIBBONS, 1);
            CgVfxMaterials.Resolved r = materials.resolve(look, placed);
            dress(world.draw(system.ribbonMesh(), materials.material(r, CgVfxSlot.Kind.RIBBONS)), effect, r, x, y, z,
                    transform, CUBE_REACH, 1f, ex, ey, ez, ew).bounds(CgAABBc.UNIT).submit();
        }
    }

    /**
     * Draws every GPU emitter instance's particles, each through the layers on its emitter's slot, from its GPU slot's
     * records as Range wrote them for this view. The system calls it after every effect's {@code submit}: an effect never
     * draws its emitters itself, it steps them ({@code tick(instance, dt)}), and one it wants hidden is
     * {@link CgVfxEmitterInstance#drawn(boolean)} false. Each renderer's shader reads:
     * <ul>
     *   <li>{@code MESHES}: a mesh per particle, Range's object records, turned by its spin and sized by its size over
     *       life; {@code CG_OBJECT_CUSTOM0.xyz} is its velocity, {@code CG_OBJECT_CUSTOM1} its life 0..1, its seed, its
     *       opacity and its heat.</li>
     *   <li>{@code SEQUENCE}: each part of its clip's prefab on every particle, a draw a part and layer of Range's object
     *       records ({@link CgVfxSlot.Kind#SEQUENCE}), through the layers on the part's slot: the look holds the
     *       prefab's {@code kit()}. Each record is the particle's place, turned with its effect and sized by its size;
     *       the part's node moves it from there at the particle's age.</li>
     *   <li>{@code QUADS} and {@code ARCS}: the slot's particle records ({@code #pragma cg_use particle}), as many as
     *       Range kept. {@code CG_OBJECT_CUSTOM0}: the first record, the slot's capacity, the layer's size and 0;
     *       {@code CG_OBJECT_CUSTOM1}: the draw's centre minus the instance's origin, and the effect's age, so
     *       {@code CG_OBJECT_TO_WORLD[3].xyz - CG_OBJECT_CUSTOM1.xyz} is the origin the records are relative to. An
     *       {@code ARCS} draw is centred on the emitter's source, so {@code CG_OBJECT_TO_WORLD[3]} is the source.</li>
     * </ul>
     * An instance's renderers are made at its first drawn frame and kept until its slot closes, made again only when
     * its tier or the skipped parts change: Niagara's ({@code FNiagaraSystemRenderData::RecacheRenderers}). Each frame
     * writes only their dynamic data, and none for an emitter its books rule empty, as Niagara makes none for a complete
     * one ({@code GenerateSystemRenderData}).
     */
    void particles() {
        try (CgTrace.Zone ignored = CgTrace.zone(CgVfxTrace.CHANNEL, GPU_ZONE)) {
            CgVfxRange.layOut();
            List<CgVfxGpuSteps.Tenant> tenants = system.gpuSteps().tenantList();
            for (int i = 0; i < tenants.size(); i++) draw(tenants.get(i));
        }
    }

    /** One instance's draws this frame: its bounds always, its renderers' dynamic data while it may have particles. */
    private void draw(CgVfxGpuSteps.Tenant tenant) {
        CgVfxEffect<?> effect = tenant.effect;
        if (effect == null || effect.state() == CgVfxEffect.State.DEAD || effect.asleep || !tenant.drawn()) return;
        CgVfxEmitterInstance emitter = tenant.instance;
        CgVfxEmitter def = emitter.emitter();
        Renderers rs = tenant.renderers;
        if (rs == null) tenant.renderers = rs = new Renderers(def);
        // Its particles' bounds: a sphere round the source no particle can leave, as Niagara gives a GPU emitter fixed ones.
        effect.bound((float) (emitter.originX() - effect.originX) + emitter.sourceX(),
                (float) (emitter.originY() - effect.originY) + emitter.sourceY(),
                (float) (emitter.originZ() - effect.originZ) + emitter.sourceZ(),
                rs.still + rs.perWind * system.air().maxSpeed() + emitter.books().entityBoxReach());
        if (SKIP_IDLE.get() && !emitter.mayHaveParticles()) return;
        CgQuality quality = system.quality(effect.lowered);
        String[] skipped = CgVfxSystem.skippedParts();
        if (rs.quality != quality || rs.skipped != skipped || rs.look != effect.look() || rs.merged != MERGED.get()) {
            build(rs, effect, tenant, quality, skipped);
        }
        if (rs.count == 0) return;
        CgVfxParticlePool pool = tenant.pool;
        // A pool that has not recorded its first step has no storage yet, and no particle to draw.
        if (pool.records() == null) return;
        int slot = tenant.slot, capacity = pool.capacity(slot);
        CgVfxRange range = CgVfxRange.of(pool);
        if (def.maxDrawCount() >= 0) range.capped(slot, def.maxDrawCount());
        if (def.drawLayout() == CgVfxDrawLayout.SEQUENCE) {
            sequence(effect, emitter, rs, pool, range, slot, capacity);
            return;
        }
        boolean arcs = def.drawLayout() == CgVfxDrawLayout.ARCS;
        float cx = arcs ? emitter.sourceX() : 0f, cy = arcs ? emitter.sourceY() : 0f, cz = arcs ? emitter.sourceZ() : 0f;
        // The instance's origin, not the effect's: a world-space instance stays where it was first stepped.
        double ox = emitter.originX(), oy = emitter.originY(), oz = emitter.originZ();
        int base = range.laidBase(slot), visible = range.laidWord(slot);
        long word = visible * 4L;
        float reach = 0f;
        boolean strips = false, beams = false;
        for (int i = 0; i < rs.count; i++) {
            Renderer rd = rs.list[i];
            if (rd.shape == Shape.LIGHT) {
                CgVfxLightRendererProperties light = (CgVfxLightRendererProperties) rd.placed.renderer();
                CgVfxParticleLights.get().note(pool, slot, light.spawnFraction(),
                        light.inverseSquaredFalloff() ? light.intensityScale() : 0f, light.volumetricScattering(),
                        light.affectsTranslucency(), light.allowMegaLights());
                continue;
            }
            float size = CgVfxMaterials.size(effect, rd.r);
            int row = materials.row(effect, rd.r);
            CgVfxTrace.count(DRAWS_PARTICLE_GPU, 1);
            switch (rd.shape) {
                case BEAM -> {
                    beams = true;
                    reach = Math.max(reach, size);
                    beam(effect, rd, size, row, pool, range, slot, capacity, base, word, ox, oy, oz);
                }
                case RIBBON -> {
                    strips = true;
                    reach = Math.max(reach, size);
                    ribbon(effect, emitter, rd, size, row, pool, range, slot, ox, oy, oz);
                }
                case MESHES -> {
                    if (!rd.unsorted && blendsInOrder(rd.material)) range.sorted(slot, rd.sort);
                    // One record set for every layer: the cull stamps each draw's customs and scale onto it, leaving
                    // custom 0, Range's velocity.
                    reach = Math.max(reach, size * rd.meshRadius);
                    CgWorldRenderer.Draw draw = world.draw(rd.mesh, rd.material)
                            .instances(range.objects(), base, CgGpuCount.at(range.visible(), visible, capacity))
                            .instanceScale(size)
                            .gpuCulled()
                            .at(ox, oy, oz)
                            .custom(3, row, effect.age, effect.seed, effect.presence())
                            // the records a mesh factory's CG_PARTICLE_* reads, at each record's custom2.w
                            .buffer(CgBindingPoints.PARTICLES, pool.drawn())
                            .buffer(CgBindingPoints.PARTICLE_ORDER, range.order());
                    if (rd.submesh >= 0) draw.submesh(rd.submesh);
                    placeParticles(draw, rd.placed, effect).submit();
                }
                case MERGED -> {
                    if (!rd.unsorted && blendsInOrder(rd.material)) range.sorted(slot, rd.sort);
                    reach = Math.max(reach, Math.max(size, 1f) * 4f);
                    int f = rd.proxy.add(this, capacity, ox + cx, oy + cy, oz + cz, word);
                    float[] c = rd.proxy.custom;
                    c[f] = base;
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
                }
                case SINGLE -> {
                    if (!rd.unsorted && blendsInOrder(rd.material)) range.sorted(slot, rd.sort);
                    reach = Math.max(reach, Math.max(size, 1f) * 4f);
                    CgWorldRenderer.Draw draw = world.draw(slotMesh(def.drawLayout(), capacity), rd.material)
                            .buffer(CgBindingPoints.PARTICLES, pool.drawn()).buffer(CgBindingPoints.PARTICLE_ORDER, range.order())
                            .indirect(range.visible(), word, arcs ? CgIndirect.VERTICES : CgIndirect.INDICES,
                                    arcs ? CgVfxRibbons.VERTICES : 6)
                            .gpuCulled()
                            .at(ox + cx, oy + cy, oz + cz)
                            .custom(0, base, capacity, size, 0f)
                            .custom(1, cx, cy, cz, effect.age)
                            .custom(3, row, effect.age, effect.seed, effect.presence());
                    CgVfxSystem.place(draw, rd.placed, effect).submit();
                }
                default -> throw new IllegalStateException(rd.shape.name());
            }
        }
        if (reach <= 0f) return;
        if (beams) {
            // Its ends are its modules' and never read back: culled only past BEAM_REACH of its source.
            pool.cullAbout(slot, BEAM_REACH);
        } else if (arcs || strips) {
            // An arc reaches from its source to its particle, which no radius about the particle covers.
            pool.cullAbout(slot, rs.still + rs.perWind * system.air().maxSpeed() - def.largestSize() - rs.clipReach
                    + emitter.books().entityBoxReach() + def.largestSize() * reach);
        } else {
            pool.cullScale(slot, reach);
        }
    }

    /**
     * {@code rs}'s renderers made again for {@code effect}'s look at {@code quality}: one per layer on the emitter's slot
     * that the tier and the skipped parts keep, or per part and layer for a clip. Its material, mesh, proxy and every
     * constant are found here, once.
     */
    private void build(Renderers rs, CgVfxEffect<?> effect, CgVfxGpuSteps.Tenant tenant, CgQuality quality, String[] skipped) {
        rs.quality = quality;
        rs.skipped = skipped;
        rs.look = effect.look();
        rs.merged = MERGED.get();
        rs.count = 0;
        CgVfxEmitter def = tenant.instance.emitter();
        CgVfxLook look = effect.look();
        CgVfxParticlePool pool = tenant.pool;
        if (def.drawLayout() == CgVfxDrawLayout.SEQUENCE) {
            for (CgVfxLook.Placed placed : look.renderers(def)) {
                if (!(placed.renderer() instanceof CgVfxSequenceRendererProperties
                        || placed.renderer() instanceof CgVfxMeshRendererProperties m && m.prefab() != null)) {
                    throw new IllegalStateException(def.name() + "'s renderer " + placed.renderer().common().id()
                            + " is neither a sequence renderer nor a prefab's, but " + def.name() + " plays a clip");
                }
            }
            CgVfxPrefab prefab = def.clip().prefab();
            CgVfxClipAtlas.Section section = CgVfxClipAtlas.of(def.clip());
            for (int node = 0; node < prefab.nodes(); node++) {
                for (int part = prefab.firstPart(node), end = prefab.firstPart(node + 1); part < end; part++) {
                    CgMeshLods mesh = prefab.partMesh(part);
                    for (CgVfxLook.Placed placed : look.on(prefab.partSlot(part))) {
                        if (skips(effect, placed)) continue;
                        CgVfxMaterials.Resolved r = materials.resolve(look, placed);
                        Renderer rd = rs.next(Shape.SEQUENCE, placed, r, materials.material(r, CgVfxSlot.Kind.SEQUENCE));
                        CgMeshLods own = mesh != null ? mesh : placed.mesh();
                        rd.mesh = own;
                        rd.node = node;
                        rd.carried = section.reach(own != null ? own.reach() : 1f);
                    }
                }
            }
            return;
        }
        for (CgVfxLook.Placed placed : look.renderers(def)) {
            CgVfxRendererProperties renderer = placed.renderer();
            if (skips(effect, placed)) continue;
            if (renderer instanceof CgVfxLightRendererProperties) {
                // No material: its particles join the world's light list (CgVfxParticleLights).
                if (!pool.shape().lights()) {
                    throw new IllegalStateException(who(def, renderer) + " gives light, but " + def.name()
                            + " has no light_attributes to give its particles one");
                }
                rs.next(Shape.LIGHT, placed, null, null);
                continue;
            }
            // A mesh renderer may name no material of its own, each section drawing its override.
            CgVfxMaterials.Resolved r = renderer instanceof CgVfxMeshRendererProperties ? null : materials.resolve(look, placed);
            if (renderer instanceof CgVfxBeamRendererProperties beam) {
                requireRenderer(renderer, def, CgVfxDrawLayout.QUADS);
                beam(rs, placed, r, beam, tenant);
            } else if (renderer instanceof CgVfxRibbonRendererProperties ribbon) {
                requireRenderer(renderer, def, CgVfxDrawLayout.QUADS);
                Renderer rd = rs.next(Shape.RIBBON, placed, r, materials.material(r, CgVfxSlot.Kind.PARTICLES));
                rd.ribbon = ribbon;
                rd.ribbonShape = CgVfxRibbonShape.of(ribbon);
            } else if (renderer instanceof CgVfxMeshRendererProperties m) {
                requireRenderer(renderer, def, CgVfxDrawLayout.MESHES);
                if (m.prefab() != null || m.meshBinding() != null || m.meshes().size() > 1) {
                    throw new IllegalStateException(who(def, renderer) + " draws a prefab, a bound mesh or several meshes, which are not"
                            + " built yet: give it one mesh");
                }
                CgMeshLods lods = m.meshes().isEmpty() ? system.particleSphere() : mesh(String.valueOf(m.meshes().get(0).get("mesh")));
                // A draw a section, as Unreal's mesh particles draw a batch per section: each section's override,
                // past them the renderer's own (Cascade's mesh material module over its required material). A mesh
                // of one section with nothing overridden is one draw of the whole.
                int submeshes = lods.finest().submeshCount();
                boolean bySection = submeshes > 1 || !m.materialOverrides().isEmpty();
                int sections = bySection ? Math.max(submeshes, m.materialOverrides().size()) : 1;
                CgVfxMaterials.Resolved own = m.common().material() != null ? materials.resolve(look, placed) : null;
                for (int s = 0; s < sections; s++) {
                    CgVfxMaterials.Resolved section = s < m.materialOverrides().size() ? materials.resolve(look, placed, s) : own;
                    if (section == null) {
                        // A section past its overrides would draw the mesh's own material, which a mesh file does not carry here.
                        throw new IllegalStateException(who(def, renderer) + "'s section " + s + " of " + sections
                                + " has no material: give the renderer a material, or an override for every section");
                    }
                    Renderer rd = rs.next(Shape.MESHES, placed, section, materials.material(section, CgVfxSlot.Kind.PARTICLES));
                    rd.mesh = lods;
                    rd.meshRadius = m.meshes().isEmpty() ? 1f : lods.reach();
                    rd.unsorted = m.sort() == CgVfxSpriteRendererProperties.Sort.NONE;
                    rd.sort = rangeSort(m.sort());
                    rd.submesh = bySection ? s : -1;
                }
            } else if (renderer instanceof CgVfxSpriteRendererProperties sprite) {
                requireRenderer(renderer, def, CgVfxDrawLayout.QUADS);
                quads(rs, placed, r, materials.material(r, CgVfxSlot.Kind.PARTICLES), pool, def, false);
                rs.list[rs.count - 1].unsorted = sprite.sort() == CgVfxSpriteRendererProperties.Sort.NONE;
                rs.list[rs.count - 1].sort = rangeSort(sprite.sort());
            } else if (renderer instanceof CgVfxArcRendererProperties) {
                requireRenderer(renderer, def, CgVfxDrawLayout.ARCS);
                quads(rs, placed, r, materials.material(r, CgVfxSlot.Kind.PARTICLES), pool, def, true);
            } else {
                throw new IllegalStateException(who(def, renderer) + " plays a sequence, which only an emitter playing a clip draws");
            }
        }
    }

    /** A quad's or an arc's renderer: a record of its pool's merged draw, or a draw of its own when merging is off. */
    private void quads(Renderers rs, CgVfxLook.Placed placed, CgVfxMaterials.Resolved r, CgMaterial material,
                       CgVfxParticlePool pool, CgVfxEmitter def, boolean arcs) {
        if (MERGED.get()) {
            Renderer rd = rs.next(Shape.MERGED, placed, r, material);
            int factor = arcs ? CgVfxRibbons.VERTICES : 6;
            rd.proxy = proxy(probe.set(pool, material, def.drawLayout(), Strip.NONE, factor), placed,
                    arcs ? CgIndirect.VERTICES : CgIndirect.INDICES);
        } else {
            rs.next(Shape.SINGLE, placed, r, material);
        }
    }

    /**
     * A beam renderer: a strip a particle, placed by the strip factory from its pool's beam runs (CgVfxBeams), as
     * many segments a sheet as its noise steps times their tessellation, or its interpolation points.
     */
    private void beam(Renderers rs, CgVfxLook.Placed placed, CgVfxMaterials.Resolved r, CgVfxBeamRendererProperties beam,
                      CgVfxGpuSteps.Tenant tenant) {
        CgVfxParticlePool pool = tenant.pool;
        CgVfxEmitter def = tenant.instance.emitter();
        if (!pool.shape().beams()) {
            throw new IllegalStateException(who(def, beam) + " draws beams, but " + def.name()
                    + " has no beam_source or beam_target to place them");
        }
        Renderer rd = rs.next(Shape.BEAM, placed, r, materials.material(r, CgVfxSlot.Kind.PARTICLES));
        rd.beam = beam;
        CgVfxModule.BeamNoise noise = null;
        for (CgVfxModule m : def.modules()) if (m instanceof CgVfxModule.BeamNoise n) noise = n;
        rd.subs = noise != null ? (noise.frequency() + 1) * Math.max(noise.tessellation(), 1)
                : Math.max(beam.interpolationPoints(), 1);
        rd.per = beam.sheets() * rd.subs * 6;
        rd.beamFrequency = noise != null ? noise.frequency() : 0;
        rd.beamFlags = (noise != null && noise.targetNoise() ? 1 : 0) | (beam.upVectorStepSize() == 1 ? 2 : 0)
                | (beam.textureTileDistance() > 0f ? 4 : 0);
        if (MERGED.get()) {
            rd.proxy = proxy(probe.set(pool, rd.material, CgVfxDrawLayout.QUADS, Strip.BEAM, rd.per), placed,
                    CgIndirect.VERTICES);
        }
    }

    /** Fails naming {@code renderer} unless {@code def}'s particles are laid out as {@code layout}, which it draws. */
    private static void requireRenderer(CgVfxRendererProperties renderer, CgVfxEmitter def, CgVfxDrawLayout layout) {
        if (def.drawLayout() != layout) {
            throw new IllegalStateException(who(def, renderer) + " draws its particles as " + layout + ", but "
                    + def.name() + " lays them out as " + def.drawLayout());
        }
    }

    /** How an error names {@code renderer}: built only when one throws, so building renderers allocates no text. */
    private static String who(CgVfxEmitter def, CgVfxRendererProperties renderer) {
        return def.name() + "'s renderer " + renderer.common().id();
    }

    /** Every mesh a mesh renderer named, provided or loaded once and kept: a mesh uploaded stays. */
    private final Map<String, CgMeshLods> meshes = new HashMap<>();

    private CgMeshLods mesh(String path) {
        return meshes.computeIfAbsent(path, CgMeshLoader::lods);
    }

    /**
     * A {@code SEQUENCE} slot's draws: every part of every node of its clip's prefab, through each layer on the part's
     * slot, its instances the slot's object records, its node's constants in {@code CUSTOM2}. Each pads its culling by
     * how far the clip carries the part from its particle, which is the slot's cull scale too.
     */
    private void sequence(CgVfxEffect<?> effect, CgVfxEmitterInstance emitter, Renderers rs, CgVfxParticlePool pool,
                          CgVfxRange range, int slot, int capacity) {
        CgVfxEmitter def = emitter.emitter();
        CgVfxClipAtlas.Section section = CgVfxClipAtlas.of(def.clip());
        float rate = CgVfxNodeClip.FRAMES_PER_SECOND * def.playRate(), reach = 0f;
        int base = range.laidBase(slot), word = range.laidWord(slot);
        for (int i = 0; i < rs.count; i++) {
            Renderer rd = rs.list[i];
            float size = CgVfxMaterials.size(effect, rd.r), carried = rd.carried * size;
            reach = Math.max(reach, carried);
            CgWorldRenderer.Draw draw = rd.mesh != null ? world.draw(rd.mesh, rd.material) : world.draw(system.sphereMesh(), rd.material);
            draw.instances(range.objects(), base, CgGpuCount.at(range.visible(), word, capacity))
                    .instanceScale(size)
                    .gpuCulled()
                    .pad(carried * def.largestSize())
                    .at(emitter.originX(), emitter.originY(), emitter.originZ())
                    .custom(2, section.base(rd.node), section.rowLength(), rate, section.stepMask(rd.node))
                    .custom(3, materials.row(effect, rd.r), effect.age, effect.seed, effect.presence());
            placeParticles(draw, rd.placed, effect).submit();
            CgVfxTrace.count(DRAWS_PARTICLE_GPU, 1);
        }
        if (reach > 0f) pool.cullScale(slot, reach);
    }

    /** A renderer's sort as Range orders it; none and spawn order keep the pool's order, which the renderers refuse first. */
    private static CgVfxRange.Sort rangeSort(CgVfxSpriteRendererProperties.Sort sort) {
        return switch (sort) {
            case VIEW_DISTANCE -> CgVfxRange.Sort.VIEW_DISTANCE;
            case OLDEST_FIRST -> CgVfxRange.Sort.OLDEST_FIRST;
            case NEWEST_FIRST -> CgVfxRange.Sort.NEWEST_FIRST;
            default -> CgVfxRange.Sort.VIEW_DEPTH;
        };
    }

    /** How a renderer draws its slot. */
    private enum Shape { MERGED, SINGLE, BEAM, RIBBON, MESHES, SEQUENCE, LIGHT }

    /**
     * An emitter instance's renderers, kept on its tenancy from its first drawn frame until its slot closes: Niagara's
     * {@code FNiagaraRenderer}s. Remade when its tier, the skipped parts, its look or {@code mergeParticles} change.
     */
    static final class Renderers {
        /** The reach of its bounds sphere in still air, and what each block a second of wind adds: the emitter's. */
        float still, perWind, clipReach;
        CgQuality quality;
        String[] skipped;
        CgVfxLook look;
        boolean merged;
        Renderer[] list = new Renderer[2];
        int count;

        Renderers(CgVfxEmitter def) {
            stale(def);
        }

        /** Takes {@code def}'s reach and builds again at its next draw: a tenancy reused, perhaps for another emitter. */
        void stale(CgVfxEmitter def) {
            clipReach = def.clip() != null ? def.largestSize() * CgVfxClipAtlas.of(def.clip()).reach(1f) : 0f;
            float calm = def.reach(0f);
            still = calm + def.largestSize() + clipReach;
            perWind = def.reach(1f) - calm;
            look = null;
        }

        Renderer next(Shape shape, CgVfxLook.Placed placed, CgVfxMaterials.Resolved r, CgMaterial material) {
            if (count == list.length) list = Arrays.copyOf(list, count * 2);
            Renderer rd = list[count];
            if (rd == null) list[count] = rd = new Renderer();
            count++;
            rd.shape = shape;
            rd.placed = placed;
            rd.r = r;
            rd.material = material;
            rd.proxy = null;
            rd.mesh = null;
            rd.meshRadius = rd.carried = 0f;
            rd.subs = rd.per = rd.node = 0;
            rd.beam = null;
            rd.beamFlags = rd.beamFrequency = 0;
            rd.unsorted = false;
            rd.sort = CgVfxRange.Sort.VIEW_DEPTH;
            rd.submesh = -1;
            return rd;
        }
    }

    /** One layer's renderer of an emitter instance: what its draws need that does not change while it lives. */
    private static final class Renderer {
        Shape shape;
        CgVfxLook.Placed placed;
        CgVfxMaterials.Resolved r;
        CgMaterial material;
        /** Its merged draw: {@code MERGED}, and {@code BEAM} unless merging is off. */
        Proxy proxy;
        /** {@code MESHES} and {@code SEQUENCE}: what it draws per particle (a part with none, the sphere), its radius. */
        CgMeshLods mesh;
        float meshRadius;
        /** A renderer sorting none: drawn in the order Range keeps, however it blends. */
        boolean unsorted;
        /** {@code MESHES}: the section it draws, -1 for the whole mesh. */
        int submesh = -1;
        /** How it sorts its particles when it blends them. */
        CgVfxRange.Sort sort = CgVfxRange.Sort.VIEW_DEPTH;
        /** {@code BEAM}: segments a sheet, vertices a particle. */
        int subs, per;
        /** {@code BEAM}: its renderer, its flags (1 noise on the target, 2 one up, 4 tiles by distance), its noise's frequency. */
        CgVfxBeamRendererProperties beam;
        int beamFlags, beamFrequency;
        /** {@code RIBBON}: its renderer and cross section. */
        CgVfxRibbonRendererProperties ribbon;
        CgVfxRibbonShape ribbonShape;
        /** {@code SEQUENCE}: its node, and how far the clip carries its part from its particle, a size's worth. */
        int node;
        float carried;
    }

    /**
     * One beam layer's draw of {@code slot}: a strip a visible particle, placed by {@code particle_factory.glsl}'s strip
     * factory from the pool's beam runs. What it reads:
     * <ul>
     *   <li>{@code CG_OBJECT_CUSTOM0}: the first record, the slot's capacity, the layer's size, sheets + 256 * segments.</li>
     *   <li>{@code CG_OBJECT_CUSTOM1}: the slot's first run, its runs, vec4s a run, noise points a run.</li>
     *   <li>{@code CG_OBJECT_CUSTOM2}: interpolation points, flags, tiles or blocks a tile, the noise's frequency.</li>
     * </ul>
     */
    private void beam(CgVfxEffect<?> effect, Renderer rd, float size, int row, CgVfxParticlePool pool, CgVfxRange range,
                      int slot, int capacity, int base, long word, double ox, double oy, double oz) {
        CgGraphBuffer runs = pool.beamRuns();
        if (runs == null) return;
        CgVfxBeamRendererProperties b = rd.beam;
        float sheets = b.sheets() + 256f * rd.subs;
        float tiles = b.textureTileDistance() > 0f ? b.textureTileDistance() : b.textureTile();
        if (rd.proxy != null) {
            int f = rd.proxy.add(this, capacity, ox, oy, oz, word);
            float[] c = rd.proxy.custom;
            c[f] = base;
            c[f + 1] = capacity;
            c[f + 2] = size;
            c[f + 3] = sheets;
            c[f + 4] = pool.beamFirst(slot);
            c[f + 5] = capacity;
            c[f + 6] = pool.beamRunVectors();
            c[f + 7] = pool.shape().beamPoints();
            c[f + 8] = b.interpolationPoints();
            c[f + 9] = rd.beamFlags;
            c[f + 10] = tiles;
            c[f + 11] = rd.beamFrequency;
            c[f + 12] = row;
            c[f + 13] = effect.age;
            c[f + 14] = effect.seed;
            c[f + 15] = effect.presence();
            return;
        }
        CgWorldRenderer.Draw draw = world.draw(CgMesh.vertices(sizeClass(capacity, 1) * rd.per, CgMeshTopology.TRIANGLES),
                        rd.material)
                .buffer(CgBindingPoints.PARTICLES, pool.drawn()).buffer(CgBindingPoints.PARTICLE_ORDER, range.order())
                .buffer(CgBindingPoints.PARTICLE_BEAMS, runs)
                .indirect(range.visible(), word, CgIndirect.VERTICES, rd.per)
                .gpuCulled()
                .at(ox, oy, oz)
                .custom(0, base, capacity, size, sheets)
                .custom(1, pool.beamFirst(slot), capacity, pool.beamRunVectors(), pool.shape().beamPoints())
                .custom(2, b.interpolationPoints(), rd.beamFlags, tiles, rd.beamFrequency)
                .custom(3, row, effect.age, effect.seed, effect.presence());
        placeParticles(draw, rd.placed, effect).submit();
    }

    /**
     * One ribbon layer's draw of {@code slot}: the pool's ribbons generated on the GPU this frame
     * ({@code CgVfxRibbonGeneration}), placed by {@code particle_factory.glsl}'s ribbon factory from the run at
     * {@code PARTICLE_BEAMS}, its vertices counted by the generation. Its customs are the factory's ({@code _cg_ribbon}).
     */
    private void ribbon(CgVfxEffect<?> effect, CgVfxEmitterInstance instance, Renderer rd, float size, int row,
                        CgVfxParticlePool pool, CgVfxRange range, int slot, double ox, double oy, double oz) {
        CgVfxRibbonGeneration ribbons = CgVfxRibbonGeneration.of(pool).ribbon(slot, rd.ribbon);
        CgVfxRibbonRendererProperties b = rd.ribbon;
        CgVfxRibbonShape shape = rd.ribbonShape;
        if (b.anim() != null && instance.animTrailSource() != null) {
            ribbons.fade(slot, instance.animTrailSource().fadeAlpha(), instance.animTrailSource().fadeWidth());
        }
        int mode = (b.trail() != null || b.anim() != null ? 1 : 0) | (shape.flipToView() ? 2 : 0) | b.uv0().distribution().ordinal() << 2
                | b.uv1().distribution().ordinal() << 4
                | (b.trail() != null && b.trail().renderAxis() != CgVfxRibbonRendererProperties.RenderAxis.CAMERA_UP ? 64 : 0)
                | (b.trail() != null && b.trail().tilingDistance() > 0f || b.anim() != null && b.anim().tilingDistance() > 0f ? 128 : 0)
                | (b.anim() != null ? 256 : 0);
        CgWorldRenderer.Draw draw = world.draw(CgMesh.vertices(sizeClass(ribbons.vertices(slot, b), 1), CgMeshTopology.TRIANGLES),
                        rd.material)
                .buffer(CgBindingPoints.PARTICLES, pool.drawn()).buffer(CgBindingPoints.PARTICLE_ORDER, range.order())
                .buffer(CgBindingPoints.PARTICLE_BEAMS, ribbons.run())
                .indirect(ribbons.args(), ribbons.argsWord(slot), CgIndirect.VERTICES, 1)
                .gpuCulled()
                .at(ox, oy, oz)
                .custom(0, range.laidBase(slot) - pool.listBase(slot), ribbons.slotRecord(slot), size, -1f - mode)
                .custom(1, shape.code(), shape.segments(), shape.planes(), shape.edges())
                .custom(2, b.uv0().scaleV(), b.uv0().offsetV(), b.uv1().scaleV(), b.uv1().offsetV())
                .custom(3, row, effect.age, effect.seed, effect.presence());
        placeParticles(draw, rd.placed, effect).submit();
    }

    /**
     * Whether {@code material}'s Forward pass blends so that order matters: over, not added. An additive blend commutes,
     * and so does none; a material still compiling answers no. A look drawn through OIT must answer no too: it needs
     * neither the sort nor ordered instances.
     */
    private static boolean blendsInOrder(CgMaterial material) {
        CgBlendState blend = material.getPassRenderState(CgRenderPassVariant.FORWARD).getBlend();
        return blend != null && blend.enabled() && blend.dstRgb() != CgGL.GL_ONE;
    }

    /**
     * The shared mesh a GPU slot of {@code capacity} draws a {@code QUADS} or {@code ARCS} layer on, made on its first
     * ask; null for {@code MESHES} and {@code SEQUENCE}, which draw meshes as instances.
     */
    static CgMesh slotMesh(CgVfxDrawLayout renderer, int capacity) {
        return switch (renderer) {
            case QUADS -> CgMesh.quads(sizeClass(capacity, CgVfxQuads.COUNT));
            case ARCS -> CgMesh.vertices(sizeClass(capacity, CgVfxRibbons.COUNT) * CgVfxRibbons.VERTICES,
                    CgMeshTopology.TRIANGLES);
            case MESHES, SEQUENCE -> null;
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
            if (skips(effect, placed)) continue;
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
            if (skips(effect, placed)) continue;
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
            if (skips(effect, placed)) continue;
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
            if (skips(effect, placed)) continue;
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
