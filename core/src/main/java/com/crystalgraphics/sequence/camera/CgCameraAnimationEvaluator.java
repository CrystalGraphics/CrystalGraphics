package com.crystalgraphics.sequence.camera;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

import javax.annotation.Nullable;

import org.joml.Quaternionf;
import org.joml.Vector3d;

import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.camera.CgCameraContext;
import com.crystalgraphics.camera.CgCameraModifier;
import com.crystalgraphics.camera.CgCameraPose;
import com.crystalgraphics.camera.CgCameraSystem;
import com.crystalgraphics.sequence.CgSequenceEvaluator;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;
import com.crystalgraphics.world.CgAttachment;

/**
 * The {@code camera_animation} kind: a camera sequence's keys played on the viewer's camera as an additive modifier,
 * for its section's span. Mirrors Unreal's camera animation in {@code CameraLocal} space
 * ({@code UCameraAnimInst::ApplyToView}, 4.21): the camera's moves relative to its own first key, turned into the
 * viewer's camera and added there, and its FOV's change from its first key added to the viewer's.
 *
 * <pre>{@code
 * // a client, with the other camera kinds: CgSequenceCameras installs it
 * CgSequenceCameras.install(sequencer, cameras, id -> assets.loadedSequence(id));
 * }</pre>
 *
 * <pre>{@code
 * # a section of a track
 * - id: cut
 *   kind: camera_animation
 *   sections:
 *     - { id: s1, range: [0, 259939], animation: mymod:cameras/cut_in }
 * }</pre>
 *
 * <ul>
 *   <li>The camera sequence must be loaded when its section starts, or the play fails naming it.</li>
 *   <li>The camera sequence plays beside its section, its time the section's; it ends with the section.</li>
 *   <li>Unbound, it plays on the viewer's camera, whatever rig is running. Bound to a role
 *       ({@code binding: { role: caster, node: Mesh }}), it is a camera of its own at that node, its keys in the
 *       node's space, cutting in and out with its section.</li>
 *   <li>Only for a viewer its play involves ({@link CgSequencePlayer#involved()}, then the host's own
 *       {@link CgCameraSystem#involves}); its play's camera is then in the view
 *       at weight 1 ({@link CgCameraSystem#weightOf}).</li>
 *   <li>Not on a server: it draws nothing a server needs.</li>
 * </ul>
 */
public final class CgCameraAnimationEvaluator implements CgSequenceEvaluator {

    /** The FOV range {@code ApplyToView} clamps to. */
    static final float FOV_MIN = 5f, FOV_MAX = 170f;

    private final CgSequencer sequencer;
    private final CgCameraSystem cameras;
    private final Function<String, CgSequenceDefinition> animations;
    private final List<Animation> playing = new ArrayList<>();

    public CgCameraAnimationEvaluator(CgSequencer sequencer, CgCameraSystem cameras, Function<String, CgSequenceDefinition> animations) {
        this.sequencer = Objects.requireNonNull(sequencer, "sequencer");
        this.cameras = Objects.requireNonNull(cameras, "cameras");
        this.animations = Objects.requireNonNull(animations, "animations");
    }

    @Override
    public String kind() {
        return "camera_animation";
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section) {
        if (section == null) return;
        String where = player.definition().id() + ": tracks." + track.id() + ".sections." + section.id();
        CgSequenceBinding.Subject subject = track.binding().subject();
        if (subject != CgSequenceBinding.Subject.NONE && subject != CgSequenceBinding.Subject.ROLE) {
            throw new CgAssetException(where + ": a camera animation bound to a " + subject.name().toLowerCase(Locale.ROOT) + " is not played yet");
        }
        if (!(section.fields().get("animation") instanceof String id)) throw new CgAssetException(where + ".animation: names its camera sequence");
        CgSequenceDefinition camera = animations.apply(id);
        if (camera == null) throw new CgAssetException(where + ".animation: " + id + " is not loaded");
        int index = -1;
        for (int i = 0; i < camera.spawnables().size(); i++) if (camera.spawnables().get(i).kind().equals("camera")) index = i;
        if (index < 0) throw new CgAssetException(where + ".animation: " + id + " has no camera spawnable");
        // Bound to a role (its prefab node): a camera of its own there, the keys in that node's space.
        CgAttachment base = subject == CgSequenceBinding.Subject.ROLE ? player.attachment(track.binding(), where) : null;
        player.data(track, section, new Animation(player, section, camera, index, base));
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section, float tick) {
        if (section == null || !(player.data(track, section) instanceof Animation a)) return;
        a.at((tick - section.from()) * player.definition().seconds(1));
    }

