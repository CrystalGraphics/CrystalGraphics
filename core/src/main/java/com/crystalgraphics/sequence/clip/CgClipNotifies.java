package com.crystalgraphics.sequence.clip;

import javax.annotation.Nullable;

import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.load.CgAssetRef;
import com.crystalgraphics.asset.load.CgLoadPriority;
import com.crystalgraphics.asset.load.CgVfxAssets;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequenceTarget;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * A clip's notifies played on the library's own playback, outside any sequence: the role's provider starts them with
 * the clip, hands them the clip's time each frame, and stops them with it, as Unreal's anim instance fires a clip's
 * notifies from its own playback ({@code UAnimInstance::TriggerAnimNotifies}) and ends open notify states however a
 * montage ends. Inside a sequence, an {@code animation} section plays the same notify sequence by itself.
 *
 * <pre>{@code
 * // at load, for each clip the character may play
 * CgClipNotifies.request("rpgcore:core/styles/default/melee/melee_6");
 *
 * // when the library starts the clip on a role (client)
 * CgSequencePlayer notifies = CgClipNotifies.start(sequencer, role, "rpgcore:core/styles/default/melee/melee_6");
 * // each frame, from the library's own playback
 * if (notifies != null) notifies.drive(clipSeconds, loopedSinceLastFrame);
 * // when the clip stops: finished, interrupted or blended out
 * if (notifies != null) notifies.stop();
 * }</pre>
 *
 * <ul>
 *   <li>A clip with no markers, or whose notify sequence is not loaded yet, starts nothing: {@link #start} answers
 *       null. Request it ahead: nothing loads at a play.</li>
 *   <li>Presentation only, on the client: each viewer drives its own playback of the clip, and nothing is sent.</li>
 *   <li>{@code looped} must say when the clip passed its end, or a span over its end never ends and begins again.</li>
 * </ul>
 */
public final class CgClipNotifies {

    private CgClipNotifies() {
    }

    /** Clip {@code clip}'s notify sequence, by the clip's id: what a GeckoLib clip's markers became at load. */
    public static CgAssetRef<CgSequenceDefinition> ref(String clip) {
        return CgVfxAssets.ref(CgSequenceKinds.SEQUENCE, clip);
    }

    /** Starts loading clip {@code clip}'s notify sequence, so {@link #start} finds it ready. Its loop's thread. */
    public static void request(String clip) {
        ref(clip).request(CgLoadPriority.MOVE);
    }

    /**
     * Clip {@code clip}'s notifies on {@code role}, played on the clock the caller drives; null where the clip has no
     * notify sequence ready.
     */
    @Nullable
    public static CgSequencePlayer start(CgSequencer sequencer, CgSequenceTarget role, String clip) {
        CgAssetRef<CgSequenceDefinition> ref = ref(clip);
        CgSequenceDefinition notifies = ref.ready() ? ref.loaded() : null;
        return notifies == null ? null : sequencer.playClip(notifies, role, clip);
    }
}
