package com.crystalgraphics.sequence;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.CgAnimInstance;
import com.crystalgraphics.api.animation.CgAnimSequence;

/**
 * A target a sequence's {@code animation} track plays clips on: its posed mesh, and its clips by the ids a sequence
 * names. Unreal's skeletal mesh component under a Sequencer animation track, the clips played in its montage slot
 * ({@link CgAnimInstance#slot}) over whatever it plays itself. A role's provider implements it beside
 * {@link CgSequenceRole}.
 *
 * <pre>{@code
 * final class Fighter implements CgSequenceRole, CgSequenceSkeletal {
 *     private final CgAnimInstance body;
 *     private final Map<String, CgAnimSequence> clips;      // by the last segment of each clip's id
 *
 *     @Override public CgAnimInstance instance() { return body; }
 *     @Override public CgAnimSequence clip(String id) { return clips.get(id.substring(id.lastIndexOf('/') + 1)); }
 *     ...
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #clip} is asked once a section, at bind: null fails the play, naming the clip.</li>
 *   <li>A role that is not one fails the play at bind when a sequence animates it.</li>
 * </ul>
 */
public interface CgSequenceSkeletal extends CgSequenceTarget {

    /** The instance its clips play on. */
    CgAnimInstance instance();

    /** The clip a sequence names {@code id} ({@code kakarot:Art/ANM/cpl/C001A/BTL/SKL/C001A_S_SKL001_Bgn_01ST}), or null. */
    @Nullable
    CgAnimSequence clip(String id);
}
