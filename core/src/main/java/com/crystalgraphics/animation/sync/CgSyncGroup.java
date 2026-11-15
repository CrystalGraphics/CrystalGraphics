package com.crystalgraphics.animation.sync;

import java.util.Objects;

/**
 * Which sync group a player joins, and as what: Unreal's {@code FAnimationGroupReference}. Players in one group move
 * together, so a walk and a run blended keep their feet in step.
 *
 * <pre>{@code
 * CgSyncGroup.of("feet");                                     // leads while heaviest
 * CgSyncGroup.of("feet", CgAnimGroupRole.ALWAYS_FOLLOWER);
 * }</pre>
 *
 * <ul>
 *   <li>Groups are by name within one instance: two graphs never share one.</li>
 *   <li>Players sync by their clips' sync markers where every marker-bearing member has the leader's markers; else by
 *       length ratio, each at the same fraction of its clip.</li>
 * </ul>
 *
 * @param name the group's name
 * @param role how the player takes part
 */
public record CgSyncGroup(String name, CgAnimGroupRole role) {

    public CgSyncGroup {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(role, "role");
        if (name.isEmpty()) throw new IllegalArgumentException("a sync group is named");
    }

    /** {@code name}, as a possible leader. */
    public static CgSyncGroup of(String name) {
        return new CgSyncGroup(name, CgAnimGroupRole.CAN_BE_LEADER);
    }

    public static CgSyncGroup of(String name, CgAnimGroupRole role) {
        return new CgSyncGroup(name, role);
    }
}
