package io.github.somehussar.crystalgraphics.api.shader;

import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Primary public API for GLSL shader program management in CrystalGraphics.
 *
 * <p>This interface abstracts the differences between OpenGL Core (GL20) and
 * ARB ({@code ARB_shader_objects}) shader entry points behind a single,
 * unified contract.  Implementations are created by the factory/backend layer
 * and should never be instantiated directly by consuming code.</p>
 *
 * <h3>Ownership Model</h3>
 * <p>Each {@code CgShaderProgram} is either <em>owned</em> or <em>wrapped</em>:</p>
 * <ul>
 *   <li><strong>Owned</strong> ({@link #isOwned()} returns {@code true}):
 *       CrystalGraphics created this program and is responsible for its
 *       lifecycle.  Calling {@link #delete()} is valid and will release the
 *       underlying OpenGL resource.</li>
 *   <li><strong>Wrapped</strong> ({@link #isOwned()} returns {@code false}):
 *       This program was created externally (e.g., by another mod or by
 *       Minecraft's vanilla shader system) and is merely tracked by
 *       CrystalGraphics.  Calling {@link #delete()} on a wrapped program
 *       will throw {@link IllegalStateException}.</li>
 * </ul>
 *
 * <h3>Call Family Semantics</h3>
 * <p>Implementations bind programs through a specific OpenGL call family
 * (Core GL20 or ARB shader objects).  When the currently active call family
 * differs from this program's family, the implementation (via
 * {@link io.github.somehussar.crystalgraphics.gl.CrossApiTransition}) performs
 * a defensive unbind of the previous family before binding through its own,
 * preventing undefined driver behavior on mixed-family transitions.</p>
 *
 * <h3>Uniform and Sampler Binding</h3>
 * <p>Uniform setters operate on the currently bound program.  The program
 * must be bound (via {@link #bind()}) before calling any uniform or sampler
 * method.  Uniform locations are obtained via
 * {@link #getUniformLocation(String)} and may be cached by the caller.</p>
 *
 * <h3>Thread Safety</h3>
 * <p>Instances are <strong>not</strong> thread-safe.  All methods must be
 * called on the thread that owns the OpenGL context (typically the render
 * thread).</p>
 *
 * @see io.github.somehussar.crystalgraphics.api.CgCapabilities
 * @see io.github.somehussar.crystalgraphics.gl.CrossApiTransition
 */
public interface CgShaderProgram {

    /**
     * Binds this shader program for rendering.
     *
     * <p>After this call, all subsequent rendering operations use this
     * program's vertex and fragment shaders until another program is bound
     * or {@link #unbind()} is called.</p>
     */
    void bind();

    /**
     * Unbinds this shader program by binding program 0, reverting to the
     * fixed-function pipeline.
     */
    void unbind();

    /**
     * Returns the raw OpenGL program object ID.
     *
     * @return the OpenGL program name (a positive integer for linked
     *         programs, or 0 for the fixed-function pipeline)
     */
    int getId();

    /**
     * Returns whether CrystalGraphics owns this program and may delete it.
     *
     * @return {@code true} if this program was created by CrystalGraphics
     *         and may be deleted via {@link #delete()}; {@code false} if it
     *         was created externally (wrapped) and must not be deleted
     */
    boolean isOwned();

    /**
     * Deletes this shader program, releasing the underlying OpenGL resource.
     *
     * <p>Only valid if {@link #isOwned()} returns {@code true}.  After
     * deletion, {@link #isDeleted()} returns {@code true} and no further
     * operations on this program are valid.</p>
     *
     * @throws IllegalStateException if {@link #isOwned()} returns {@code false}
     * @throws IllegalStateException if already deleted
     */
    void delete();

    /**
     * Returns whether this shader program has been deleted.
     *
     * @return {@code true} if {@link #delete()} has been called successfully;
     *         {@code false} otherwise
     */
    boolean isDeleted();

    /**
     * Queries the location of a named uniform variable in this program.
     *
     * <p>The program does not need to be currently bound to query uniform
     * locations, but it must have been successfully linked.</p>
     *
     * @param name the name of the uniform variable as declared in the
     *             GLSL source
     * @return the uniform location (a non-negative integer), or {@code -1}
     *         if the named uniform does not exist or was optimized out by
     *         the GLSL compiler
     * @throws IllegalArgumentException if {@code name} is null
     */
    int getUniformLocation(String name);

    /**
     * Sets an integer uniform variable.
     *
     * <p>The program must be bound before calling this method.</p>
     *
     * @param location the uniform location obtained from
     *                 {@link #getUniformLocation(String)}
     * @param value    the integer value to set
     */
    void setUniform1i(int location, int value);

    /**
     * Sets a float uniform variable.
     *
     * <p>The program must be bound before calling this method.</p>
     *
     * @param location the uniform location obtained from
     *                 {@link #getUniformLocation(String)}
     * @param value    the float value to set
     */
    void setUniform1f(int location, float value);

    /**
     * Sets a 2-component float vector uniform variable.
     *
     * <p>The program must be bound before calling this method.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     */
    void setUniform2f(int location, float x, float y);

    /**
     * Sets a 3-component float vector uniform variable.
     *
     * <p>The program must be bound before calling this method.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     * @param z        the third component
     */
    void setUniform3f(int location, float x, float y, float z);

    /**
     * Sets a 4-component float vector uniform variable.
     *
     * <p>The program must be bound before calling this method.</p>
     *
     * @param location the uniform location
     * @param x        the first component
     * @param y        the second component
     * @param z        the third component
     * @param w        the fourth component
     */
    void setUniform4f(int location, float x, float y, float z, float w);
    
    /**
     * Uploads a scalar int array uniform from an {@link IntBuffer}
     * ({@code glUniform1iv} / {@code glUniform1ivARB} equivalent).
     *
     * <p>Each element in the buffer maps to one {@code int} uniform in a GLSL
     * array (e.g. {@code uniform int flags[4];}). This does <strong>not</strong>
     * support {@code ivec} array uploads — those require separate
     * {@code glUniform2iv} / {@code glUniform3iv} calls not provided here.</p>
     *
     * <p>If {@code location} is {@code -1} (uniform not found / optimized out),
     * implementations return immediately without issuing a GL call.</p>
     *
     * @param location the uniform location, or -1 to no-op
     * @param buffer   the int data; read from position to limit
     */
    void setUniformIntBuffer(int location, IntBuffer buffer);
    
    /**
     * Uploads a scalar float array uniform from a {@link FloatBuffer}
     * ({@code glUniform1fv} / {@code glUniform1fvARB} equivalent).
     *
     * <p>Each element in the buffer maps to one {@code float} uniform in a GLSL
     * array (e.g. {@code uniform float weights[8];}). This does <strong>not</strong>
     * support {@code vec} or {@code mat} array uploads — use
     * {@link #setUniformMatrix3f(int, FloatBuffer)} or
     * {@link #setUniformMatrix4f(int, FloatBuffer)} for matrices.</p>
     *
     * <p>If {@code location} is {@code -1} (uniform not found / optimized out),
     * implementations return immediately without issuing a GL call.</p>
     *
     * @param location the uniform location, or -1 to no-op
     * @param buffer   the float data; read from position to limit
     */
    void setUniformFloatBuffer(int location, FloatBuffer buffer);

    /**
     * Uploads a 3x3 float matrix uniform from a {@link FloatBuffer}.
     *
     * <p>The buffer must contain exactly <strong>9 elements</strong> (from
     * position to limit) in <strong>column-major</strong> order. The buffer
     * must be a direct buffer (heap buffers will cause LWJGL to throw).</p>
     *
     * <p>If {@code location} is {@code -1} (uniform not found / optimized out),
     * implementations return immediately without issuing a GL call.</p>
     *
     * @param location the uniform location, or -1 to no-op
     * @param buffer   a direct FloatBuffer with 9 elements in column-major order
     */
    void setUniformMatrix3f(int location, FloatBuffer buffer);

    /**
     * Uploads a 4x4 float matrix uniform from a {@link FloatBuffer}.
     *
     * <p>The buffer must contain exactly <strong>16 elements</strong> (from
     * position to limit) in <strong>column-major</strong> order. The buffer
     * must be a direct buffer (heap buffers will cause LWJGL to throw).</p>
     *
     * <p>If {@code location} is {@code -1} (uniform not found / optimized out),
     * implementations return immediately without issuing a GL call.</p>
     *
     * @param location the uniform location, or -1 to no-op
     * @param buffer   a direct FloatBuffer with 16 elements in column-major order
     */
    void setUniformMatrix4f(int location, FloatBuffer buffer);

    /**
     * Uploads a 3×3 float matrix uniform from a JOML {@link Matrix3f}.
     *
     * <p>The matrix is serialized to a direct {@link FloatBuffer} in
     * <strong>column-major</strong> order internally, so callers do not need
     * to perform manual buffer conversion.  Implementations use a
     * thread-local buffer to avoid per-call allocation.</p>
     *
     * <p>If {@code location} is {@code -1} (uniform not found / optimized out),
     * implementations return immediately without issuing a GL call.</p>
     *
     * @param location the uniform location, or -1 to no-op
     * @param matrix   the JOML 3×3 matrix to upload (column-major)
     */
    void setUniformMatrix3f(int location, Matrix3f matrix);

    /**
     * Uploads a 4×4 float matrix uniform from a JOML {@link Matrix4f}.
     *
     * <p>The matrix is serialized to a direct {@link FloatBuffer} in
     * <strong>column-major</strong> order internally, so callers do not need
     * to perform manual buffer conversion.  Implementations use a
     * thread-local buffer to avoid per-call allocation.</p>
     *
     * <p>If {@code location} is {@code -1} (uniform not found / optimized out),
     * implementations return immediately without issuing a GL call.</p>
     *
     * @param location the uniform location, or -1 to no-op
     * @param matrix   the JOML 4×4 matrix to upload (column-major)
     */
    void setUniformMatrix4f(int location, Matrix4f matrix);

    /**
     * Binds a texture unit to a sampler uniform.
     *
     * <p>This is a convenience wrapper around {@link #setUniform1i(int, int)}
     * that sets the sampler uniform to the specified texture unit index.
     * The caller is responsible for binding the actual texture to the
     * corresponding texture unit via {@code glActiveTexture} and
     * {@code glBindTexture}.</p>
     *
     * <p>The program must be bound before calling this method.</p>
     *
     * @param location    the sampler uniform location obtained from
     *                    {@link #getUniformLocation(String)}
     * @param textureUnit the 0-based texture unit index (0 corresponds to
     *                    {@code GL_TEXTURE0}, 1 to {@code GL_TEXTURE1}, etc.)
     */
    void setSampler(int location, int textureUnit);
}
