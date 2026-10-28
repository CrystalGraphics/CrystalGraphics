package com.crystalgraphics.sequence.animation;

import java.util.Locale;

import javax.annotation.Nullable;

import com.crystalgraphics.api.animation.CgAnimSection;
import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.sequence.CgSequenceEvaluator;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;

/**
 * The {@code animation} kind: each section's clip played on its target's montage slot, at the clip time Sequencer maps
 * the section's time to and its weight, every frame play is inside it. Unreal's skeletal animation track
 * ({@code MovieSceneSkeletalAnimationSystem.cpp:552-557}: {@code Weight * EvaluateEasing}, then
 * {@code UpdateAnimTrack(clip, position, weight)}, AnimSequencerInstanceProxy.cpp:266-299); the mapping is
 * {@link CgAnimSection#animationTime}'s. A section play has left sets nothing, so its clip is gone from the next pose.
 */
public final class CgSequenceAnimationEvaluator implements CgSequenceEvaluator {

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
        player.data(track, section, b.build());
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section, float tick) {
        if (section == null || !(player.data(track, section) instanceof CgAnimSection clip)
                || !(player.target(track) instanceof CgSequenceSkeletal skeletal)) return;
        double seconds = tick * player.definition().seconds(1);
        skeletal.instance().slot(clip.sequence(), clip.animationTime(seconds), section.weight(tick));
    }
}
