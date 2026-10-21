package com.crystalgraphics.net.object;

import com.crystalgraphics.world.CgCharacterClock;

import java.util.function.LongSupplier;

/**
 * Replicates a {@link CgCharacterClock} as a per-holder net object, so every client that tracks a character computes
 * its time as the server does. Only a freeze or a rate change is sent, never a tick: the clock is a function of the
 * level's tick.
 *
 * <pre>{@code
 * // client: installed by CgVfx.install with the level's tick (CgHostEnvironment.gameTime): nothing to call
 *
 * // server, once a game tick, after the character's own advance()
 * if (clock.advance()) runStateTick();
 * CgCharacterClockNet.publish(entity, clock, level.getGameTime());
 *
 * // client: effects following the entity find CgCharacterClock.of(entityId) by themselves
 * }</pre>
 *
 * <ul>
 *   <li>{@code publish} sends only when the clock was frozen, extended or re-rated since the last one.</li>
 *   <li>A client hearing a freeze late sees its time step back; {@link CgCharacterClock#now} holds until it passes.</li>
 * </ul>
 */
public final class CgCharacterClockNet {

    private static final CgNetField<Long> LEVEL_TICK = CgNetField.ofLong("level_tick");
    private static final CgNetField<Long> TICKS = CgNetField.ofLong("ticks");
    private static final CgNetField<Double> SECONDS = CgNetField.ofDouble("seconds");
    private static final CgNetField<Integer> FROZEN = CgNetField.ofInt("frozen");
    private static final CgNetField<Float> RATE = CgNetField.ofFloat("rate");

    /** The character clock's net type: one object per tracked character. */
    public static final CgNetType TYPE = CgNetType.define("crystalgraphics:character_clock")
            .fields(LEVEL_TICK, TICKS, SECONDS, FROZEN, RATE).perHolder().visibleToTrackers().build();

    private static volatile LongSupplier clientLevelTick = () -> 0L;

    private CgCharacterClockNet() {
    }

    /**
     * Makes each client's copy of the type drive {@link CgCharacterClock#of} from its level's tick. Client side, once:
     * {@code CgVfx.install} does it; a test or a host without the VFX engine calls it.
     */
    public static void install(LongSupplier levelTick) {
        clientLevelTick = levelTick;
        TYPE.onClient(obj -> new CgNetBehaviour() {
            @Override
            public void spawned(CgNetObject o, float age) {
                apply(o);
            }

            @Override
            public void received(CgNetObject o) {
                apply(o);
            }

            @Override
            public void tick(CgNetObject o) {
                CgCharacterClock.of(o.entityId()).sync(clientLevelTick.getAsLong());
            }

            @Override
            public void despawned(CgNetObject o, boolean killed) {
                CgCharacterClock.forget(o.entityId());
            }
        });
    }

    private static void apply(CgNetObject o) {
        CgCharacterClock clock = CgCharacterClock.of(o.entityId());
        clock.segment(o.getLong(LEVEL_TICK), o.getLong(TICKS), o.getDouble(SECONDS), o.getInt(FROZEN), o.getFloat(RATE));
        clock.sync(clientLevelTick.getAsLong());
    }

    /**
     * Sends {@code holder}'s clock to the players tracking it, if it moved since the last send. Server thread, once a
     * game tick after {@link CgCharacterClock#advance}; {@code levelTick} is the server level's game time.
     */
    public static void publish(Object holder, CgCharacterClock clock, long levelTick) {
        if (!clock.unpublished()) return;
        clock.published();
        TYPE.of(holder).setLong(LEVEL_TICK, levelTick).setLong(TICKS, clock.ticks()).setDouble(SECONDS, clock.seconds())
                .setInt(FROZEN, clock.frozenTicks()).setFloat(RATE, clock.rate());
    }
}
