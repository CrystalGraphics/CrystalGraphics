package com.crystalgraphics.world;

import java.util.List;

/**
 * The events the engine detects on every entity, on each side, from what the host says of it each frame: a jump, a
 * landing, a hit, a swing, a use, a mount, a death. Each is also a field a graph reads, true for its frame
 * ({@code entity.jumped}, {@link CgEntityFields#JUMPED}).
 *
 * <pre>{@code
 * entity.on(CgEntityEvents.HURT, (e, none) -> body.anim().montages().play(FLINCH));
 * entity.on(CgEntityEvents.USE_STARTED, (e, hand) -> raise(hand));
 * }</pre>
 */
public final class CgEntityEvents {

    /** It left the ground going up. */
    public static final CgEntityEvent<Void> JUMPED = new CgEntityEvent<>("entity.jumped", CgEntityState.JUMPED, s -> null);
    /** It reached the ground; how far it fell is {@link CgEntityState#landHeight}. */
    public static final CgEntityEvent<Void> LANDED = new CgEntityEvent<>("entity.landed", CgEntityState.LANDED, s -> null);
    /** It was hurt. */
    public static final CgEntityEvent<Void> HURT = new CgEntityEvent<>("entity.hurt", CgEntityState.HURT, s -> null);
    /** An arm began a swing, or began it again. */
    public static final CgEntityEvent<CgHand> SWING_STARTED = new CgEntityEvent<>("entity.swing_started",
            CgEntityState.SWING_STARTED, s -> CgHand.of(s.swingHand()));
    /** It began using what a hand holds: drawing a bow, raising a shield, eating. */
    public static final CgEntityEvent<CgHand> USE_STARTED = new CgEntityEvent<>("entity.use_started",
            CgEntityState.USE_STARTED, s -> CgHand.of(s.useHand()));
    /** It stopped using what a hand held. */
    public static final CgEntityEvent<CgHand> USE_STOPPED = new CgEntityEvent<>("entity.use_stopped",
            CgEntityState.USE_STOPPED, s -> CgHand.of(s.stoppedHand()));
    /** It got on something; what is {@link CgEntityState#vehicle}. */
    public static final CgEntityEvent<Void> MOUNTED = new CgEntityEvent<>("entity.mounted", CgEntityState.MOUNTED, s -> null);
    /** It got off what it rode. */
    public static final CgEntityEvent<Void> DISMOUNTED = new CgEntityEvent<>("entity.dismounted", CgEntityState.DISMOUNTED, s -> null);
    /** It died. */
    public static final CgEntityEvent<Void> DIED = new CgEntityEvent<>("entity.died", CgEntityState.DIED, s -> null);

    private static final List<CgEntityEvent<?>> ALL = List.of(JUMPED, LANDED, HURT, SWING_STARTED, USE_STARTED, USE_STOPPED,
            MOUNTED, DISMOUNTED, DIED);

    private CgEntityEvents() {
    }

    /** Every one, in the order above. */
    public static List<CgEntityEvent<?>> all() {
        return ALL;
    }
}
