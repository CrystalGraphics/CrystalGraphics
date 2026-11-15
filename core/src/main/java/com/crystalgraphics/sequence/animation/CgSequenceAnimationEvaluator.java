package com.crystalgraphics.sequence.animation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.animation.graph.CgAnimGraph;
import com.crystalgraphics.animation.graph.CgAnimSlot;
import com.crystalgraphics.animation.montage.CgMontage;
import com.crystalgraphics.animation.montage.CgMontageInstance;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.sequence.CgSequenceEvaluator;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;

/**
 * The {@code animation} kind: a play's sections on one character are one montage ({@link CgMontageInstance}), each
 * section's clip played on its slot at the clip time Sequencer maps the section's time to. Unreal's skeletal animation
 * track ({@code MovieSceneSkeletalAnimationSystem.cpp:552-557}, {@link CgAnimSection#animationTime}'s mapping) as
 * a montage instance plays it ({@code FAnimMontageInstance::Advance}, AnimMontage.cpp:2549-2817).
 *
 * <ul>
 *   <li><b>The montage</b>: the character's first section's ease in is its blend in and its last section's ease out its
 *       blend out, linear unless they name a function; {@code blend_out_trigger_time}, {@code auto_blend_out},
 *       {@code slot_group}, {@code sync_group} and {@code root_motion} are read from whichever section says them.
 *       Root motion is on where any of its clips has it, unless {@code root_motion} says otherwise.</li>
 *   <li><b>Its end</b>: in a montage section that hands play to none (or the sequence's own end), it blends out once
 *       the time left is within its trigger time, over its blend out, or over the time left where the trigger is
 *       unset; and once no section of it plays, at once.</li>
 *   <li><b>A stop</b> interrupts it: it blends out over its blend out, its clips playing on alone. Another montage of
 *       its slot group playing stops its play.</li>
 *   <li>A section names its slot ({@code slot}, {@link CgAnimGraph#DEFAULT_SLOT} where it names none); a slot the
 *       character's graph lacks plays nothing, as in Unreal. A {@code stop} section plays the slot's source through
 *       ({@link CgAnimSlot#stop}).</li>
 * </ul>
 */
public final class CgSequenceAnimationEvaluator implements CgSequenceEvaluator {

    /** A stop section's bound data. */
    private static final Object STOP = new Object();
    /** {@code UE_KINDA_SMALL_NUMBER}: the auto blend-out's least window. */
    private static final double KINDA_SMALL = 1e-4;

    /** A play's montage on one character, while its play lasts. */
    private static final class Montage {
        final CgSequencePlayer player;
        final CgSequenceSkeletal target;
        final CgMontageInstance instance;
        final CgSequenceSection first, last;
        final boolean customTrigger;
        int written = Integer.MIN_VALUE, ended = Integer.MIN_VALUE;

        Montage(CgSequencePlayer player, CgSequenceSkeletal target, CgMontageInstance instance, CgSequenceSection first,
                CgSequenceSection last, boolean customTrigger) {
            this.player = player;
            this.target = target;
            this.instance = instance;
            this.first = first;
            this.last = last;
            this.customTrigger = customTrigger;
        }
    }

    /** A clip section's bound data: its montage, its mapping, and where it last played. */
    private static final class Bound {
        final Montage montage;
        final CgAnimSection clip;
        final String slot;
        double lastSeconds = Double.NaN;
        float lastClip;
        int lastPass = Integer.MIN_VALUE;

        Bound(Montage montage, CgAnimSection clip, String slot) {
            this.montage = montage;
            this.clip = clip;
            this.slot = slot;
        }
    }

    private final List<Montage> montages = new ArrayList<>();

