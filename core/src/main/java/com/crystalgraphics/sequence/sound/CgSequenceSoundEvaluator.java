package com.crystalgraphics.sequence.sound;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.audio.CgAudioEngine;
import com.crystalgraphics.audio.CgSoundAttenuation;
import com.crystalgraphics.audio.CgSoundClass;
import com.crystalgraphics.audio.CgSoundRef;
import com.crystalgraphics.sequence.CgSequenceEvaluator;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceChannel;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;
import com.crystalgraphics.sequence.time.CgSequenceEvaluationRange;
import com.crystalgraphics.world.CgAttachment;

/**
 * The {@code sound} kind: Unreal's audio track ({@code MovieSceneAudioSystem}). A section's sound starts as play
 * crosses its start, on its sample, and stops at the section's end unless it plays until finished; a looping one
 * joined late starts at its age in the section, a one-shot only inside the one-shot window. Placed at its binding,
 * moving with it; unbound, unplaced.
 *
 * <pre>{@code
 * # a section of a sound track
 * - { from: 12, to: 60, fields: { sound: dragonforge:sounds/kame_loop.ogg, looping: true, start_offset: 0 } }
 * }</pre>
 *
 * <ul>
 *   <li>The sequencer registers it; a server plays nothing, having no audio engine.</li>
 *   <li>A {@code volume} channel scales it each frame.</li>
 *   <li>Its sound compiles at bind. A section it cannot play yet (a voice line's {@code speaker}, a {@code pitch}
 *       channel, a cue no kit table holds) is skipped with one warning naming it, and the rest of the sequence
 *       plays.</li>
 * </ul>
 */
public final class CgSequenceSoundEvaluator implements CgSequenceEvaluator {

    /** One section's sound in one play. */
    private static final class Playing {
        final CgSequencePlayer player;
        final CgSoundRef sound;
        @Nullable
        final CgAttachment at;
        final double startOffset;
        final boolean looping, untilFinished;
        long id;
        long touched = -1;
        /** A one-shot that played, or was skipped: never started again in this play. */
        boolean done;

        Playing(CgSequencePlayer player, CgSoundRef sound, @Nullable CgAttachment at, double startOffset, boolean looping,
                boolean untilFinished) {
            this.player = player;
            this.sound = sound;
            this.at = at;
            this.startOffset = startOffset;
            this.looping = looping;
            this.untilFinished = untilFinished;
        }
    }

