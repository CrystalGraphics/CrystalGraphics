package com.crystalgraphics.sequence.sound;

import com.crystalgraphics.asset.load.CgAssetPreparer;
import com.crystalgraphics.audio.CgAudioEngine;
import com.crystalgraphics.audio.CgSoundRef;
import com.crystalgraphics.job.CgJob;
import com.crystalgraphics.job.CgJobLane;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;
import com.crystalgraphics.sequence.definition.CgSequenceSection;
import com.crystalgraphics.sequence.definition.CgSequenceTrack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * A sequence asset's sounds read and decoded on a worker before it is {@code READY}, so its first play decodes nothing
 * on the frame: Unreal loads a level sequence's sound waves with it. Every sound track's sections, each pick's sounds.
 *
 * <pre>{@code
 * CgVfxAssets.instance().preparer(CgSequenceKinds.SEQUENCE,
 *         new CgSequenceSoundPreparer(() -> CgWorld.client().subsystem(CgAudioEngine.class)));
 * }</pre>
 *
 * <ul>
 *   <li>With no engine when its step runs (a host playing no sound) it is done at once: sounds then load at their
 *       first play.</li>
 *   <li>A section whose sound does not compile is left to the evaluator, which skips it and says why.</li>
 * </ul>
 */
public final class CgSequenceSoundPreparer implements CgAssetPreparer {

    /** The engine sounds load into; it answers null where none runs. */
    private final Supplier<CgAudioEngine> audio;

    public CgSequenceSoundPreparer(Supplier<CgAudioEngine> audio) {
        this.audio = audio;
    }

    @Override
    public void plan(Object definition, Plan plan) {
        if (!(definition instanceof CgSequenceDefinition sequence)) return;
        List<CgSoundRef> sounds = new ArrayList<>();
        for (CgSequenceTrack track : sequence.tracks()) {
            if (!track.kind().equals("sound")) continue;
            for (CgSequenceSection section : track.sections()) {
                try {
                    sounds.add(CgSoundRef.of(section.fields().get("sound"), sequence.id() + ": " + track.id()));
                } catch (IllegalArgumentException ignored) {
                    // the evaluator skips it at bind, naming why
                }
            }
        }
        if (sounds.isEmpty()) return;
        CompletableFuture<?>[] loading = new CompletableFuture<?>[1];
        plan.warm("sounds of " + sequence.id(), () -> {
            if (loading[0] == null) {
                CgAudioEngine engine = audio.get();
                if (engine == null) return true;
                loading[0] = CompletableFuture.runAsync(() -> {
                    for (CgSoundRef sound : sounds) engine.preload(sound);
                }, CgJob.executor(CgJobLane.LATENCY));
            }
            return loading[0].isDone();
        });
    }
}
