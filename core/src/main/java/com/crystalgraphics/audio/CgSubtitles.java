package com.crystalgraphics.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongToDoubleFunction;

import javax.annotation.Nullable;

/**
 * The subtitles of the sounds playing, and the one shown: Unreal's {@code FSubtitleManager}. A sound's subtitle is a
 * list of cues over its duration, queued as it plays and dropped when it ends or stops; each frame the highest priority
 * one shows the cue its sound has reached.
 *
 * <pre>{@code
 * long id = audio.play(sound, CgSoundClass.VOICE, 1f);
 * audio.subtitles().queue(id, "Kamehameha!", sound.seconds());                     // one line for the whole sound
 * audio.subtitles().queue(id, CgSubtitles.DEFAULT_PRIORITY, sound.seconds(),       // cues, from the sound's start
 *         List.of(new CgSubtitles.Cue("Kame...", 0f), new CgSubtitles.Cue("...hameha!", 1.4f)));
 * }</pre>
 *
 * A sequence's {@code subtitle} section is a driven line: keyed by {@link #key()}, its time set by its owner each frame,
 * so it follows the sequence's clock through a pause or a seek, and dropped by its owner when its section ends:
 *
 * <pre>{@code
 * long key = audio.subtitles().key();
 * audio.subtitles().queueDriven(key, "Kamehame...", "Goku", seconds);
 * audio.subtitles().drive(key, secondsIntoSection);   // each frame it is active
 * audio.subtitles().kill(key);                         // its section ended
 * }</pre>
 *
 * <ul>
 *   <li>Minecraft's own events queue theirs as {@link CgAudioEngine#playEvent} plays them, in the player's language.</li>
 *   <li>Shown only while {@code CgGraphicsSettings.SUBTITLES} is on; queued either way.</li>
 *   <li>A cue follows its sound's own playback, so a hitch, a pause or a sound joined late reads the cue it is at.</li>
 *   <li>A second queue for one id replaces the first. Priority 0, no cues or no duration queue nothing.</li>
 *   <li>Equal priorities show the latest queued. Frame thread.</li>
 * </ul>
 */
public final class CgSubtitles {

    /** Unreal's {@code DEFAULT_SUBTITLE_PRIORITY}: what a sound's subtitle has unless it says otherwise. */
    public static final float DEFAULT_PRIORITY = 10000f;

    /** One line, from {@code time} seconds into the sound until the next: Unreal's {@code FSubtitleCue}. */
    public record Cue(String text, float time) {
    }

    /** A sound's cues, by seconds into it, a blank at its end: Unreal's {@code FActiveSubtitle}. */
    private static final class Active {
        final long id;
        final float priority;
        final long order;
        final String[] text;
        final double[] at;
        @Nullable
        final String speaker;
        /** Its owner sets its time ({@link #drive}); else its sound's playback does. */
        final boolean driven;
        double time;
        int index;

        Active(long id, float priority, long order, String[] text, double[] at, @Nullable String speaker, boolean driven) {
            this.id = id;
            this.priority = priority;
            this.order = order;
            this.text = text;
            this.at = at;
            this.speaker = speaker;
            this.driven = driven;
        }
    }

    private final List<Active> active = new ArrayList<>();
    private long queued;
    /** The last driven key handed out; negative, so never a sound's id. */
    private long keys;
    @Nullable
    private Active shown;

    /** {@code text} for the whole of a sound {@code duration} seconds long, at the default priority. */
    public void queue(long id, String text, double duration) {
        queue(id, DEFAULT_PRIORITY, duration, List.of(new Cue(text, 0f)));
    }

    /** {@code cues} for a sound {@code duration} seconds long, each clamped to it, the last shown to its end. */
    public void queue(long id, float priority, double duration, List<Cue> cues) {
        add(id, priority, duration, cues, null, false);
    }

    /** A fresh key for a driven line: never a sound's id, never one handed out before. */
    public long key() {
        return --keys;
    }

    /** {@code text} said by {@code speaker} (null for none) for {@code duration} seconds, its time driven by its owner. */
    public void queueDriven(long key, String text, @Nullable String speaker, double duration) {
        add(key, DEFAULT_PRIORITY, duration, List.of(new Cue(text, 0f)), speaker, true);
    }

    /** {@code key}'s line at {@code seconds} into it; past its duration it ends. */
    public void drive(long key, double seconds) {
        for (int i = 0; i < active.size(); i++) {
            if (active.get(i).id == key) active.get(i).time = seconds;
        }
    }

    private void add(long id, float priority, double duration, List<Cue> cues, @Nullable String speaker, boolean driven) {
        if (id == 0 || cues.isEmpty() || priority == 0f || !(duration > 0)) return;
        int n = cues.size();
        String[] text = new String[n + 1];
        double[] at = new double[n + 1];
        for (int i = 0; i < n; i++) {
            text[i] = cues.get(i).text();
            at[i] = Math.min(cues.get(i).time(), duration);
        }
        // A blank at the sound's end clears it.
        text[n] = "";
        at[n] = duration;
        kill(id);
        active.add(new Active(id, priority, queued++, text, at, speaker, driven));
    }

    /** Drops {@code id}'s subtitle: its sound stopped ({@code KillSubtitles}). */
    public void kill(long id) {
        for (int i = active.size() - 1; i >= 0; i--) {
            if (active.get(i).id == id) {
                if (shown == active.get(i)) shown = null;
                active.remove(i);
            }
        }
    }

    /** Drops every subtitle. */
    public void clear() {
        active.clear();
        shown = null;
    }

    /**
     * Steps every subtitle to the seconds its sound has played, {@code played.applyAsDouble(id)}, NaN once it stopped;
     * drops the ended and the stopped, and picks the one shown ({@code FindHighestPrioritySubtitle}).
     */
    public void update(LongToDoubleFunction played) {
        shown = null;
        for (int i = active.size() - 1; i >= 0; i--) {
            Active a = active.get(i);
            double t = a.driven ? a.time : played.applyAsDouble(a.id);
            while (a.index < a.text.length - 1 && t >= a.at[a.index + 1]) a.index++;
            if (Double.isNaN(t) || a.index == a.text.length - 1) {
                active.remove(i);
                continue;
            }
            if (shown == null || a.priority > shown.priority || a.priority == shown.priority && a.order > shown.order) {
                shown = a;
            }
        }
    }

    /** The line shown now; null for none. */
    @Nullable
    public String text() {
        return shown == null || shown.text[shown.index].isEmpty() ? null : shown.text[shown.index];
    }

    /** Who says the line shown; null for none, or no line. */
    @Nullable
    public String speaker() {
        return text() == null ? null : shown.speaker;
    }

    /** The sound whose line is shown; 0 for none. */
    public long id() {
        return shown == null ? 0 : shown.id;
    }

    /** How many subtitles are queued and not yet ended. */
    public int count() {
        return active.size();
    }
}
