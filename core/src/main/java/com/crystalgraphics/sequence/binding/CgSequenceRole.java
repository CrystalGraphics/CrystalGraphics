package com.crystalgraphics.sequence.binding;

import java.util.Collection;
import java.util.List;

import javax.annotation.Nullable;

import com.crystalgraphics.world.CgAttachment;
import com.crystalgraphics.sequence.CgSequenceTarget;

/**
 * What plays a sequence's role: its slots, as any target's, and its sockets, where the sequence places what it spawns.
 * Unreal's possessable actor. RPG-Core implements it for its characters; a host or a test for anything else.
 *
 * <pre>{@code
 * final class Fighter implements CgSequenceRole {
 *     private final CgAnimInstance body;
 *     @Override public CgAttachment attachment(String socket) {
 *         return body.attachment(socket == null ? "root" : socket);   // a skinned mesh's sockets
 *     }
 *     @Override public int slot(String name) { return -1; }
 *     @Override public void set(int slot, float[] values, int mask) { }
 *     @Override public void get(int slot, float[] out) { }
 * }
 *
 * sequencer.play(beam, Map.of("caster", fighter));   // its spawnables placed at the fighter's sockets
 * }</pre>
 *
 * <ul>
 *   <li>{@link #attachment} is asked once a placement, at play: return one that answers every frame after.</li>
 *   <li>A socket it does not have may answer where it stands, as Unreal's {@code GetSocketTransform} does, or null,
 *       which fails the play, naming the socket.</li>
 * </ul>
 */
public interface CgSequenceRole extends CgSequenceTarget {

    /** Its socket {@code socket} as an attachment, or where it stands for null; null if it cannot place it. */
    @Nullable
    CgAttachment attachment(@Nullable String socket);

    /**
     * Its prefab's node {@code name} as an attachment ({@code binding: { role: caster, node: Mesh }}): where that node
     * stands and turns each frame. Null if it has no such node, which fails the play, naming it.
     */
    @Nullable
    default CgAttachment node(String name) {
        return null;
    }

    /** Who it is, for a {@code crystalgraphics:character} condition; null for no one in particular. */
    @Nullable
    default String character() {
        return null;
    }

    /**
     * The variants it reports, for a {@code crystalgraphics:variant} condition: a form, a flight gear (Kakarot's
     * {@code C001A_S_SKL003_3}); empty in its base form.
     */
    default Collection<String> variants() {
        return List.of();
    }
}