    @Override
    public String kind() {
        return "animation";
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section) {
        CgSequenceTarget target = player.target(track);
        if (target == null || section == null) return;
        String at = player.definition().id() + ": tracks." + track.id() + ".sections." + section.id();
        if (!(target instanceof CgSequenceSkeletal skeletal)) throw new CgAssetException(at + ": " + target + " plays no animation");
        if (section.blend() != null && section.blend() != CgSequenceSection.Blend.ABSOLUTE) {
            throw new CgAssetException(at + ": a " + section.blend().name().toLowerCase(Locale.ROOT) + " animation is not played yet");
        }
        if ("step".equals(section.fields().get("interpolation"))) throw new CgAssetException(at + ": interpolation: step is not played yet");
        if (Boolean.TRUE.equals(section.fields().get("stop"))) {
            if (!(section.fields().get("slot") instanceof String)) throw new CgAssetException(at + ": a stop names its slot");
            player.data(track, section, STOP);
            return;
        }
        if (!(section.fields().get("clip") instanceof String id)) throw new CgAssetException(at + ": no clip");
        CgAnimSequence clip = skeletal.clip(id);
        if (clip == null) throw new CgAssetException(at + ": " + target + " has no clip " + id);

        CgSequenceDefinition d = player.definition();
        CgAnimSection.Builder b = CgAnimSection.of(clip)
                .at(section.from() == CgSequenceSection.OPEN_START ? Double.NEGATIVE_INFINITY : d.seconds(section.from()))
                .until(section.to() == CgSequenceSection.OPEN_END ? Double.POSITIVE_INFINITY : d.seconds(section.to()));
        if (section.fields().get("play_rate") instanceof Number rate) b.playRate(rate.floatValue());
        if (section.fields().get("start_offset") instanceof Number ticks) b.startOffset((float) d.seconds(ticks.intValue()));
        Object loop = section.fields().get("loop");
        if (Boolean.TRUE.equals(loop) || loop instanceof Number n && n.intValue() > 1) b.loop();
        String slot = section.fields().get("slot") instanceof String name ? name : CgAnimGraph.DEFAULT_SLOT;
        player.data(track, section, new Bound(montage(player, skeletal), b.build(), slot));
    }

