package com.crystalgraphics.world;

import com.crystalgraphics.object.CgValueType;

/**
 * A value of an entity something can read by name each frame: one the engine derives on each side
 * ({@link CgEntityField}, {@code entity.speed}) or one stored on the entity's networked data
 * ({@link CgEntityNetField}, {@code mymod:mana#current}). What a graph's {@code entity:} block binds its variables to.
 *
 * <pre>{@code
 * CgEntityBinding speed = CgEntityFields.SPEED;                         // derived
 * CgEntityBinding ki = CgEntityNetField.of(Mana.TYPE, Mana.CURRENT);    // stored
 * CgEntityBinding same = CgEntityFields.binding("mymod:mana#current");  // either, by name
 * }</pre>
 */
public interface CgEntityBinding {

    /** What a file names it by: {@code entity.speed}, {@code mymod:flight_gear}, {@code mymod:mana#current}. */
    String name();

    /** What it holds: {@link CgValueType#FLOAT}, {@link CgValueType#INT} or {@link CgValueType#BOOL}. */
    CgValueType type();
}
