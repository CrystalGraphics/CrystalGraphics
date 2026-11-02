package com.crystalgraphics.vfx.camera;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.camera.CgCameraShakeDefinition;
import com.crystalgraphics.asset.kind.CgCameraKinds;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.sequence.CgSequenceEvaluator;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;
import com.crystalgraphics.sequence.time.CgSequenceEvaluationRange;
import com.crystalgraphics.world.CgAttachment;

/**
 * Plays a sequence's {@code shake} sections on this viewer's camera: Unreal's {@code UMovieSceneCameraShakeTrack},
 * evaluated as {@code UMovieSceneCameraShakeInstantiatorSystem} does
 * (Engine/Source/Runtime/MovieSceneTracks/Private/Systems/MovieSceneCameraShakeSystem.cpp).
 *
 * <pre>{@code
 * sequencer.register(new CgCameraShakeTrackEvaluator());   // CgVfxSequencer.install does it for the engine's sequencers
 * }</pre>
 *
 * <pre>{@code
 * tracks:
 *   - { id: t1, kind: shake, sections: [ { id: s1, range: [400, 7120], shake: mymod:hit/heavy, scale: 0.5 } ] }
 * }</pre>
 *
 * <ul>
 *   <li>A section starts its shake as play crosses its start, at its {@code scale} and {@code space}; a jump past its
 *       start fires it only within the one-shot window, as every one-shot.</li>
 *   <li>A shake that outlasts its section, or never ends by itself, runs for the section's length and blends out
 *       inside it ({@code ComputeCameraShakeDurationOverride}).</li>
 *   <li>Its section ending, or the play stopping, ends it at once unless the section keeps its state: Unreal's
 *       pre-animated state restored, and a keep-state shake left to run on.</li>
 *   <li>It is felt everywhere, unfaded by distance: the camera plays it. Where its play stands is the sequence's
 *       origin, for a world-space shake.</li>
 *   <li>A section naming a shake that is not compiled, or {@code space: user_defined}, fails as it binds, naming it.</li>
 * </ul>
 */
public final class CgCameraShakeTrackEvaluator implements CgSequenceEvaluator {

    private final double[] point = new double[3];
    private final List<Shaking> live = new ArrayList<>();
    private int pass;

    @Override
    public String kind() {
        return "shake";
    }

    /** One section's shake: what it plays, and the play once started. */
    private static final class Shaking {
        final CgSequencePlayer player;
        final CgCameraShakeDefinition shake;
        final float scale;
        final boolean world, keeps;
        /** Its section's length in seconds; 0 for an open one. */
        final float length;
        @Nullable
        CgShakePlay play;
        /** Which start of the pooled play is this section's: a play ended and restarted is another's. */
        int start;
        boolean done;
        int touched;

        Shaking(CgSequencePlayer player, CgCameraShakeDefinition shake, float scale, boolean world, boolean keeps, float length) {
            this.player = player;
            this.shake = shake;
            this.scale = scale;
            this.world = world;
            this.keeps = keeps;
            this.length = length;
        }
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section) {
        if (section == null) return;
        String where = player.definition().id() + ": tracks." + track.id() + ".sections." + section.id();
        Object id = section.fields().get("shake");
        if (!(id instanceof String ref)
                || !(CgVfxAssets.ref(CgCameraKinds.SHAKE, ref).loaded() instanceof CgCameraShakeDefinition shake)) {
            throw new CgAssetException(where + ".shake: " + id + " is not a compiled shake");
        }
        boolean world;
        try {
            world = CgCameraShakeDefinition.worldSpace(section.fields().get("space"), where + ".space");
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(e.getMessage());
        }
        float scale = section.fields().get("scale") instanceof Number n ? n.floatValue() : 1f;
        boolean bounded = section.from() != CgSequenceSection.OPEN_START && section.to() != CgSequenceSection.OPEN_END;
        float length = bounded ? (float) player.definition().seconds(section.to() - section.from()) : 0f;
        player.data(track, section, new Shaking(player, shake, scale, world,
                section.completion() == CgSequenceSection.Completion.KEEP_STATE, length));
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection s, float tick) {
        if (s == null || !(player.data(track, s) instanceof Shaking p)) return;
        p.touched = pass;
        if (p.done) return;
        p.done = true;
        int from = s.from() == CgSequenceSection.OPEN_START ? player.definition().start() : s.from();
        CgSequenceEvaluationRange range = player.range();
        boolean crossed = !range.jumped() && from >= range.keyFrom() && from < range.keyEnd();
        // A one-shot that already passed: dropped, as its seek class says
        if (!crossed && !(range.jumped() && from >= player.oneShotFrom())) return;
        CgAttachment origin = player.origin();
        if (origin == null || !origin.place(point, null)) point[0] = point[1] = point[2] = 0;
        CgShakePlay play = p.shake.play(point[0], point[1], point[2], p.scale, player.seed(), 0f, 0f, 1f, p.world);
        if (play == null) return;
        float own = play.duration();
        if (p.length > 0f && (own <= 0f || own > p.length)) play.durationOverride(p.length);
        p.play = play;
        p.start = play.starts();
        live.add(p);
    }

    /** Ends each shake whose section left play this pass, unless its section keeps its state. */
    @Override
    public void postEvaluation() {
        for (int i = live.size() - 1; i >= 0; i--) {
            Shaking p = live.get(i);
            if (p.touched == pass) continue;
            end(p);
            live.remove(i);
        }
        pass++;
    }

    @Override
    public void stopped(CgSequencePlayer player) {
        for (int i = live.size() - 1; i >= 0; i--) {
            Shaking p = live.get(i);
            if (p.player != player) continue;
            end(p);
            live.remove(i);
        }
    }

    private static void end(Shaking p) {
        if (!p.keeps && p.play != null && p.play.playing() && p.play.starts() == p.start) p.play.stop(true);
        p.play = null;
    }

    /** Registers one on {@code sequencer}, unless it plays shakes already. */
    public static void install(CgSequencer sequencer) {
        if (!sequencer.evaluates("shake")) sequencer.register(new CgCameraShakeTrackEvaluator());
    }
}
