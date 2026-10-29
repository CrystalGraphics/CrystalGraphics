package com.crystalgraphics.sequence.camera;

import java.util.Map;
import java.util.function.Function;

import com.crystalgraphics.camera.CgCameraSystem;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.spawn.CgSequenceSpawned;
import com.crystalgraphics.world.CgAttachment;

/**
 * A sequencer's cameras, on one viewer's camera system: the {@code camera} spawner (a cine camera, or a rig camera
 * where it names a {@code rig}), the {@code camera_animation} kind and the {@code camera_cut} kind.
 *
 * <pre>{@code
 * // a client, once its sequencer and camera system exist
 * CgSequenceCameras.install(sequencer, cameras, id -> assets.loadedSequence(id));
 * }</pre>
 *
 * <ul>
 *   <li>Install once a sequencer: each kind registers once.</li>
 *   <li>{@code animations} answers a camera animation's sequence by its id, null while it is not loaded.</li>
 *   <li>A client's alone: a server plays no camera.</li>
 * </ul>
 */
public final class CgSequenceCameras {

    private CgSequenceCameras() {
    }

    public static void install(CgSequencer sequencer, CgCameraSystem cameras, Function<String, CgSequenceDefinition> animations) {
        sequencer.spawner("camera", CgSequenceCameras::camera);
        sequencer.register(new CgCameraAnimationEvaluator(sequencer, cameras, animations));
        sequencer.register(new CgCameraCutEvaluator(cameras));
    }

    /** The {@code camera} spawner: a rig camera where its {@code camera} names a {@code rig}, else a cine camera. */
    public static CgSequenceSpawned camera(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        if (spawnable.tree().get("camera") instanceof Map<?, ?> camera && camera.get("rig") != null) {
            return CgRigCamera.spawn(player, spawnable, at);
        }
        return CgCineCamera.spawn(player, spawnable, at);
    }
}
