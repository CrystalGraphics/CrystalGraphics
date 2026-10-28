package com.crystalgraphics.sequence.spawn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.joml.Matrix4fc;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.api.animation.CgPose;
import com.crystalgraphics.api.mesh.CgMeshLoader;
import com.crystalgraphics.api.mesh.CgSkeletalMesh;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.registry.CgAssetEntry;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.world.CgAttachment;

/**
 * A sequence's {@code puppet} spawnable: Unreal's skeletal mesh actor. Its model's skeleton is posed by its
 * {@code animation} on the play's time, and each bone answers {@code at: { spawnable, node }}, so a light or a system
 * rides it. The sequencer's default {@code puppet} spawner finds clip {@code ns:path/name} in
 * {@code <the model's namespace>:models/path/name.glb}, else in the model's own file; a host with clips of its own
 * installs one that finds them.
 *
 * <pre>{@code
 * spawnables:
 *   - { id: dmy, at: { role: caster, socket: root },
 *       puppet: { model: mymod:models/dummy.glb, animation: mymod:anim/cut_light } }
 *   - { id: light, at: { spawnable: dmy, node: EffPoint_01 }, light: { type: directional, intensity: 10, priority: 1 } }
 * }</pre>
 *
 * <pre>{@code
 * CgSequencePuppet.install(sequencer, id -> myClips.get(id.substring(id.lastIndexOf('/') + 1)));   // a host's clips
 * }</pre>
 *
 * <ul>
 *   <li>A model or clip that does not resolve fails as the play starts, naming the spawnable and what is missing.</li>
 *   <li>It draws nothing yet: a puppet naming {@code meshes} to draw fails as the play starts.</li>
 *   <li>Its clip holds its last pose past its end.</li>
 * </ul>
 */
public final class CgSequencePuppet implements CgSequenceSpawner {

    private final Function<String, CgAnimSequence> clips;

    private CgSequencePuppet(@Nullable Function<String, CgAnimSequence> clips) {
        this.clips = clips;
    }

    /** The default: the clip's file beside the model's kit's models, else the model's own file. */
    public static final CgSequencePuppet DEFAULT = new CgSequencePuppet(null);

    /** Makes {@code sequencer}'s puppets, their clips found by {@code clips} (null where it has none). */
    public static CgSequencePuppet install(CgSequencer sequencer, Function<String, CgAnimSequence> clips) {
        CgSequencePuppet p = new CgSequencePuppet(clips);
        sequencer.spawner("puppet", p);
        return p;
    }

    @Override
    public void check(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable spawnable) {
        resolve(definition, spawnable);
    }

    @Override
    public CgSequenceSpawned spawn(CgSequencePlayer player, CgSequenceDefinition.Spawnable spawnable, CgAttachment at) {
        Resolved r = resolve(player.definition(), spawnable);
        return new Spawned(player, r.mesh, r.clip, at);
    }

    private record Resolved(CgSkeletalMesh mesh, CgAnimSequence clip) {
    }

    private Resolved resolve(CgSequenceDefinition definition, CgSequenceDefinition.Spawnable spawnable) {
        String path = definition.id() + ": spawnables." + spawnable.id() + ".puppet";
        Map<?, ?> tree = spawnable.tree().get("puppet") instanceof Map<?, ?> m ? m : Map.of();
        if (tree.get("meshes") instanceof List<?> l && !l.isEmpty()) throw new CgAssetException(path + ".meshes: a puppet's meshes are not drawn yet");
        if (!(tree.get("model") instanceof String model)) throw new CgAssetException(path + ".model: a puppet names its model");
        if (!(tree.get("animation") instanceof String animation)) throw new CgAssetException(path + ".animation: a puppet names its clip");
        CgSkeletalMesh mesh;
        try {
            mesh = CgMeshLoader.skeletal(file(model));
        } catch (RuntimeException e) {
            throw new CgAssetException(path + ".model: " + model + " does not load: " + e.getMessage());
        }
        CgAnimSequence clip = clips != null ? clips.apply(animation) : null;
        String last = animation.substring(animation.lastIndexOf('/') + 1);
        if (clip == null && model.indexOf(':') > 0) {
            String beside = model.substring(0, model.indexOf(':')) + ":models/" + animation.substring(animation.indexOf(':') + 1) + ".glb";
            if (CgVfxAssets.instance().registry().binary(beside) != null) clip = named(CgMeshLoader.sequences(file(beside)), last);
        }
        if (clip == null) clip = named(mesh.sequences(), last);
        if (clip == null) throw new CgAssetException(path + ".animation: no clip " + animation + " for " + model);
        return new Resolved(mesh, clip);
    }

    @Nullable
    private static CgAnimSequence named(List<CgAnimSequence> clips, String last) {
        CgAnimSequence found = null;
        for (CgAnimSequence c : clips) if (c.name().contains(last)) found = c;
        return found == null && clips.size() == 1 ? clips.get(0) : found;
    }

    /** A model id's file through the asset registry, else the id as a resource path. */
    private static String file(String model) {
        CgAssetEntry e = CgVfxAssets.instance().registry().binary(model);
        return e != null && e.file() != null ? e.file().toString() : model;
    }

    /** One puppet: its pose each frame, and its bones as attachments under where it stands. */
    private static final class Spawned implements CgSequenceSpawned {
        private final CgSequencePlayer player;
        private final CgSkeletalMesh mesh;
        private final CgAnimSequence clip;
        private final CgAttachment at;
        private final CgPose pose;
        private final Map<String, CgAttachment> bones = new HashMap<>();
        private final double[] root = new double[3];
        private final Quaternionf rootTurn = new Quaternionf(), boneTurn = new Quaternionf();
        private final Vector3f offset = new Vector3f();

        Spawned(CgSequencePlayer player, CgSkeletalMesh mesh, CgAnimSequence clip, CgAttachment at) {
            this.player = player;
            this.mesh = mesh;
            this.clip = clip;
            this.at = at;
            this.pose = new CgPose(mesh.skeleton());
            update();
        }

        @Override
        public void update() {
            pose.rest();
            clip.sample(pose, (float) Math.min(player.seconds(), clip.duration()));
            pose.resolve();
        }

        @Nullable
        @Override
        public CgAttachment attachment(String name) {
            CgAttachment a = bones.get(name);
            if (a != null) return a;
            int bone = mesh.skeleton().indexOfIgnoringCase(name);
            if (bone < 0) return null;
            a = (point, turn) -> {
                if (!at.place(root, rootTurn)) return false;
                Matrix4fc m = pose.component(bone);
                m.getTranslation(offset);
                rootTurn.transform(offset);
                point[0] = root[0] + offset.x;
                point[1] = root[1] + offset.y;
                point[2] = root[2] + offset.z;
                if (turn != null) turn.set(rootTurn).mul(m.getNormalizedRotation(boneTurn));
                return true;
            };
            bones.put(name, a);
            return a;
        }

        @Override
        public void destroy(boolean graceful) {
        }

        @Override
        public String toString() {
            return "puppet playing " + clip.name();
        }
    }
}