    /** {@code player}'s montage on {@code target}, played the first time one of its sections binds. */
    private Montage montage(CgSequencePlayer player, CgSequenceSkeletal target) {
        for (int i = 0; i < montages.size(); i++) {
            Montage m = montages.get(i);
            if (m.player == player && m.target == target) return m;
        }
        CgSequenceDefinition d = player.definition();
        CgSequenceSection first = null, last = null;
        float trigger = -1f;
        boolean auto = true, anyRootMotion = false;
        Boolean rootMotion = null;
        String group = CgMontage.DEFAULT_GROUP, syncGroup = null;
        for (CgSequenceTrack t : d.tracks()) {
            if (!t.kind().equals(kind()) || t.muted() || player.target(t) != target) continue;
            for (CgSequenceSection s : t.sections()) {
                if (!s.active() || !(s.fields().get("clip") instanceof String id)) continue;
                if (first == null || s.from() < first.from()) first = s;
                if (last == null || s.to() > last.to()) last = s;
                if (s.fields().get("blend_out_trigger_time") instanceof Number n) trigger = (float) d.seconds(n.intValue());
                if (Boolean.FALSE.equals(s.fields().get("auto_blend_out"))) auto = false;
                if (s.fields().get("slot_group") instanceof String g) group = g;
                if (s.fields().get("sync_group") instanceof String g) syncGroup = g;
                if (s.fields().get("root_motion") instanceof Boolean r) rootMotion = r;
                CgAnimSequence clip = target.clip(id);
                anyRootMotion |= clip != null && clip.settings().rootMotion().enabled();
            }
        }
        CgSequenceSection.Ease in = first == null ? null : first.ease(), out = last == null ? null : last.ease();
        CgMontage settings = new CgMontage(group,
                in == null ? 0f : (float) d.seconds(in.in()), in == null || in.inFn() == null ? CgEasings.LINEAR : in.inFn(),
                out == null ? 0f : (float) d.seconds(out.out()), out == null || out.outFn() == null ? CgEasings.LINEAR : out.outFn(),
                trigger, auto, rootMotion != null ? rootMotion : anyRootMotion, syncGroup);
        CgMontageInstance instance = target.instance().montages().play(settings, player).onInterrupted(player::stop);
        Montage m = new Montage(player, target, instance, first, last, trigger >= 0f);
        montages.add(m);
        return m;
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section, float tick) {
        if (section == null || !(player.target(track) instanceof CgSequenceSkeletal skeletal)) return;
        Object data = player.data(track, section);
        if (data == STOP) {
            CgAnimSlot slot = skeletal.instance().slot((String) section.fields().get("slot"));
            if (slot != null) slot.stop(section.weight(tick));
            return;
        }
        if (!(data instanceof Bound b)) return;
        Montage m = b.montage;
        CgMontageInstance inst = m.instance;
        if (inst.terminated()) return;
        int pass = player.pass();
        if (m.written != pass) {
            m.written = pass;
            inst.updateWeight(frameSeconds(player, skeletal.instance()));
            inst.beginPass(pass);
            autoBlendOut(m, player.timeToEnd());
        }

        double seconds = tick * player.definition().seconds(1);
        float time = b.clip.animationTime(seconds), from, moved;
        if (player.jumped()) {
            from = time;
            moved = 0f;
        } else if (Double.isNaN(b.lastSeconds) || seconds < b.lastSeconds || b.lastPass != pass - 1) {
            // Entered by play (a loop's section played again included): from the section's start
            double start = Double.isInfinite(b.clip.start()) ? seconds : b.clip.start();
            from = b.clip.animationTime(start);
            moved = (float) ((seconds - start) * b.clip.playRate());
        } else {
            from = b.lastClip;
            moved = (float) ((seconds - b.lastSeconds) * b.clip.playRate());
        }
        b.lastSeconds = seconds;
        b.lastClip = time;
        b.lastPass = pass;
        CgAnimSlot slot = skeletal.instance().slot(b.slot);
        if (slot == null) return;
        inst.entry(slot, b.clip.sequence(), time, section.weight(tick, section != m.first, section != m.last), null,
                b.clip.playRate(), b.clip.loops(), from, moved);
    }

    /**
     * {@code Advance}'s auto blend-out (AnimMontage.cpp:2619-2639): within its trigger time of its end, it blends out
     * over its blend out, or over the time left where its trigger is unset.
     */
    private static void autoBlendOut(Montage m, double timeToEnd) {
        CgMontageInstance inst = m.instance;
        CgMontage s = inst.montage();
        if (inst.stopped() || !s.autoBlendOut()) return;
        double trigger = m.customTrigger ? s.blendOutTriggerTime() : s.blendOut();
        if (timeToEnd <= Math.max(trigger, KINDA_SMALL)) {
            inst.stop(m.customTrigger ? s.blendOut() : (float) timeToEnd, s.blendOutCurve(), false);
        }
    }

    /** The anim instance's seconds for its frame: its rate, 0 paused. */
    private static float frameSeconds(CgSequencePlayer player, CgAnimInstance anim) {
        return anim.playing() ? player.frameSeconds() * anim.rate() : 0f;
    }

    /** After a pass: a montage none of whose sections played in its play's latest pass has reached its end. */
    @Override
    public void postEvaluation() {
        for (int i = 0; i < montages.size(); i++) {
            Montage m = montages.get(i);
            int pass = m.player.pass();
            if (m.written == pass || m.ended == pass || m.player.stopped() || m.instance.terminated()) continue;
            m.ended = pass;
            m.instance.updateWeight(frameSeconds(m.player, m.target.instance()));
            autoBlendOut(m, 0);
        }
    }

    /** Its montages interrupted, blending out on their own. */
    @Override
    public void stopped(CgSequencePlayer player) {
        for (int i = montages.size() - 1; i >= 0; i--) {
            Montage m = montages.get(i);
            if (m.player != player) continue;
            if (!m.instance.stopped()) m.instance.stop();
            m.instance.release();
            montages.remove(i);
        }
    }
}
