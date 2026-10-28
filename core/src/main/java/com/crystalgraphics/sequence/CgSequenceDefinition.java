package com.crystalgraphics.sequence;

import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetCompiler;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.object.value.CgUnits;
import com.crystalgraphics.sequence.time.CgFrameRate;

/**
 * A sequence asset compiled once and shared by every player of it: Unreal's {@code UMovieScene} with its compiled data
 * ({@code FMovieSceneCompiledDataEntry}). Its rates and playback range; its roles, locators and spawnables; its tracks
 * with their sections and channels as columns, every binding resolved to an index; its evaluation field; the montage's
 * composite sections; the sections events start; and the subsequences it plays.
 *
 * <pre>{@code
 * CgSequenceDefinition.register();                                      // the pipeline compiles sequences so
 * CgSequenceDefinition s = CgSequenceDefinition.compile(asset);         // or directly, from a resolved asset
 * CgSequencePlaybackPosition clock = CgSequencePlaybackPosition.of(s);  // its clock: display rate in, ticks out
 *
 * int caster = s.role("caster");                                        // the contract code binds, by name
 * CompositeSection loop = s.compositeSection("loop");                   // a montage's section and its next
 * int segment = s.field().segmentAt(tick);                              // what is active at a tick
 * }</pre>
 *
 * <ul>
 *   <li>Fails at load, naming the sequence and the path: the first validation error; a range that is not two
 *       increasing ticks; a rate {@link CgFrameRate#of} refuses; a binding, a role, a composite section's {@code next}
 *       or a subsequence that does not resolve; a channel whose keys go backwards; a conditional section in an
 *       {@code rpg} event track, which a server would never hear; subsequences more than {@link #MAX_DEPTH} deep.</li>
 *   <li>Nothing is defaulted but what Unreal defaults: {@code evaluation} (sub-frames), a section's row, priority and
 *       completion (the project's default).</li>
 *   <li>A channel's values are in the host's units by their quantity; a rotation stays in {@link #units()}'
 *       convention.</li>
 * </ul>
 *
 * @param id                the asset's id
 * @param tickResolution    ticks a second: what keys and ranges are counted in
 * @param displayRate       frames a second the editor shows, and the rate a frame-locked sequence steps at
 * @param start             the playback range's first tick
 * @param end               its last tick
 * @param evaluation        how a frame is evaluated
 * @param units             what its values were written in
 * @param roles             what plays it, bound at play by name
 * @param locators          the points it owns
 * @param spawnables        the objects it makes and owns
 * @param compositeSections the montage's named ranges, in file order
 * @param tracks            its tracks, in file order
 * @param field             what is active over which ticks
 * @param triggered         the sections each event starts, by event name
 * @param children          the subsequences its subsequence sections play
 * @param depth             how many levels of subsequences lie under it: 0 for none
 */
public record CgSequenceDefinition(String id, CgFrameRate tickResolution, CgFrameRate displayRate, int start, int end,
                                   Evaluation evaluation, CgUnits units, List<Role> roles, List<Locator> locators,
                                   List<Spawnable> spawnables, List<CompositeSection> compositeSections,
                                   List<CgSequenceTrack> tracks, CgSequenceField field,
                                   Map<String, List<SectionRef>> triggered, List<Child> children, int depth) {

    /** How deep subsequences nest: a deeper one fails at load. */
    public static final int MAX_DEPTH = 4;

    /** {@code EMovieSceneEvaluationType}. */
    public enum Evaluation { SUB_FRAMES, FRAME_LOCKED }

    /**
     * A role a player binds: Unreal's possessable, by {@code name}.
     *
     * @param character which roster character, where the source fixes it; null otherwise
     * @param seed      whether the sequence draws its seed from this role
     * @param skip      a missing role skips what binds it, rather than failing the play
     */
    public record Role(String id, String name, String kind, @Nullable String character, boolean seed, boolean skip) {
    }

    /** A point the sequence owns, placed under {@code parent}; {@code tree} holds its rest transform as written. */
    public record Locator(String id, @Nullable String name, CgSequenceBinding parent, Map<?, ?> tree) {
    }

    /**
     * An object the sequence makes and owns: Unreal's spawnable.
     *
     * @param kind       its one kind key: {@code system}, {@code prefab}, {@code camera}…
     * @param at         where it is placed; {@link CgSequenceBinding#NONE} where it says nothing
     * @param follow     whether it follows {@code at} every frame, or is placed and left
     * @param tree       its keys as written
     * @param definition the asset it names, compiled (a {@code CgVfxPrefab}, a system's definition); null where it
     *                   names none or writes it inline
     */
    public record Spawnable(String id, @Nullable String name, String kind, CgSequenceBinding at, boolean follow,
                            Map<?, ?> tree, @Nullable Object definition) {
    }

    /** A montage's named range, {@code [from, to)}, and the section it plays next: an index, or -1 for none. */
    public record CompositeSection(String id, String name, int from, int to, int next) {
    }

    /** A section by its track's index and its own. */
    public record SectionRef(int track, int section) {
    }

    /**
     * A subsequence a section plays: its definition, and for each of its roles the role of this sequence that fills
     * it, or -1 for none.
     */
    public record Child(int track, int section, CgSequenceDefinition definition, int[] roles) {
    }

    /** The sequence kind's compiler. */
    public static final CgAssetCompiler<CgSequenceDefinition> COMPILER = CgSequenceCompiler::compile;

    /** Sets {@link #COMPILER} as the sequence kind's. */
    public static void register() {
        CgAssetCompiler.register(CgSequenceKinds.SEQUENCE, COMPILER);
    }

    /** {@code asset}, validated and compiled, its values in the host's units and holding no subsequence. */
    public static CgSequenceDefinition compile(CgAssetData asset) {
        return CgSequenceCompiler.compile(asset, (kind, id) -> null);
    }

    /** {@code ticks} as seconds. */
    public double seconds(int ticks) {
        return (double) ticks * tickResolution.denominator() / tickResolution.numerator();
    }

    /** The index of the role named {@code name}, or -1. */
    public int role(String name) {
        for (int i = 0; i < roles.size(); i++) if (roles.get(i).name().equals(name)) return i;
        return -1;
    }

    /** The composite section named {@code name}, or null. */
    @Nullable
    public CompositeSection compositeSection(String name) {
        for (CompositeSection c : compositeSections) if (c.name().equals(name)) return c;
        return null;
    }
}
