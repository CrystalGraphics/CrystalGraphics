package com.crystalgraphics.api.material;

/**
 * How a value reaches a draw: three routes, and no fourth. Never by name: a per-draw value arrives only through a
 * {@code bind}.
 *
 * <pre>{@code
 * definition.route("_Speed");                 // CONSTANT: in the material's snapshot, nothing per draw
 * definition.route("_Color");                 // ROW: its values bind it, or a renderer or track does
 * CgMaterialRoute.of("global.skill.darken");  // GLOBAL: a parameter collection, one buffer a frame
 * }</pre>
 */
public enum CgMaterialRoute {
    /** {@code values} on the material or an ancestor: in its snapshot, nothing per draw. */
    CONSTANT,
    /** A {@code bind} of a renderer, a track or a user parameter: a vec4 slot in the draw's row ({@code cg_use rows}). */
    ROW,
    /** A parameter collection, {@code global.<namespace>.<name>}: {@code CgParameterCollection}'s buffer. */
    GLOBAL;

    /** The prefix of a parameter collection's value: {@code global.<namespace>.<name>}. */
    public static final String GLOBAL_PREFIX = "global.";

    /** {@link #GLOBAL} for a collection's name, else {@link #ROW}: what a {@code bind} source routes through. */
    public static CgMaterialRoute of(String bindSource) {
        return bindSource.startsWith(GLOBAL_PREFIX) ? GLOBAL : ROW;
    }
}
