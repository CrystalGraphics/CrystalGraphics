package com.crystalgraphics.sequence.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.camera.CgCameraContext;
import com.crystalgraphics.camera.CgCameraPose;
import com.crystalgraphics.camera.CgCameraSystem;
import com.crystalgraphics.sequence.CgSequenceEvaluator;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;

/**
 * The {@code camera_cut} kind: for its section's span, the viewer looks through the camera it names. Mirrors
 * {@code UMovieSceneCameraCutTrackInstance::OnAnimate}: the active section's camera is the view, and once no section is
 * active the view the play found comes back. A rig camera ({@link CgRigCamera}) cuts in and out by its rig's own
 * transitions; a cine camera ({@link CgCineCamera}) is a context with no rig, posed by its keys, and pops.
 *
 * <pre>{@code
 * # a track
 * - id: cut
 *   kind: camera_cut
 *   sections:
 *     - { id: c, range: [6600, 138600], camera: { camera: skill_camera_id } }
 * }</pre>
 *
 * <ul>
 *   <li>Only for a viewer its play involves ({@link CgSequencePlayer#involved()}, then the host's own
 *       {@link CgCameraSystem#involves}).</li>
 *   <li>A section past its play's end holds the camera until the play stops.</li>
 *   <li>Not played yet, each failing at bind by name: the track's {@code can_blend}; a section's {@code ease},
 *       {@code lock_previous} and {@code panels}; a subsequence's camera ({@code camera: { sequence, camera }}).</li>
 *   <li>Not on a server: it draws nothing a server needs.</li>
 * </ul>
 */
public final class CgCameraCutEvaluator implements CgSequenceEvaluator {

    private final CgCameraSystem cameras;
    private final List<Cut> playing = new ArrayList<>();

    public CgCameraCutEvaluator(CgCameraSystem cameras) {
        this.cameras = Objects.requireNonNull(cameras, "cameras");
    }

    @Override
    public String kind() {
        return "camera_cut";
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section) {
        if (section == null) return;
        String where = player.definition().id() + ": tracks." + track.id() + ".sections." + section.id();
        if (section.ease() != null && (section.ease().in() > 0 || section.ease().out() > 0)) throw notYet(where, "ease");
        if (Boolean.TRUE.equals(track.fields().get("can_blend"))) {
            throw new CgAssetException(player.definition().id() + ": tracks." + track.id() + ".can_blend: a camera cut track's blend is not played yet");
        }
        if (Boolean.TRUE.equals(section.fields().get("lock_previous"))) throw notYet(where, "lock_previous");
        if (section.fields().get("panels") != null) throw notYet(where, "panels");
        Object camera = section.fields().get("camera");
        String id;
        if (camera instanceof String s) {
            id = s;
        } else if (camera instanceof Map<?, ?> m && m.get("camera") instanceof String s) {
            if (m.get("sequence") != null) throw notYet(where + ".camera", "sequence");
            id = s;
        } else {
            throw new CgAssetException(where + ".camera: names the camera spawnable it cuts to");
        }
        List<CgSequenceDefinition.Spawnable> spawnables = player.definition().spawnables();
        int index = -1;
        for (int i = 0; i < spawnables.size(); i++) if (spawnables.get(i).id().equals(id)) index = i;
        if (index < 0 || !spawnables.get(index).kind().equals("camera")) {
            throw new CgAssetException(where + ".camera: " + id + " is no camera spawnable of " + player.definition().id());
        }
        player.data(track, section, new Cut(player, section, index));
    }

    private static CgAssetException notYet(String where, String field) {
        return new CgAssetException(where + "." + field + ": a camera cut's " + field + " is not played yet");
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section, float tick) {
        if (section != null && player.data(track, section) instanceof Cut c) c.start();
    }

    /** A cut whose section its play has left ends here; a cine camera's still playing takes its keyed pose. */
    @Override
    public void postEvaluation() {
        for (int i = playing.size() - 1; i >= 0; i--) {
            Cut c = playing.get(i);
            CgSequenceDefinition d = c.owner.definition();
            double s = c.owner.seconds(), from = d.seconds(c.section.from() - d.start());
            boolean open = c.section.to() == CgSequenceSection.OPEN_END;
            if (s < from || (!open && s >= d.seconds(c.section.to() - d.start()))) c.end(i);
            else c.place();
        }
    }

    @Override
    public void stopped(CgSequencePlayer player) {
        for (int i = playing.size() - 1; i >= 0; i--) if (playing.get(i).owner == player) playing.get(i).end(i);
    }

    /** One section's cut: the context it pushed while it plays. */
    private final class Cut {
        final CgSequencePlayer owner;
        final CgSequenceSection section;
        final int spawnable;
        @Nullable
        CgCameraContext context;
        @Nullable
        CgCineCamera cine;
        final CgCameraPose keyed = new CgCameraPose();

        Cut(CgSequencePlayer owner, CgSequenceSection section, int spawnable) {
            this.owner = owner;
            this.section = section;
            this.spawnable = spawnable;
        }

        void start() {
            if (context != null || !owner.involved() || !cameras.involves(owner)) return;
            if (owner.spawned(spawnable) instanceof CgRigCamera rig) {
                context = rig.context();
            } else if (owner.spawned(spawnable) instanceof CgCineCamera cam) {
                cine = cam;
                context = new CgCameraContext(owner.definition().id()).owner(owner).flags(owner.playbackSettings().flags());
                place();
            } else {
                return;
            }
            cameras.push(context);
            playing.add(this);
        }

        void place() {
            if (cine == null || context == null) return;
            context.initial().set(cine.pose(keyed));
        }

        void end(int index) {
            playing.remove(index);
            if (context != null) cameras.remove(context);
            context = null;
            cine = null;
        }
    }
}
