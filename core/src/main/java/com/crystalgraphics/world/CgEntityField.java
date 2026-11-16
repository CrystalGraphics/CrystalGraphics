package com.crystalgraphics.world;

import java.util.Objects;

import javax.annotation.Nullable;

import com.crystalgraphics.object.CgValueType;

/**
 * A value the engine derives for an entity each frame, on each side, from what the host says of it: its speed, whether
 * it is on the ground, how its arms are posed. Never sent and never saved: both sides know it. The engine's are
 * {@link CgEntityFields}' constants ({@code entity.speed}); a mod derives its own with {@link CgEntityFields#register}.
 * Data an entity stores (a mod's mana) is a field of a networked type instead ({@link CgEntityNetField}).
 *
 * <pre>{@code
 * float speed = CgEntityFields.SPEED.read(entity.state(), null);
 *
 * public static final CgEntityField FLIGHT_GEAR = CgEntityFields.register("mymod:flight_gear", CgValueType.INT,
 *         (state, entity) -> Flight.gear(entity));            // read off the loader's entity on each side
 * }</pre>
 */
public final class CgEntityField implements CgEntityBinding {

    /**
     * Reads its value now, as a float (a bool 0 or 1, an int exactly up to 2²⁴): from the entity's state, or from the
     * host's own entity (the loader's, null where this side has none). Allocates nothing.
     */
    @FunctionalInterface
    public interface Reader {
        float read(CgEntityState state, @Nullable Object entity);
    }

    private final String name;
    private final CgValueType type;
    private final Reader reader;

    CgEntityField(String name, CgValueType type, Reader reader) {
        this.name = Objects.requireNonNull(name, "name");
        if (!CgEntityFields.isScalar(type)) throw new IllegalArgumentException(name + ": a field is a float, an int or a bool, not " + type);
        this.type = type;
        this.reader = Objects.requireNonNull(reader, "reader");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CgValueType type() {
        return type;
    }

    /** Its value in {@code state}, the host's entity {@code entity} (null where this side has none). */
    public float read(CgEntityState state, @Nullable Object entity) {
        return reader.read(state, entity);
    }

    @Override
    public String toString() {
        return name + ":" + type;
    }
}
