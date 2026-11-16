package com.crystalgraphics.world;

import java.util.Objects;
import java.util.function.Function;

/**
 * Something that happened to an entity this frame, with what it carries: a jump, a landing, a swing and its hand. The
 * engine's are {@link CgEntityEvents}' constants, detected on each side from what the host says of the entity, so a
 * client sees them for every entity it draws with nothing sent. Shaped as a networked object's event
 * ({@code CgNetEvent}): a name and a payload type.
 *
 * <pre>{@code
 * entity.on(CgEntityEvents.LANDED, (e, none) -> {               // an actor's entity, each time it happens
 *     if (e.state().landHeight() > 3) body.anim().montages().play(HARD_LANDING);
 * });
 * entity.on(CgEntityEvents.SWING_STARTED, (e, hand) -> slash(hand));
 *
 * if (state.fired(CgEntityEvents.JUMPED)) ...                   // or asked of a state, in the frame it happened
 * CgHand hand = CgEntityEvents.SWING_STARTED.payload(state);
 * }</pre>
 *
 * <ul>
 *   <li>An event lasts one {@link CgEntityState#update}: read it in the frame it happens.</li>
 *   <li>A payload is read off the state, so it allocates nothing: {@code null} for {@code Void}, an enum otherwise.
 *       What an event carries beyond that is the state's, read in the same frame ({@link CgEntityState#vehicle}
 *       after {@link CgEntityEvents#MOUNTED}).</li>
 *   <li>Something only the server knows (a mod's ability fired, a hit's damage) is a {@code CgNetEvent} on the mod's
 *       own networked type, sent by its server.</li>
 * </ul>
 *
 * @param <P> what it carries: {@link Void} for nothing, {@link CgHand} for a swing or a use
 */
public final class CgEntityEvent<P> {

    private final String name;
    /** Its bit in the state's events. */
    final int bit;
    private final Function<CgEntityState, P> payload;

    CgEntityEvent(String name, int bit, Function<CgEntityState, P> payload) {
        this.name = Objects.requireNonNull(name, "name");
        this.bit = bit;
        this.payload = Objects.requireNonNull(payload, "payload");
    }

    /** Its name, as a graph's {@code entity:} block names the field true in its frame: {@code entity.jumped}. */
    public String name() {
        return name;
    }

    /** What it carried in {@code state}'s last update; meaningful only where {@link CgEntityState#fired} says it fired. */
    public P payload(CgEntityState state) {
        return payload.apply(state);
    }

    @Override
    public String toString() {
        return name;
    }
}
