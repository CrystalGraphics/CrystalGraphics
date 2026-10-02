package com.crystalgraphics.vfx;

import com.crystalgraphics.api.material.CgMaterial;
import com.crystalgraphics.api.vertex.CgVertexFormat;
import com.crystalgraphics.gl.mesh.CgMesh;
import com.crystalgraphics.gl.mesh.CgMeshBuilder;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.path.CgVfxPathTexture;
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
 *   <li>{@link #submit} is render thread, and must run every frame an effect draws: the path texture holds only the
 *       last upload.</li>
 *   <li>Every mesh the package draws is made here, so a change to how meshes are made is one edit.</li>
 * </ul>
 */
public final class CgVfxSystem {

    /** Seconds of one simulation step. */
    public static final float TICK = 1f / 120f;
    private static final int MAX_TICKS = 12;
    private static final String PATH_SAMPLER = "_FxPath";

    private final List<CgVfxEffect> effects = new ArrayList<>();
    private final CgVfxPathTexture paths = new CgVfxPathTexture();
    private final CgVfxTube tube = new CgVfxTube();
    private final CgVfxFrame frame = new CgVfxFrame(this);
    private final IdentityHashMap<CgVfxLayer, CgMaterial> materials = new IdentityHashMap<>();
    private final List<CgMaterial> unbound = new ArrayList<>();
    private CgTexture2D boundTexture;
    private CgMesh tubeMesh, sphereMesh;
    private double clock = Double.NaN;
    private float owed;

    public <E extends CgVfxEffect> E play(E effect) {
        effects.add(effect);
        return effect;
    }

    /** Advances every effect to {@code seconds} on the clock the caller keeps. */
    public void update(double seconds) {
        if (Double.isNaN(clock)) {
            clock = seconds;
            return;
        }
        owed = Math.max(0f, owed + (float) (seconds - clock));
        clock = seconds;
        int ticks = 0;
        while (owed >= TICK && ticks < MAX_TICKS) {
            for (int i = 0; i < effects.size(); i++) {
                CgVfxEffect effect = effects.get(i);
                if (effect.state() != CgVfxEffect.State.DEAD) effect.step(TICK);
            }
            owed -= TICK;
            ticks++;
        }
        if (ticks == MAX_TICKS) owed = Math.min(owed, TICK);
        for (int i = effects.size() - 1; i >= 0; i--) {
            if (effects.get(i).state() == CgVfxEffect.State.DEAD) effects.remove(i);
        }
    }

    /** Draws every playing effect into {@code world}, interpolated between the last two ticks. Render thread. */
    public void submit(CgWorldRenderer world) {
        if (effects.isEmpty()) return;
        if (tubeMesh == null) {
            tubeMesh = CgMesh.upload(CgVfxTube.meshData());
            sphereMesh = CgMesh.upload(CgMeshBuilder.uvSphere(CgVertexFormat.SPATIAL, 48, 96, 1f));
        }
        frame.begin(world, Math.min(owed / TICK, 1f));
        paths.begin();
        for (int i = 0; i < effects.size(); i++) effects.get(i).submit(frame);
        paths.upload();
        bindPaths();
    }

    public List<CgVfxEffect> effects() {
        return effects;
    }

    /** Frees the meshes and the path texture; materials belong to the material registry. */
    public void delete() {
        effects.clear();
        paths.delete();
        if (tubeMesh != null) tubeMesh.delete();
        if (sphereMesh != null) sphereMesh.delete();
        tubeMesh = null;
        sphereMesh = null;
        boundTexture = null;
        unbound.addAll(materials.values());
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
