package com.crystalgraphics.sequence;

import javax.annotation.Nullable;

/**
 * What a track, a locator's parent or a spawnable's place binds, resolved to an index into its sequence: Unreal's
 * {@code FMovieSceneObjectBindingID}, a role, locator or spawnable by its slot rather than its name, and the part of
 * it (a socket, a node, a material).
 *
 * <pre>{@code
 * CgSequenceBinding b = track.binding();
 * if (b.subject() == CgSequenceBinding.Subject.ROLE) {
 *     CgSequenceDefinition.Role role = definition.roles().get(b.index());   // { role: caster, socket: hand_r }
 *     String socket = b.part() == CgSequenceBinding.Part.SOCKET ? b.partName() : null;
 * }
 * }</pre>
 *
 * <ul>
 *   <li>{@link #index()} is -1 for {@link Subject#NONE} and {@link Subject#ENVIRONMENT}.</li>
 *   <li>A camera ({@code { camera: c1 }}) is the spawnable it names, which holds a {@code camera}.</li>
 *   <li>{@link Part#VELOCITY} reads the subject's velocity every tick: a {@code vec3} user parameter bound to a role.</li>
 * </ul>
 *
 * @param subject  what is bound
 * @param index    its index in the definition's roles, locators or spawnables
 * @param part     which part of it
 * @param partName the socket's, node's or material's name; null for the whole and for the velocity
 */
public record CgSequenceBinding(Subject subject, int index, Part part, @Nullable String partName) {

    /** What a binding names. */
    public enum Subject {
        /** A master track's: nothing. */
        NONE,
        /** The host world's settings: fog, ambient light, its grade. */
        ENVIRONMENT,
        ROLE, LOCATOR, SPAWNABLE
    }

    /** Which part of its subject. */
    public enum Part { WHOLE, SOCKET, NODE, MATERIAL, VELOCITY }

    /** A master track's binding. */
    public static final CgSequenceBinding NONE = new CgSequenceBinding(Subject.NONE, -1, Part.WHOLE, null);
    /** The host world's settings. */
    public static final CgSequenceBinding ENVIRONMENT = new CgSequenceBinding(Subject.ENVIRONMENT, -1, Part.WHOLE, null);
}
