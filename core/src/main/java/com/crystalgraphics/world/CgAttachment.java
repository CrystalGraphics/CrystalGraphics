package com.crystalgraphics.world;

import javax.annotation.Nullable;

import org.joml.Quaternionf;

/**
 * Something that rides something else: where it stands this frame, and its named points. Unreal's single
 * {@code AttachToComponent(parent, socket)}, whatever the parent: a skinned mesh's socket, a sequence's locator.
 * An effect {@code follow}s one.
 *
 * <pre>{@code
 * owner.play(new Flame(look, x, y, z).follow(kurama.attachment("hand_r")));   // a socket, as posed this frame
 * owner.play(new Charge(look, x, y, z).follow(player.placement(0)));          // a sequence's spawnable, at its locator
 * owner.play(new Aura(look, x, y, z).follow(CgEntityAttachments.of(entities, id, CgEntityAttachments.CENTRE)));   // an entity's
 *
 * CgAttachment fixed = (point, turn) -> {                                      // your own
 *     point[0] = 10; point[1] = 64; point[2] = -3;
 *     if (turn != null) turn.identity();
 *     return true;
 * };
 * }</pre>
 *
 * <ul>
 *   <li>{@link #place} is asked each frame by what follows it, after what moves it has run; false means it is gone,
 *       and what follows it stops where it last was.</li>
 *   <li>{@code point} is absolute, in doubles: the world's; {@code turn} may be null when only the place is wanted.</li>
 * </ul>
 */
@FunctionalInterface
public interface CgAttachment {

    /** Its place this frame into {@code point} and its turn into {@code turn}; false once it is gone. */
    boolean place(double[] point, @Nullable Quaternionf turn);

    /** Its point named {@code name} (a socket, a bone) into {@code out}, absolute; false if it has none. */
    default boolean point(String name, double[] out) {
        return false;
    }

    /**
     * Its point named {@code name} with that point's turn, Unreal's {@code GetSocketTransform}: what a cue spawns at.
     * Unless overridden, the point's turn is its own ({@link #place}'s).
     */
    default boolean point(String name, double[] out, @Nullable Quaternionf turn) {
        return place(out, turn) && point(name, out);
    }

    /** It seen from its point named {@code name}: what a system attached to that socket follows. */
    default CgAttachment atSocket(String name) {
        return (out, turn) -> point(name, out, turn);
    }

    /**
     * The box of the body it is on, this frame, into {@code out}: centre x, y, z, then half extents x, y, z, absolute.
     * What an effect's {@code entity()} emitters spawn in. False for one with no body.
     */
    default boolean bounds(double[] out) {
        return false;
    }

    /** The host entity it is on by network id, -1 for none: what a follower's casts skip, and whose clock it takes. */
    default int entity() {
        return -1;
    }
}
