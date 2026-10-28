package com.crystalgraphics.sequence.binding;

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
}
