package com.crystalgraphics.sequence;

import javax.annotation.Nullable;
import com.crystalgraphics.sequence.definition.CgSequenceBinding;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/**
 * What a bound object takes from a sequence's state tracks: named slots of up to four numbers, resolved once when the
 * player binds and then written by index every frame. Unreal's property bindings ({@code FTrackInstancePropertyBindings}),
 * which find a property by its path once and set it each evaluation. A role's provider, a spawned object and a
 * locator each implement it.
 *
 * <pre>{@code
 * final class Lamp implements CgSequenceTarget {
 *     private final float[] colour = {1, 1, 1, 1};
 *     private float intensity = 1;
 *
 *     @Override public int slot(String name) {
 *         return switch (name) { case "colour" -> 0; case "intensity" -> 1; default -> -1; };
 *     }
 *
 *     @Override public void set(int slot, float[] values, int mask) {
 *         if (slot == 1) intensity = values[0];
 *         else for (int i = 0; i < 4; i++) if ((mask & 1 << i) != 0) colour[i] = values[i];
 *     }
 * }
 *
 * sequencer.play(definition, Map.of("caster", lamp));   // its tracks write the role named caster
 * }</pre>
 *
 * <ul>
 *   <li>{@link #slot} is asked once a slot a section writes; -1 fails the play, naming the target and the slot.</li>
 *   <li>{@link #set}'s {@code values} is reused: copy what you keep. Component {@code i} is written only where bit
 *       {@code i} of {@code mask} is set (a section keying only {@code x} leaves {@code y} and {@code z} alone).</li>
 *   <li>A transform writes {@code translation}, {@code rotation} and {@code scale}, three numbers each, in the host's
 *       units, a rotation's in radians and the sequence's convention ({@link CgSequenceDefinition#units()}).</li>
 *   <li>{@code set} runs on the frame thread, every frame a section is active: allocate nothing in it.</li>
 * </ul>
 */
public interface CgSequenceTarget {

    /** The index of the slot named {@code name}, or -1 if it has none. */
    int slot(String name);

    /** Writes {@code values}' components that {@code mask} names into slot {@code slot}. */
    void set(int slot, float[] values, int mask);

    /**
     * Its value of slot {@code slot} into {@code out}, as it stands: read once as a sequence first animates the slot,
     * so a blend can ramp from it and its end can put it back.
     */
    void get(int slot, float[] out);

    /**
     * A part of it a binding names ({@code { role: caster, material: body }}, a node, a socket), or null if it has no
     * such part.
     */
    @Nullable
    default CgSequenceTarget part(CgSequenceBinding.Part part, String name) {
        return null;
    }
}
