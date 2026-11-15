package com.crystalgraphics.animation.sync;

/**
 * How a player takes part in its sync group: Unreal's {@code EAnimGroupRole}. The group's leader moves by its own time;
 * the others follow its markers, or its place through its length.
 *
 * <pre>{@code
 * CgSequencePlayerNode.of(WALK).loop().sync(CgSyncGroup.of("feet"));                                  // leads by weight
 * CgSequencePlayerNode.of(LIMP).loop().sync(CgSyncGroup.of("feet", CgAnimGroupRole.ALWAYS_FOLLOWER)); // never leads
 * }</pre>
 */
public enum CgAnimGroupRole {
    /** Leads while it is the heaviest of the group's possible leaders: its score is its weight. */
    CAN_BE_LEADER,
    /** Never leads, unless the group has only followers; then the heaviest leads. */
    ALWAYS_FOLLOWER,
    /** Always leads; of several, the last updated wins. */
    ALWAYS_LEADER,
    /** Plays unsynced while blending in; once at full weight, it can lead. */
    TRANSITION_LEADER,
    /** Plays unsynced while blending in; once at full weight, it follows. */
    TRANSITION_FOLLOWER,
    /** Always leads; where another took the lead, it plays unsynced instead of following. */
    EXCLUSIVE_ALWAYS_LEADER;

    /** {@code TestTickRecordForLeadership}'s score: weight for a possible leader, 2 for an always leader, -2 + weight for a follower. */
    float leaderScore(float weight) {
        return switch (this) {
            case CAN_BE_LEADER, TRANSITION_LEADER -> weight;
            case ALWAYS_LEADER, EXCLUSIVE_ALWAYS_LEADER -> 2f;
            case ALWAYS_FOLLOWER, TRANSITION_FOLLOWER -> -2f + weight;
        };
    }

    boolean transitional() {
        return this == TRANSITION_LEADER || this == TRANSITION_FOLLOWER;
    }
}
