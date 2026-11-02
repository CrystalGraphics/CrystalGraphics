package com.crystalgraphics.vfx.system;

import java.util.function.Function;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.camera.CgCameraSystem;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.render.post.volume.CgColourRegionSpawner;
import com.crystalgraphics.render.post.volume.CgPostVolumeSpawner;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.camera.CgSequenceCameras;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.camera.CgCameraShakeTrackEvaluator;
import com.crystalgraphics.vfx.prefab.CgVfxPrefabSpawner;

/**
 * A world's sequencer with the engine's kinds, so a sequence plays the same wherever it plays: in Minecraft, the
 * harness or a tool. Its {@code system} and {@code prefab} spawnables play on a VFX system, its {@code post} and
 * {@code colour_region} spawnables on the post stack, its {@code shake} tracks on the camera, and with a camera system
 * its cameras, cuts and camera animations.
 *
 * <pre>{@code
 * CgSequencer sequencer = CgVfxSequencer.install(CgWorld.client(), CgVfx.world());   // the engine's, as a client starts
 * sequencer.play(move, Map.of("caster", goku));
 * }</pre>
 *
 * <pre>{@code
 * // a scene that owns the view: its camera system, and where the camera animations its cuts name come from
 * CgSequencer sequencer = CgVfxSequencer.install(world, vfx, cameras, id -> kit.cameraSequence(id));
 * }</pre>
 *
 * <ul>
 *   <li>One sequencer a world: installing again returns the world's, its spawners made again on {@code vfx}; its
 *       camera kinds stay those of the first install that gave a camera system.</li>
 *   <li>Without a camera system a {@code camera} spawnable fails as its play starts, naming it, and a
 *       {@code camera_cut} or {@code camera_animation} track plays nothing.</li>
 *   <li>A game's own kinds (a notify, a spawnable of its own) are registered on what it returns.</li>
 * </ul>
 */
public final class CgVfxSequencer {

    private CgVfxSequencer() {
    }

    /** {@code world}'s sequencer with the engine's kinds on {@code vfx}, and no cameras. */
    public static CgSequencer install(CgWorld world, CgVfxSystem vfx) {
        return install(world, vfx, null, null);
    }

    /**
     * {@code world}'s sequencer with the engine's kinds on {@code vfx}, and with {@code cameras} its camera kinds,
     * their animations by id from {@code cameraSequences}; null reads the loaded {@code sequence} assets.
     */
    public static CgSequencer install(CgWorld world, CgVfxSystem vfx, @Nullable CgCameraSystem cameras,
                                      @Nullable Function<String, CgSequenceDefinition> cameraSequences) {
        CgSequencer sequencer;
        synchronized (world) {
            sequencer = world.subsystem(CgSequencer.class);
            if (sequencer == null) sequencer = world.add(new CgSequencer());
        }
        CgVfxSystemSpawner systems = new CgVfxSystemSpawner(vfx);
        sequencer.spawner("system", systems);
        sequencer.spawner("prefab", new CgVfxPrefabSpawner(vfx, systems));
        CgPostVolumeSpawner.install(sequencer, cameras);
        CgColourRegionSpawner.install(sequencer);
        CgCameraShakeTrackEvaluator.install(sequencer);
        if (cameras != null && !sequencer.evaluates("camera_cut")) {
            CgSequenceCameras.install(sequencer, cameras, cameraSequences != null ? cameraSequences
                    : id -> CgVfxAssets.<CgSequenceDefinition>ref(CgSequenceKinds.SEQUENCE, id).loaded());
        }
        return sequencer;
    }
}
