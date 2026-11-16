package com.crystalgraphics.animation.retarget;

import com.crystalgraphics.api.animation.CgAnimSequence;
import com.crystalgraphics.asset.load.CgAssetPreparer;
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
 * <pre>{@code
 * // baked as the role's asset is prepared: READY once every clip it plays is baked
 * assets.preparer(MyKinds.FIGHTER, (definition, plan) -> clips.plan(plan, ((Fighter) definition).moveClips()));
 *
 * // a sequence's animation section asks its role for its clip at bind: own, retargeted, or the play fails naming it
 * @Override public CgAnimSequence clip(String id) { return clips.find(id.substring(id.lastIndexOf('/') + 1)); }
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

    /**
     * A prepare step baking {@code names} on workers: started when the plan reaches it, done once every clip is baked,
     * so the asset is READY with nothing left to bake in play.
     */
    public void plan(CgAssetPreparer.Plan plan, List<String> names) {
        List<String> copy = List.copyOf(names);
        CgJob<?>[] job = {null};
        plan.warm("retarget " + copy.size() + " clips", () -> {
            if (job[0] == null) job[0] = CgJob.run(() -> bakeAll(copy));
            return job[0].isDone();
        });
    }

    /** Bakes every name of {@code names} the character lacks, across workers; returns once all are baked. */
    public void bakeAll(List<String> names) {
        CgJob.forEach(names.size(), i -> {
            String name = names.get(i);
            if (own.apply(name) != null) return;
            CgAnimSequence theirs = source.apply(name);
            if (theirs == null) return;
            CgRetargetProcessor mine = processor.copy();
            CgRetargetBake.cached(mine, theirs);
        });
    }
}
