package com.crystalgraphics.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.i18n.CgKitStrings;

/**
 * A sound's own subtitles, queued each time it starts playing: Unreal's {@code USoundWave::Subtitles} and
 * {@code SubtitlePriority}, which {@code USoundWave::HandleStart} hands {@code FSubtitleManager::QueueSubtitles}.
 * Written on the sound, in a kit's {@code sounds.yaml} or a sound map, so whoever plays it need do nothing:
 *
 * <pre>{@code
 * # a kit's sounds.yaml: a voice line, its text from the kit's strings or the mod's lang file
 * V_PL_SKL_01: { path: kakarot_kit:sounds/V_PL_SKL_01.ogg, subtitles: [ { text: kakarot:text/V_PL_SKL_01_Msg } ] }
 *
 * # a sound map anywhere a sound is named (a cue's sounds, a sequence's sound section): two cues, a higher priority
 * sound: { path: mymod:sounds/kame.ogg, subtitle_priority: 20000,
 *          subtitles: [ { text: mymod:text/kame_charge }, { text: mymod:text/kame_fire, time: 1.4 } ] }
 * }</pre>
 *
 * <ul>
 *   <li>A cue's {@code text} is a string id ({@code <ns>:text/<id>}, read through {@link CgKitStrings}: a kit's table,
 *       else the mod's {@code lang} file) or the literal text, read in the player's language as the sound starts.</li>
 *   <li>A cue's {@code time} is seconds into the sound, 0 unless written; cues climb, or the sound fails where it
 *       loads, named.</li>
 *   <li>Shown while {@code CgGraphicsSettings.SUBTITLES} is on; the highest priority playing shows
 *       ({@link CgSubtitles}).</li>
 *   <li>A host's sound event takes none of these: its subtitle is its {@code sounds.json}'s.</li>
 * </ul>
 *
 * @param cues     the lines, by seconds into the sound
 * @param priority over other sounds' subtitles; {@link CgSubtitles#DEFAULT_PRIORITY} unless written
 */
public record CgSoundSubtitles(List<CgSubtitles.Cue> cues, float priority) {

    /** A sound with none. */
    public static final CgSoundSubtitles NONE = new CgSoundSubtitles(List.of(), CgSubtitles.DEFAULT_PRIORITY);

    public CgSoundSubtitles {
        cues = List.copyOf(cues);
    }

    /** {@code sound}'s {@code subtitles} and {@code subtitle_priority}, as written; {@link #NONE} where it has none. */
    static CgSoundSubtitles read(Map<?, ?> sound, String where) {
        Object written = sound.get("subtitles");
        if (written == null) return NONE;
        if (!(written instanceof List<?> list)) throw new IllegalArgumentException(where + ".subtitles is not a list");
        List<CgSubtitles.Cue> cues = new ArrayList<>(list.size());
        float last = -1f;
        for (int i = 0; i < list.size(); i++) {
            String at = where + ".subtitles[" + i + "]";
            if (!(list.get(i) instanceof Map<?, ?> cue) || !(cue.get("text") instanceof String text)) {
                throw new IllegalArgumentException(at + ": a cue is { text, time }");
            }
            float time = cue.get("time") instanceof Number n ? n.floatValue() : 0f;
            if (time < 0f || time <= last) throw new IllegalArgumentException(at + ".time " + time + ": cues climb from 0");
            cues.add(new CgSubtitles.Cue(text, time));
            last = time;
        }
        float priority = sound.get("subtitle_priority") instanceof Number p ? p.floatValue() : CgSubtitles.DEFAULT_PRIORITY;
        return new CgSoundSubtitles(cues, priority);
    }

    /** Whether it has a line. */
    public boolean any() {
        return !cues.isEmpty();
    }

    /** Queues its lines on {@code subtitles} for the sound {@code id}, {@code seconds} long, in the player's language. */
    void queue(@Nullable CgSubtitles subtitles, long id, double seconds) {
        if (cues.isEmpty() || subtitles == null || id == 0) return;
        List<CgSubtitles.Cue> read = new ArrayList<>(cues.size());
        for (CgSubtitles.Cue cue : cues) {
            String text = CgKitStrings.text(cue.text());
            read.add(new CgSubtitles.Cue(text != null ? text : cue.text(), cue.time()));
        }
        subtitles.queue(id, priority, seconds, read);
    }
}