    private static final Logger LOGGER = LogManager.getLogger("CgSequenceSoundEvaluator");
    /** Each skipped section warned of once a process. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final CgSequencer sequencer;
    private final List<Playing> live = new ArrayList<>();
    private final double[] point = new double[3];
    private long pass;

    public CgSequenceSoundEvaluator(CgSequencer sequencer) {
        this.sequencer = sequencer;
    }

    @Override
    public String kind() {
        return "sound";
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section) {
        if (section == null) return;
        String where = player.definition().id() + ": tracks." + track.id() + ".sections." + section.id();
        if (section.channel("pitch") != null) {
            skip(where + ": a pitch channel is not read yet");
            return;
        }
        CgSoundRef sound;
        try {
            sound = CgSoundRef.of(section.fields().get("sound"), where + ".sound");
        } catch (IllegalArgumentException e) {
            // A sound section must never stop the cut it is in: its camera, notifies and effects still play.
            skip(e.getMessage());
            return;
        }
        CgAttachment at = track.binding() == null ? null : player.attachment(track.binding(), where);
        double offset = section.fields().get("start_offset") instanceof Number n ? player.definition().seconds(n.intValue()) : 0;
        System.out.println("DBG-AU bind " + where + " -> " + sound);
        player.data(track, section, new Playing(player, sound, at, offset, Boolean.TRUE.equals(section.fields().get("looping")),
                Boolean.TRUE.equals(section.fields().get("play_until_finished"))));
    }

    private static void skip(String why) {
        if (WARNED.add(why)) LOGGER.warn("A sound section is skipped: {}", why);
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection s, float tick) {
        if (s == null || !(player.data(track, s) instanceof Playing p)) return;
        CgAudioEngine audio = sequencer.audio();
        if (audio == null) { System.out.println("DBG-AU no audio for " + s.id()); return; }
        p.touched = pass;
        if (p.at != null) p.at.place(point, null);
        if (p.id != 0 && audio.activeSounds().playing(p.id)) {
            if (p.at != null) audio.activeSounds().at(p.id, point[0], point[1], point[2]);
            CgSequenceChannel volume = s.channel("volume");
            if (volume != null && volume.size() > 0) audio.activeSounds().gain(p.id, volume.eval(tick));
            return;
        }
        if (p.done) return;
        int from = s.from() == CgSequenceSection.OPEN_START ? player.definition().start() : s.from();
        double sinceStart = player.definition().seconds(1) * (tick - from);
        CgSequenceEvaluationRange range = player.range();
        boolean crossed = !range.jumped() && from >= range.keyFrom() && from < range.keyEnd();
        if (!p.looping && !crossed && !(range.jumped() && from >= player.oneShotFrom())) {
            // A one-shot that already passed: dropped, as its seek class says.
            p.done = true;
            return;
        }
        float gain = 1f;
        CgSequenceChannel volume = s.channel("volume");
        if (volume != null && volume.size() > 0) gain = volume.eval(tick);
        double x = p.at == null ? Double.NaN : point[0];
        if (crossed) {
            // On its sample: where its start fell in this frame, on the audio clock.
            double atTime = audio.clock().now() - player.passRemainingSeconds() - sinceStart / Math.max(1e-6f, player.rate());
            p.id = audio.play(p.sound, CgSoundClass.SFX, x, point[1], point[2], gain, 1f, p.looping, atTime, player.seed());
            audio.activeSounds().startOffset(p.id, p.startOffset);
        } else {
            p.id = audio.play(p.sound, CgSoundClass.SFX, x, point[1], point[2], gain, 1f, p.looping, Double.NaN, player.seed());
            audio.activeSounds().startOffset(p.id, sinceStart + p.startOffset);
        }
        if (p.at != null && p.sound.attenuation() == null && p.sound.kind() != CgSoundRef.Kind.EVENT) {
            audio.activeSounds().attenuation(p.id, CgSoundAttenuation.DEFAULT);
        }
        System.out.println("DBG-AU start " + p.sound + " tick " + tick + " id " + p.id + " crossed " + crossed + " at " + (p.at != null ? point[0] + "," + point[1] + "," + point[2] : "unplaced") + " listener " + audio.listener().x() + "," + audio.listener().y() + "," + audio.listener().z());
        if (!p.looping) p.done = true;
        if (!live.contains(p)) live.add(p);
    }

    /** Stops each sound whose section left play this pass, unless it plays until finished. */
    @Override
    public void postEvaluation() {
        CgAudioEngine audio = live.isEmpty() ? null : sequencer.audio();
        for (int i = live.size() - 1; i >= 0; i--) {
            Playing p = live.get(i);
            if (p.touched == pass) continue;
            if (!p.untilFinished && audio != null) audio.activeSounds().stop(p.id);
            p.id = 0;
            live.remove(i);
        }
        pass++;
    }

    @Override
    public void stopped(CgSequencePlayer player) {
        CgAudioEngine audio = sequencer.audio();
        for (int i = live.size() - 1; i >= 0; i--) {
            Playing p = live.get(i);
            if (p.player != player) continue;
            if (audio != null) audio.activeSounds().stop(p.id);
            live.remove(i);
        }
    }

    @Override
    public void ended(CgSequencePlayer player, CgSequenceTrack track, CgSequenceSection section) {
        if (!(player.data(track, section) instanceof Playing p) || p.untilFinished) return;
        CgAudioEngine audio = sequencer.audio();
        if (audio != null) audio.activeSounds().stop(p.id);
        p.id = 0;
    }
}
