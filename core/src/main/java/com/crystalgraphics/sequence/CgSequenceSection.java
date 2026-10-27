package com.crystalgraphics.sequence;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.easing.CgEasing;

/**
 * A section of a track, compiled: Unreal's {@code UMovieSceneSection}, its range, row, priority, blend, ease and
 * completion, its channels as columns, and its kind's own fields as written.
 *
 * <pre>{@code
 * for (CgSequenceSection s : track.sections()) {
 *     boolean open = s.to() == CgSequenceSection.OPEN_END;        // range: [0, null]
 *     CgSequenceChannel x = s.channel("translation.x");            // null if the section keys none
 *     Object age = s.fields().get("age");                          // a system section's age mode, as written
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A range is {@code [from, to)} in ticks, Unreal's discrete range; an open end is {@link #OPEN_START} or
 *       {@link #OPEN_END}.</li>
 *   <li>A triggered section ({@link #trigger()} set) has its range relative to its event, and is never in the
 *       evaluation field: it starts when its event fires.</li>
 *   <li>{@link #blend()} is null where the kind does not blend; a blending kind's section without one is
 *       {@code absolute}, as Unreal gives a new section the track's first supported blend type.</li>
 * </ul>
 *
 * @param id              its id
 * @param index           its index in its track
 * @param from            the first tick it covers, or {@link #OPEN_START}
 * @param to              the tick after its last, or {@link #OPEN_END}
 * @param trigger         the event that starts it, or null for one placed on the timeline
 * @param condition       what must hold for it to play, or null
 * @param row             its row (Unreal's {@code RowIndex}): rows evaluate together
 * @param overlapPriority within a row, the higher wins where sections overlap
 * @param blend           how its values combine with others', or null
 * @param ease            its ease in and out, or null
 * @param completion      what its values do once it ends
 * @param preRoll         ticks before its range it runs hidden
 * @param postRoll        ticks after its range it keeps running
 * @param active          false: compiled, never evaluated
 * @param channels        its channels, in file order
 * @param fields          its kind's own keys that are not channels, as written
 */
public record CgSequenceSection(String id, int index, int from, int to, @Nullable String trigger,
                                @Nullable CgSequenceCondition condition, int row, int overlapPriority,
                                @Nullable Blend blend, @Nullable Ease ease, Completion completion, int preRoll,
                                int postRoll, boolean active, List<CgSequenceChannel> channels, Map<?, ?> fields) {

    /** An open start. */
    public static final int OPEN_START = Integer.MIN_VALUE;
    /** An open end. */
    public static final int OPEN_END = Integer.MAX_VALUE;

    /** {@code EMovieSceneBlendType}. */
    public enum Blend { ABSOLUTE, ADDITIVE, RELATIVE, ADDITIVE_FROM_BASE, OVERRIDE }

    /** {@code EMovieSceneCompletionMode}: absent from the file, the project's default. */
    public enum Completion { KEEP_STATE, RESTORE_STATE, PROJECT_DEFAULT }

    /**
     * {@code FMovieSceneEasingSettings}: ticks of ease in and out and their functions, or Unreal's automatic easing
     * from overlaps. A function the file leaves out is Unreal's default, cubic in-out.
     */
    public record Ease(int in, int out, CgEasing inFunction, CgEasing outFunction, boolean auto) {
    }

    /**
     * Its weight at {@code tick}, what it blends with: its ease in and out, multiplied, times its {@code weight}
     * channel. Unreal's {@code UMovieSceneSection::EvaluateEasing} times the manual weight. 1 with neither.
     */
    public float weight(float tick) {
        double w = 1;
        if (ease != null) {
            if (ease.in() > 0 && from != OPEN_START) {
                double t = (tick - from) / ease.in();
                w *= t < 0 ? 0 : t > 1 ? 1 : ease.inFunction().ease(t);
            }
            if (ease.out() > 0 && to != OPEN_END) {
                double t = (tick - to + ease.out()) / (double) ease.out();
                w *= t < 0 ? 1 : t > 1 ? 0 : 1 - ease.outFunction().ease(t);
            }
        }
        CgSequenceChannel weight = channel("weight");
        if (weight != null && weight.hasValue()) w *= weight.eval(tick);
        return (float) w;
    }

    /** The channel at {@code name} ({@code translation.x}, {@code scalars.near}), or null. */
    @Nullable
    public CgSequenceChannel channel(String name) {
        for (CgSequenceChannel c : channels) if (c.name().equals(name)) return c;
        return null;
    }

    /** Whether {@code tick} lies in its range. */
    public boolean contains(int tick) {
        return tick >= from && (to == OPEN_END || tick < to);
    }
}