    /**
     * An animation whose section its play has left ends here, after the frame's evaluation: by the play's time, since
     * a later pass of the same frame evaluates only the plays that handed on. A bound one still playing is placed.
     */
    @Override
    public void postEvaluation() {
        for (int i = playing.size() - 1; i >= 0; i--) {
            Animation a = playing.get(i);
            CgSequenceDefinition d = a.owner.definition();
            double s = a.owner.seconds(), from = d.seconds(a.section.from() - d.start());
            boolean open = a.section.to() == CgSequenceSection.OPEN_END;
            if (s < from || (!open && s >= d.seconds(a.section.to() - d.start()))) a.end(i);
            else a.place();   // after the blend: its camera's keys are on it now
        }
    }

    @Override
    public void stopped(CgSequencePlayer player) {
        for (int i = playing.size() - 1; i >= 0; i--) if (playing.get(i).owner == player) playing.get(i).end(i);
    }

    /**
     * One section's camera animation: its camera sequence playing, and either the modifier it keeps on the viewer's
     * camera or, bound to a role's node, the camera context of its own it places there.
     */
    private final class Animation implements CgCameraModifier {
        final CgSequencePlayer owner;

        @Override
        public int flags() {
            return owner.playbackSettings().flags();
        }

        final CgSequenceSection section;
        final CgSequenceDefinition camera;
        final int spawnable;
        @Nullable
        CgSequencePlayer sub;
        boolean haveInitial;
        final CgCameraPose animated = new CgCameraPose(), initial = new CgCameraPose();
        final Quaternionf invInitial = new Quaternionf(), relative = new Quaternionf(), turned = new Quaternionf();
        final Vector3d offset = new Vector3d();
        @Nullable
        final CgAttachment base;
        @Nullable
        CgCameraContext context;
        final double[] point = new double[3];
        final Quaternionf turn = new Quaternionf();

        Animation(CgSequencePlayer owner, CgSequenceSection section, CgSequenceDefinition camera, int spawnable,
                  @Nullable CgAttachment base) {
            this.owner = owner;
            this.section = section;
            this.camera = camera;
            this.spawnable = spawnable;
            this.base = base;
        }

        /**
         * Bound: the context's pose this frame, the node's world transform times the camera's keyed pose. Kakarot's
         * view actor seeded at the node times key 0, then CameraLocal from key 0, which is the same.
         */
        void place() {
            if (context == null || sub == null || !(sub.spawned(spawnable) instanceof CgCineCamera cam)) return;
            cam.pose(animated);
            if (!base.place(point, turn)) return;
            CgCameraPose pose = context.initial();
            pose.location(point[0], point[1], point[2]).location().add(turn.transform(offset.set(animated.location())));
            pose.rotation().set(turn).mul(animated.rotation());
            pose.fov(Math.max(FOV_MIN, Math.min(FOV_MAX, animated.fov())));
        }

        /** The section is at {@code seconds}: starts the camera sequence, or keeps it on that time. */
        void at(double seconds) {
            if (sub == null) {
                // A viewer the play does not involve never takes its camera.
                if (!owner.involved() || !cameras.involves(owner)) return;
                // From its first key: the initial transform the keys are taken relative to (bRelativeToInitialTransform).
                sub = sequencer.play(camera, Map.of());
                haveInitial = false;
                playing.add(this);
                if (base == null) {
                    cameras.add(this);
                } else {
                    context = new CgCameraContext(owner.definition().id()).owner(owner).flags(owner.playbackSettings().flags());
                    cameras.push(context);
                }
                return;
            }
            // Played on beside its section; put back on it only where it drifts by more than a frame.
            if (Math.abs(sub.seconds() - seconds) > camera.seconds(1) + 1e-6) sub.jumpTo(seconds);
            else sub.rate(owner.rate());
        }

        void end(int index) {
            playing.remove(index);
            cameras.remove(this);
            if (context != null) cameras.remove(context);
            context = null;
            if (sub != null) sub.stop();
            sub = null;
        }

        @Override
        public Object owner() {
            return owner;
        }

        /** {@code ApplyToView}, {@code CameraLocal}, weight 1: the cut-in plays at full scale with no blend in or out. */
        @Override
        public void apply(CgCameraPose pose, float dt) {
            if (base != null || sub == null || !(sub.spawned(spawnable) instanceof CgCineCamera cam)) return;
            cam.pose(animated);
            if (!haveInitial) {
                initial.set(animated);
                initial.rotation().invert(invInitial);
                haveInitial = true;
            }
            // AnimatedCamToInitialCam = AnimatedCamToWorld * InitialCamToWorld.Inverse()
            offset.set(animated.location()).sub(initial.location());
            invInitial.transform(offset);
            invInitial.mul(animated.rotation(), relative);
            pose.location().add(pose.rotation().transform(offset));
            pose.rotation().mul(relative, turned);
            pose.rotation().set(turned);
            pose.fov(Math.max(FOV_MIN, Math.min(FOV_MAX, pose.fov() + (animated.fov() - initial.fov()))));
        }
    }
}
