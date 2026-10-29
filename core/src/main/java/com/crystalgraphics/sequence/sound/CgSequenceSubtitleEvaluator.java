package com.crystalgraphics.sequence.sound;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.crystalgraphics.audio.CgAudioEngine;
import com.crystalgraphics.audio.CgSoundRef;
import com.crystalgraphics.audio.CgSubtitles;
import com.crystalgraphics.i18n.CgKitStrings;
import com.crystalgraphics.sequence.CgSequenceEvaluator;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;

/**
 * The {@code subtitle} kind: a line shown while its section is active, in the kit's {@code subtitle_style}. Its
 * {@code text} is a string id ({@code <ns>:text/<id>}) the kit's {@code strings/<locale>.yaml} holds, read in the player's
 * language, its speaker the string's own unless the section names one; an open end
 * lasts as long as the {@code sound} section it names. A lasting kind: active while play is in its section, and joined
 * by a seek at its age there, the line driven by the sequence's own clock ({@link CgSubtitles#drive}).
 *
 * <pre>{@code
 * # a subtitle track's section, beside the voice it captions
 * - { id: line1, range: [35189, 125189], text: kakarot:text/V_C001A_skl_01_Msg, sound: voice1 }
 * }</pre>
 *
 * <ul>
 *   <li>{@code speaker}, where a section sets it, is a string id too, over the string's own.</li>
 *   <li>A section whose text no string table holds is skipped with one warning naming it, and the rest plays.</li>
 *   <li>The sequencer registers it; a server shows nothing, having no audio engine.</li>
 * </ul>
 */
public final class CgSequenceSubtitleEvaluator implements CgSequenceEvaluator {

    /** One section's line in one play. */
    private static final class Line {
        final CgSequencePlayer player;
        final String text;
        @Nullable
        final String speaker;
        /** The section's own length in seconds; NaN for an open end, which the linked sound's sets. */
        final double length;
        @Nullable
        final CgSoundRef sound;
        long key;
        long touched = -1;

        Line(CgSequencePlayer player, String text, @Nullable String speaker, double length, @Nullable CgSoundRef sound) {
            this.player = player;
            this.text = text;
            this.speaker = speaker;
            this.length = length;
            this.sound = sound;
        }
    }

    private static final Logger LOGGER = LogManager.getLogger("CgSequenceSubtitleEvaluator");
    /** Each skipped section warned of once a process. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final CgSequencer sequencer;
    private final List<Line> live = new ArrayList<>();
    private long pass;

    public CgSequenceSubtitleEvaluator(CgSequencer sequencer) {
        this.sequencer = sequencer;
    }

    @Override
    public String kind() {
        return "subtitle";
    }

    @Override
    public void bind(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection section) {
        if (section == null) return;
        CgSequenceDefinition d = player.definition();
        String where = d.id() + ": tracks." + track.id() + ".sections." + section.id();
        String id = section.fields().get("text") instanceof String s ? s : null;
        CgKitStrings.Entry entry = id == null ? null : CgKitStrings.entry(id);
        if (entry == null) {
            skip(where + ".text: " + (id == null ? "none" : id + " is in no kit's string table"));
            return;
        }
        String speakerRef = section.fields().get("speaker") instanceof String n ? n : entry.speaker();
        String speaker = CgKitStrings.text(speakerRef);
        if (speakerRef != null && speaker == null) skip(where + ".speaker: " + speakerRef + " is in no kit's string table");
        double length = section.to() == CgSequenceSection.OPEN_END || section.from() == CgSequenceSection.OPEN_START
                ? Double.NaN : d.seconds(section.to() - section.from());
        CgSoundRef sound = null;
        if (section.fields().get("sound") instanceof String linked) {
            sound = soundOf(d, linked, where);
            if (sound == null) return;
        }
        player.data(track, section, new Line(player, entry.text(), speaker, length, sound));
    }

    /** The sound of the {@code sound} section {@code id}, for an open end's length; null, skipped, where none is. */
    @Nullable
    private static CgSoundRef soundOf(CgSequenceDefinition d, String id, String where) {
        for (CgSequenceTrack t : d.tracks()) {
            if (!t.kind().equals("sound")) continue;
            for (CgSequenceSection s : t.sections()) {
                if (!s.id().equals(id)) continue;
                try {
                    return CgSoundRef.of(s.fields().get("sound"), where + ".sound");
                } catch (IllegalArgumentException e) {
                    skip(e.getMessage());
                    return null;
                }
            }
        }
        skip(where + ".sound: no sound section " + id);
        return null;
    }

    private static void skip(String why) {
        if (WARNED.add(why)) LOGGER.warn("A subtitle section is skipped: {}", why);
    }

    @Override
    public void evaluate(CgSequencePlayer player, CgSequenceTrack track, @Nullable CgSequenceSection s, float tick) {
        if (s == null || !(player.data(track, s) instanceof Line line)) return;
        CgAudioEngine audio = sequencer.audio();
        if (audio == null) return;
        line.touched = pass;
        CgSequenceDefinition d = player.definition();
        int from = s.from() == CgSequenceSection.OPEN_START ? d.start() : s.from();
        double into = d.seconds(1) * (tick - from);
        if (line.key == 0) {
            double length = line.length;
            if (Double.isNaN(length)) {
                length = line.sound != null ? audio.seconds(line.sound, player.seed()) : d.seconds(d.end() - from);
            }
            line.key = audio.subtitles().key();
            audio.subtitles().queueDriven(line.key, line.text, line.speaker, length);
            if (!live.contains(line)) live.add(line);
        }
        audio.subtitles().drive(line.key, into);
    }

    /** Drops each line whose section left play this pass. */
    @Override
    public void postEvaluation() {
        CgAudioEngine audio = live.isEmpty() ? null : sequencer.audio();
        for (int i = live.size() - 1; i >= 0; i--) {
            Line line = live.get(i);
            if (line.touched == pass) continue;
            if (audio != null) audio.subtitles().kill(line.key);
            line.key = 0;
            live.remove(i);
        }
        pass++;
    }

    @Override
    public void stopped(CgSequencePlayer player) {
        CgAudioEngine audio = sequencer.audio();
        for (int i = live.size() - 1; i >= 0; i--) {
            Line line = live.get(i);
            if (line.player != player) continue;
            if (audio != null) audio.subtitles().kill(line.key);
            line.key = 0;
            live.remove(i);
        }
    }

    @Override
    public void ended(CgSequencePlayer player, CgSequenceTrack track, CgSequenceSection section) {
        if (!(player.data(track, section) instanceof Line line) || line.key == 0) return;
        CgAudioEngine audio = sequencer.audio();
        if (audio != null) audio.subtitles().kill(line.key);
        line.key = 0;
        live.remove(line);
    }
}
