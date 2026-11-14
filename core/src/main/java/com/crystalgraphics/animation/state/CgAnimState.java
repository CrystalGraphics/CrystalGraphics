package com.crystalgraphics.animation.state;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.animation.graph.CgAnimNode;

/**
 * One state of a {@link CgStateMachine}: a pose the machine plays while in it, or a conduit, a branch point that holds no
 * pose. Unreal's {@code FBakedAnimationState}.
 *
 * <pre>{@code
 * CgAnimState idle = CgAnimState.of("idle", CgSequencePlayerNode.of(IDLE).loop());
 * CgAnimState jumpStart = CgAnimState.of("jump_start", CgSequencePlayerNode.of(JUMP)).alwaysResetOnEntry();
 * CgAnimState branch = CgAnimState.conduit("branch", CgAnimRule.isTrue(GROUNDED));   // entered only while grounded
 * }</pre>
 *
 * <ul>
 *   <li>A state's pose restarts when it is entered from no weight; {@link #alwaysResetOnEntry()} restarts it on every
 *       entry, as Unreal's {@code bAlwaysResetOnEntry}.</li>
 *   <li>A conduit is passed through in the same frame: its entry rule gates every transition through it.</li>
 * </ul>
 *
 * @param name               its name, unique in its machine
 * @param pose               the pose it plays; null for a conduit
 * @param resetsOnEntry      restart its pose on every entry
 * @param entryRule          a conduit's entry rule; null for a state
 */
public record CgAnimState(String name, @Nullable CgAnimNode pose, boolean resetsOnEntry, @Nullable CgAnimRule entryRule) {

    public CgAnimState {
        Objects.requireNonNull(name, "name");
        if ((pose == null) == (entryRule == null)) throw new IllegalArgumentException(name + ": a state has a pose, a conduit an entry rule");
    }

    /** A state playing {@code pose}. */
    public static CgAnimState of(String name, CgAnimNode pose) {
        return new CgAnimState(name, Objects.requireNonNull(pose, "pose"), false, null);
    }

    /** A conduit entered only while {@code entryRule} holds. */
    public static CgAnimState conduit(String name, CgAnimRule entryRule) {
        return new CgAnimState(name, null, false, Objects.requireNonNull(entryRule, "entryRule"));
    }

    /** The same state, its pose restarted on every entry. */
    public CgAnimState alwaysResetOnEntry() {
        return new CgAnimState(name, pose, true, entryRule);
    }

    public boolean conduit() {
        return pose == null;
    }
}
