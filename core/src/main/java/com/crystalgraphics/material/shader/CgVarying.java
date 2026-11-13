package com.crystalgraphics.material.shader;

import com.github.bsideup.jabel.Desugar;

/**
 * One field of a pass's {@code v2f}: what its vertex stage hands its fragment stage.
 *
 * <pre>{@code
 * struct v2f { vec2 uv0; flat int id; noperspective vec2 p; };
 * // new CgVarying("vec2", "uv0", ""), new CgVarying("int", "id", "flat"), new CgVarying("vec2", "p", "noperspective")
 * }</pre>
 *
 * @param type          its GLSL type; an integer type is always {@code flat}
 * @param name          its name
 * @param interpolation {@code flat}, {@code noperspective}, {@code smooth}, or {@code ""} as written
 */
@Desugar
public record CgVarying(String type, String name, String interpolation) {
}
