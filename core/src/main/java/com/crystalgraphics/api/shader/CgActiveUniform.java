package com.crystalgraphics.api.shader;

import com.github.bsideup.jabel.Desugar;

/**
 * Immutable descriptor of a single active uniform variable as reported by the GL driver
 * via {@code glGetActiveUniform} / {@code glGetActiveUniformARB}.
 *
 * <p>Instances are returned by {@link CgShader#getActiveUniforms()} and
 * {@link CgShaderProgram#getActiveUniforms()}.  They represent the uniforms
 * that are <em>active</em> (i.e. not optimised away) in a successfully linked
 * program.  Built-in uniforms whose names begin with {@code gl_} are excluded
 * from the list.</p>
 *
 * <p>Equality and hashing are based on all four fields ({@code name},
 * {@code glType}, {@code size}, {@code location}) via the Lombok-generated
 * implementations.</p>
 *
 * @see CgShader#getActiveUniforms()
 * @see CgShaderProgram#getActiveUniforms()
 * @param name
The uniform name as declared in the GLSL source and reported by the driver.
Array uniforms typically appear as {@code "myArray[0]"}.
 * @param glType
The GL type constant (e.g. {@code GL_FLOAT}, {@code GL_FLOAT_VEC2},
{@code GL_SAMPLER_2D}, …) as returned by the driver.
 * @param size
Number of array elements for array uniforms; {@code 1} for non-arrays.
 * @param location
Result of {@code glGetUniformLocation(program, name)}.
May be {@code -1} if the driver reports the uniform as active but
cannot assign it a location (rare; should not occur in practice).
 */
@Desugar
public record CgActiveUniform(String name, int glType, int size, int location) {

}
