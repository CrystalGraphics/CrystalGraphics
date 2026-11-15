package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import javax.annotation.Nullable;
import java.util.List;
import java.util.function.Function;

/**
 * Which clip a character plays for a name: its own, else the source character's retargeted onto it, else none, which
 * fails naming the clip. A character's own clip always wins, so a mod's GeckoLib animation for one move replaces the
 * retargeted one.
 *
 * <pre>{@code
 * CgClipResolver clips = new CgClipResolver(playerClips::get, gokuClips::get, processor);
 * clips.bakeAll(List.of("punch", "kick", "kamehameha"));   // at load, on workers: nothing bakes during play
 * playerInstance.play(clips.resolve("punch"), false);
 * }</pre>
 *
 * <ul>
 *   <li>A name not baked by {@link #bakeAll} bakes on first {@link #resolve}, on the caller's thread, and counts
 *       {@code retarget.late}: a hitch to fix by naming it to {@code bakeAll}.</li>
 *   <li>Each worker of {@link #bakeAll} bakes through a processor of its own.</li>
 * </ul>
 */
public final class CgClipResolver {

    private final Function<String, CgAnimSequence> own, source;
    private final CgRetargeter retargeter;
    private final CgRetargetProcessor processor;

    /**
     * @param own       the character's own clips by name, null where it has none
     * @param source    the source character's clips by name, null where it has none
     * @param processor the retarget from the source's skeleton to the character's
     */
    public CgClipResolver(Function<String, CgAnimSequence> own, Function<String, CgAnimSequence> source, CgRetargetProcessor processor) {
        this.own = own;
        this.source = source;
        this.processor = processor;
        this.retargeter = processor.retargeter();
    }

    /** The clip for {@code name}: own, else retargeted, else it throws naming it. */
    public CgAnimSequence resolve(String name) {
        CgAnimSequence clip = find(name);
        if (clip == null) throw new IllegalArgumentException("no clip " + name + ": neither the character's own nor its source's");
        return clip;
    }

    /** As {@link #resolve}, null where neither has it. */
    @Nullable
    public CgAnimSequence find(String name) {
        CgAnimSequence mine = own.apply(name);
        if (mine != null) return mine;
        CgAnimSequence theirs = source.apply(name);
        if (theirs == null) return null;
        if (!CgRetargetBake.isCached(processor, theirs)) CgTrace.add(CgChannels.WORLD, "retarget.late", 1);
        return CgRetargetBake.cached(processor, theirs);
    }

    /** Bakes every name of {@code names} the character lacks, across workers; returns once all are baked. */
    public void bakeAll(List<String> names) {
        CgJob.forEach(names.size(), i -> {
            String name = names.get(i);
            if (own.apply(name) != null) return;
            CgAnimSequence theirs = source.apply(name);
            if (theirs == null) return;
            CgRetargetProcessor mine = new CgRetargetProcessor(retargeter, processor.sourceSkeleton(), processor.targetSkeleton());
            CgRetargetBake.cached(mine, theirs);
        });
    }
}
