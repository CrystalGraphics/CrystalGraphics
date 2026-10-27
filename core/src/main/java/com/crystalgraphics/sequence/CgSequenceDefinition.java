package com.crystalgraphics.sequence;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.object.validate.CgIssue;
import com.crystalgraphics.object.validate.CgValidation;
import com.crystalgraphics.sequence.time.CgFrameRate;

/**
 * A sequence asset compiled: its frame rates, playback range, evaluation, roles and tracks, as Unreal's
 * {@code UMovieScene} holds them once loaded. What a sequence player evaluates.
 *
 * <pre>{@code
 * CgSequenceDefinition.register();                                    // the pipeline compiles sequences so
 * CgSequenceDefinition s = CgSequenceDefinition.compile(asset);       // or directly, from a resolved asset
 * double seconds = s.seconds(s.end());                                 // ticks to seconds
 * CgSequencePlaybackPosition clock = CgSequencePlaybackPosition.of(s);  // its clock: display rate in, ticks out
 * }</pre>
 *
 * <ul>
 *   <li>Fails naming the sequence and the path: the first validation error, or a {@code range} that is not two
 *       increasing ticks, or a rate {@link CgFrameRate#of} refuses. Nothing is defaulted but {@code evaluation}, whose
 *       absence is Unreal's sub-frames.</li>
 *   <li>Tracks keep their resolved trees; their sections and channels compile in later steps.</li>
 * </ul>
 *
 * @param id             the asset's id
 * @param tickResolution ticks a second: what keys and ranges are counted in
 * @param displayRate    frames a second the editor shows, and the rate a frame-locked sequence steps at
 * @param start          the playback range's first tick
 * @param end            its last tick
 * @param evaluation     how a frame is evaluated
 * @param roles          what plays it, by id
 * @param tracks         its tracks, in order
 */
public record CgSequenceDefinition(String id, CgFrameRate tickResolution, CgFrameRate displayRate, int start, int end,
                                   Evaluation evaluation, List<Role> roles, List<Track> tracks) {

    /** {@code EMovieSceneEvaluationType}. */
    public enum Evaluation { SUB_FRAMES, FRAME_LOCKED }

    /** A role a player binds: its id, name and kind. */
    public record Role(String id, String name, String kind) {
    }

    /** A track: its id, its kind (the dispatch key) and its resolved tree. */
    public record Track(String id, String kind, Map<?, ?> tree) {
    }

    /** The sequence kind's compiler. */
    public static final CgAssetCompiler<CgSequenceDefinition> COMPILER = (asset, deps) -> compile(asset);

    /** Sets {@link #COMPILER} as the sequence kind's. */
    public static void register() {
        CgAssetCompiler.register(CgSequenceKinds.SEQUENCE, COMPILER);
    }

    /** {@code ticks} as seconds. */
    public double seconds(int ticks) {
        return (double) ticks * tickResolution.denominator() / tickResolution.numerator();
    }

    /** {@code asset}, validated and compiled. */
    public static CgSequenceDefinition compile(CgAssetData asset) {
        String id = asset.id();
        CgValidation v = asset.validate(CgValidation.Usecase.LOAD);
        if (!v.valid()) {
            for (CgIssue issue : v.issues()) {
                if (issue.severity() == CgIssue.Severity.ERROR) throw new CgAssetException(id + ": " + issue.path() + ": " + issue.message());
            }
        }
        Map<Object, Object> tree = asset.resolved();
        CgFrameRate tick = rate(id, tree, "tick_resolution"), display = rate(id, tree, "display_rate");
        if (!(tree.get("range") instanceof List<?> range) || range.size() != 2
                || !(range.get(0) instanceof Number a) || !(range.get(1) instanceof Number b) || b.intValue() < a.intValue()) {
            throw new CgAssetException(id + ": range: two increasing ticks, not " + tree.get("range"));
        }
        Evaluation evaluation = "frame_locked".equals(tree.get("evaluation")) ? Evaluation.FRAME_LOCKED : Evaluation.SUB_FRAMES;
        List<Role> roles = new ArrayList<>();
        if (tree.get("roles") instanceof List<?> rs) {
            for (Object o : rs) {
                Map<?, ?> r = (Map<?, ?>) o;
                roles.add(new Role(String.valueOf(r.get("id")), String.valueOf(r.get("name")), String.valueOf(r.get("kind"))));
            }
        }
        List<Track> tracks = new ArrayList<>();
        if (tree.get("tracks") instanceof List<?> ts) {
            for (Object o : ts) {
                Map<?, ?> t = (Map<?, ?>) o;
                if (!(t.get("kind") instanceof String kind)) throw new CgAssetException(id + ": tracks." + t.get("id") + ".kind is required");
                tracks.add(new Track(String.valueOf(t.get("id")), kind, t));
            }
        }
        return new CgSequenceDefinition(id, tick, display, a.intValue(), b.intValue(), evaluation,
                Collections.unmodifiableList(roles), Collections.unmodifiableList(tracks));
    }

    private static CgFrameRate rate(String id, Map<?, ?> tree, String key) {
        if (!(tree.get(key) instanceof Number n) || n.doubleValue() <= 0) throw new CgAssetException(id + ": " + key + ": a positive number, not " + tree.get(key));
        try {
            return CgFrameRate.of(n.doubleValue());
        } catch (IllegalArgumentException e) {
            throw new CgAssetException(id + ": " + key + ": " + e.getMessage());
        }
    }
}
