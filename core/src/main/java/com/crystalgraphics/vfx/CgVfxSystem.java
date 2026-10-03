package com.crystalgraphics.vfx;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.mesh.CgMesh;
import com.crystalgraphics.api.mesh.CgMeshShapes;
import com.crystalgraphics.gl.buffer.shader.CgParticleBuffer;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.render.stage.CgHostEnvironment;
import com.crystalgraphics.render.stage.CgRenderStage;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.settings.CgGraphicsSettings;
import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.particle.CgVfxAir;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxParticleSet;
import com.crystalgraphics.vfx.path.CgVfxPathTexture;
import com.crystalgraphics.vfx.render.CgVfxQuads;
import com.crystalgraphics.vfx.render.CgVfxRibbons;
import com.crystalgraphics.vfx.render.CgVfxTube;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Plays {@link CgVfxEffect}s: simulates them on a fixed tick, draws them into a world each frame, and owns what they
 * share on the GPU (the path texture, the meshes, a material per {@link CgVfxLayer}).
 *
 * <pre>{@code
 * CgVfxSystem vfx = new CgVfxSystem();
 * CgEnergyWave wave = vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
 * // every frame, before the world stages record (a CgWorldRenderer.onFrame listener, or ahead of a harness's fire):
 * vfx.update(seconds);
 * vfx.submit(CgWorldRenderer.get());
 * // once, when the context goes:
 * vfx.delete();
 * }</pre>
 *
 * <ul>
 *   <li>{@link #update} runs {@link #TICK}-second steps, as many as the clock owes and at most {@value #MAX_TICKS} a
 *       call, so a hitch slows effects down rather than stalling the frame. It touches no GPU state.</li>
 *   <li>{@link #submit} is render thread, and must run every frame an effect draws: the path texture and the particle
 *       buffer hold only the last upload.</li>
 *   <li>Every mesh the package draws is made here, so a change to how meshes are made is one edit.</li>
 *   <li>{@link #air} is the wind every effect's particles move through; set it once, or change it while playing.</li>
 *   <li>It applies the player's settings ({@link CgGraphicsSettings}) to every effect: particle density and the
 *       quality tier, read once an update. Its clock stops while the game is paused or {@code /tick freeze} holds the
 *       world, and runs at the world's tick rate.</li>
 * </ul>
 */
public final class CgVfxSystem {

    /** Seconds of one simulation step. */
    public static final float TICK = 1f / 120f;
    private static final int MAX_TICKS = 12;
    private static final String PATH_SAMPLER = "_FxPath";

    private final List<CgVfxEffect> effects = new ArrayList<>();
    private final List<CgVfxMomentListener> momentListeners = new ArrayList<>();
    private final CgVfxPathTexture paths = new CgVfxPathTexture();
    private final CgVfxTube tube = new CgVfxTube();
    private final CgVfxFrame frame = new CgVfxFrame(this);
    private final IdentityHashMap<CgVfxLayer, CgMaterial> materials = new IdentityHashMap<>();
    private final List<CgMaterial> unbound = new ArrayList<>();
    /** Materials compiling ahead of their first draw, so a layer that appears late does not stall its frame. */
    private final List<CgMaterial> warming = new ArrayList<>();
    private CgTexture2D boundTexture;
    // The tube is this system's own; the ribbons, sphere and quads are shared.
    private CgMesh tubeMesh, ribbonMesh, sphereMesh, quadMesh;
    /** The emitters drawn this frame through the particle buffer, in the order their records go into it. */
    private final List<CgVfxEmitterInstance> particleEmitters = new ArrayList<>();
    private int particleRecords;
    private final CgVfxAir air = new CgVfxAir();
    private double clock = Double.NaN;
    private float owed, simulated;
    private float density = 1f;
    private CgQuality quality = CgQuality.HIGH;

    public <E extends CgVfxEffect> E play(E effect) {
        effect.system = this;
        effects.add(effect);
        return effect;
    }

    /** Hears every effect's named moments from now on: the visual debugging hook ({@link CgVfxMomentListener}). */
    public void onMoment(CgVfxMomentListener listener) {
        momentListeners.add(listener);
    }

    boolean hasMomentListeners() {
        return !momentListeners.isEmpty();
    }

    void moment(CgVfxEffect effect, String name, double x, double y, double z, float radius) {
        for (int i = 0; i < momentListeners.size(); i++) momentListeners.get(i).moment(effect, name, x, y, z, radius);
    }

    /** The air the particles of every effect move through. */
    public CgVfxAir air() {
        return air;
    }

    /**
     * Advances every effect to {@code seconds} on the clock the caller keeps, at the world's pace: not at all while it is
     * paused or frozen, slower or faster under {@code /tick rate}.
     */
    public void update(double seconds) {
        CgHostEnvironment world = CgRenderStage.WORLD_OPAQUE.host().environment();
        readSettings(world);
        if (Double.isNaN(clock)) {
            clock = seconds;
            return;
        }
        owed = Math.max(0f, owed + (float) (seconds - clock) * pace(world));
        clock = seconds;
        int ticks = 0;
        try (CgTrace.Zone ignored = CgTrace.zone(CgChannels.WORLD, "vfx.sim")) {
            while (owed >= TICK && ticks < MAX_TICKS) {
                air.tick(simulated);
                for (int i = 0; i < effects.size(); i++) {
                    CgVfxEffect effect = effects.get(i);
                    if (effect.state() != CgVfxEffect.State.DEAD) effect.step(TICK);
                }
                simulated += TICK;
                owed -= TICK;
                ticks++;
            }
        }
        if (ticks == MAX_TICKS) owed = Math.min(owed, TICK);
        for (int i = effects.size() - 1; i >= 0; i--) {
            if (effects.get(i).state() == CgVfxEffect.State.DEAD) effects.remove(i);
        }
    }

    /** The share of {@code emitter}'s particles to spawn: the player's density, halved again at Low for an optional one. */
    public float spawnShare(CgVfxEmitter emitter) {
        return emitter.optional() && quality == CgQuality.LOW ? density * 0.5f : density;
    }

    /** The quality tier this update draws at. */
    public CgQuality quality() {
        return quality;
    }

    private void readSettings(CgHostEnvironment world) {
        density = CgGraphicsSettings.DENSITY.get();
        if (CgGraphicsSettings.FOLLOW_MINECRAFT_PARTICLES.get()) density *= world.particleShare();
        quality = CgGraphicsSettings.QUALITY.get();
    }

    /** Simulated seconds per real second for {@code world}: 0 while paused or frozen, the tick rate over 20 otherwise. */
    public static float pace(CgHostEnvironment world) {
        if (world.paused() || world.frozen()) return 0f;
        float rate = world.tickRate();
        return rate > 0f ? rate / 20f : 1f;
    }

    /** Draws every playing effect into {@code world}, interpolated between the last two ticks. Render thread. */
    public void submit(CgWorldRenderer world) {
        if (effects.isEmpty()) return;
        if (tubeMesh == null) {
            tubeMesh = CgVfxTube.mesh();
            ribbonMesh = CgVfxRibbons.mesh();
            sphereMesh = CgMeshShapes.sphere(48, 96);
            quadMesh = CgVfxQuads.mesh();
        }
        warm();
        frame.begin(world, Math.min(owed / TICK, 1f));
        paths.begin();
        for (int i = 0; i < effects.size(); i++) effects.get(i).submit(frame);
        paths.upload();
        bindPaths();
        writeParticles(frame.alpha());
    }

    /**
     * Where {@code emitter}'s records start in this frame's particle buffer, adding them on its first draw this frame.
     * Its draws read {@code [base, base + count)}.
     */
    int particleBase(CgVfxEmitterInstance emitter) {
        int base = 0;
        for (int i = 0; i < particleEmitters.size(); i++) {
            if (particleEmitters.get(i) == emitter) return base;
            base += particleEmitters.get(i).particles().count();
        }
        particleEmitters.add(emitter);
        particleRecords += emitter.particles().count();
        return base;
    }

    /** Every particle drawn this frame into the buffer, once, in the order their bases were handed out. */
    private void writeParticles(float alpha) {
        if (particleRecords > 0) {
            float ahead = alpha * TICK;
            CgParticleBuffer.begin(particleRecords);
            for (int k = 0; k < particleEmitters.size(); k++) {
                CgVfxEmitter def = particleEmitters.get(k).emitter();
                CgVfxParticleSet p = particleEmitters.get(k).particles();
                for (int i = 0; i < p.count(); i++) {
                    float t = p.progress(i);
                    CgParticleBuffer.put(p.x(i, alpha), p.y(i, alpha), p.z(i, alpha), p.size[i] * def.sizeAt(t),
                            p.vx[i], p.vy[i], p.vz[i], t,
                            p.seed[i], p.spin[i] + p.spinRate[i] * ahead, p.heat[i], def.opacityAt(t));
                }
            }
            CgParticleBuffer.end();
        }
        particleEmitters.clear();
        particleRecords = 0;
    }

    /**
     * Starts compiling every material a newly playing effect's look can draw, and polls what is still compiling: an
     * effect's last layers (a blast, its cloud) appear seconds after it starts, and compiling them then stalls that
     * frame.
     */
    private void warm() {
        for (int i = 0; i < effects.size(); i++) {
            CgVfxEffect effect = effects.get(i);
            if (effect.warmed) continue;
            effect.warmed = true;
            List<CgVfxLayer> layers = effect.look().layers();
            for (int k = 0; k < layers.size(); k++) {
                CgMaterial material = material(layers.get(k));
                if (!warming.contains(material)) warming.add(material);
            }
        }
        for (int i = warming.size() - 1; i >= 0; i--) {
            if (warming.get(i).prepare()) warming.remove(i);
        }
    }

    public List<CgVfxEffect> effects() {
        return effects;
    }

    /** Releases its meshes and frees the path texture; materials belong to the material registry. */
    public void delete() {
        effects.clear();
        paths.delete();
        if (tubeMesh != null) tubeMesh.release();
        tubeMesh = null;
        ribbonMesh = null;
        sphereMesh = null;
        quadMesh = null;
        boundTexture = null;
        unbound.addAll(materials.values());
        warming.clear();
    }

    CgVfxPathTexture paths() {
        return paths;
    }

    CgVfxTube tube() {
        return tube;
    }

    CgMesh tubeMesh() {
        return tubeMesh;
    }

    CgMesh sphereMesh() {
        return sphereMesh;
    }

    CgMesh ribbonMesh() {
        return ribbonMesh;
    }

    CgMesh quadMesh() {
        return quadMesh;
    }

    CgMaterial material(CgVfxLayer layer) {
        CgMaterial material = materials.get(layer);
        if (material == null) {
            material = CgMaterial.newInstance(layer.shader());
            if (layer.properties() != null) material.applyProperties(layer.properties());
            materials.put(layer, material);
            unbound.add(material);
        }
        return material;
    }

    /** Points every material at the path texture: once each, and again if the texture was made anew. */
    private void bindPaths() {
        CgTexture2D texture = paths.texture();
        if (texture == null) return;
        if (texture != boundTexture) {
            boundTexture = texture;
            unbound.clear();
            unbound.addAll(materials.values());
        }
        for (int i = 0; i < unbound.size(); i++) {
            unbound.get(i).applyProperties(b -> b.sampler(PATH_SAMPLER, 0, texture));
        }
        unbound.clear();
    }
}
