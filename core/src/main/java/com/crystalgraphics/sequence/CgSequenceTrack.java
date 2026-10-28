package com.crystalgraphics.sequence;

import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.crystalgraphics.api.material.CgParameterCollection;

/**
 * A track of a sequence, compiled: Unreal's {@code UMovieSceneTrack}, its kind, what it binds (resolved), its
 * track-level fields, and its sections.
 *
 * <pre>{@code
 * for (CgSequenceTrack t : definition.tracks()) {
 *     if (t.muted()) continue;
 *     if (t.kind().equals("parameter")) bindParameter(t.binding(), t.parameter());   // user.<name>
 *     for (CgSequenceSection s : t.sections()) ...
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A muted track compiles but is never in the evaluation field.</li>
 *   <li>{@link #population()} is how its sections fill the field where they overlap, as Unreal's track class fills
 *       its evaluation tree.</li>
 * </ul>
 *
 * @param id            its id
 * @param index         its index in the sequence
 * @param kind          its kind: {@code transform}, {@code system}, {@code event}…
 * @param binding       what it binds
 * @param muted         compiled, never evaluated
 * @param side          an event track's listener side, {@code rpg} or {@code ours}; null for any other
 * @param handle        a system track's one emitter, or null for the whole system
 * @param parameter     a parameter track's {@code user.<name>}, or null
 * @param property      a property track's property name, or null
 * @param collection    a global track's parameter collection id, or null
 * @param globals       that collection, compiled; null where compiled without its dependencies
 * @param fireForwards  an event track's: fires as play crosses a key forwards
 * @param fireBackwards an event track's: fires as play crosses a key backwards
 * @param population    how its sections fill the evaluation field
 * @param sections      its sections, in file order
 * @param fields        any other track keys, as written
 */
public record CgSequenceTrack(String id, int index, String kind, CgSequenceBinding binding, boolean muted,
                              @Nullable String side, @Nullable String handle, @Nullable String parameter,
                              @Nullable String property, @Nullable String collection,
                              @Nullable CgParameterCollection globals, boolean fireForwards,
                              boolean fireBackwards, Population population, List<CgSequenceSection> sections,
                              Map<?, ?> fields) {

    /**
     * How a track's sections fill the evaluation field: Unreal's {@code ETreePopulationMode}, and a track that
     * evaluates over all time.
     */
    public enum Population {
        /**
         * {@code HighPassPerRow}, the default: sections by row, then overlap priority, highest first; a section that
         * blends is added over its whole range, one that does not only where its row is still empty
         * ({@code FEvaluationTreePopulationRules::HighPassCustomPerRow}).
         */
        HIGH_PASS_PER_ROW,
        /** {@code Blended}: every active section over its whole range (a material track, a subsequence track). */
        BLENDED,
        /**
         * The track over all time, its sections read by its evaluator: Niagara's system track
         * ({@code UMovieSceneNiagaraSystemTrack::PopulateEvaluationTree}).
         */
        WHOLE_TRACK
    }

    /** Kinds whose sections blend in every channel type: Unreal's tracks with supported blend types. */
    private static final Set<String> BLENDING = Set.of("transform", "parameter", "material", "global", "animation",
            "morph", "fade", "time_dilation", "camera_cut", "sound", "subsequence");

    /** {@code kind}'s population. */
    public static Population populationOf(String kind) {
        return switch (kind) {
            case "material", "subsequence" -> Population.BLENDED;
            case "system", "notify", "event" -> Population.WHOLE_TRACK;
            default -> Population.HIGH_PASS_PER_ROW;
        };
    }

    /**
     * Whether a section of {@code kind} with these channels carries a blend type. A property track blends where its
     * channels are floats (a float, vector or colour property) and not where they are held.
     */
    public static boolean blends(String kind, List<CgSequenceChannel> channels) {
        if (BLENDING.contains(kind)) return true;
        if (!kind.equals("property")) return false;
        for (CgSequenceChannel c : channels) if (c.type() != CgSequenceChannel.Type.FLOAT && !c.name().equals("weight")) return false;
        return true;
    }
}
