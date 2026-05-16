package com.crystalgraphics.api.shader;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;

import org.joml.*;
import com.crystalgraphics.platform.gl.CgGL;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * A deferred uniform/sampler binding container that accumulates bindings
 * and applies them in bulk to a managed shader.
 *
 * <p>Bindings are recorded through a fluent API (all setters return {@code this}
 * for chaining), then flushed to the underlying {@link CgShaderProgram} via
 * {@link #apply(CgShader)}. This decouples uniform configuration from
 * the bind/unbind lifecycle of the shader program itself.</p>
 *
 * <p>Missing uniforms are handled gracefully: a warn-once log message is emitted
 * per uniform name per program ID, preventing log spam while still surfacing
 * typos in uniform names that would otherwise go silent.</p>
 *
 * <p><strong>Instances are not thread-safe.</strong> All operations must occur
 * on the render thread only.</p>
 *
 * @see CgShader#bindings()
 */
public interface CgShaderBindings {

    /**
     * Records a single integer uniform binding.
     *
     * @param name  the uniform name
     * @param value the integer value to set
     * @return this instance for chaining
     */
    CgShaderBindings set1i(String name, int value);

    /**
     * Records a single-component float uniform binding.
     *
     * <p>The uniform is resolved by name at apply-time against the target
     * shader program. If the uniform does not exist, a warn-once message
     * is logged and the binding is skipped.</p>
     *
     * @param name  the uniform name as declared in the GLSL source
     * @param value the float value to set
     * @return this instance for chaining
     */
    CgShaderBindings set1f(String name, float value);

    /**
     * Records a two-component float vector (vec2) uniform binding.
     *
     * @param name the uniform name
     * @param x    the X component
     * @param y    the Y component
     * @return this instance for chaining
     */
    CgShaderBindings vec2(String name, float x, float y);

    /**
     * Records a two-component float vector (vec2) uniform binding.
     *
     * @param name the uniform name
     * @param vec2 JOML Vector2f object
     * @return this instance for chaining
     */
    CgShaderBindings vec2(String name, Vector2f vec2);
    
    /**
     * Records a three-component float vector (vec3) uniform binding.
     *
     * @param name the uniform name
     * @param x    the X component
     * @param y    the Y component
     * @param z    the Z component
     * @return this instance for chaining
     */
    CgShaderBindings vec3(String name, float x, float y, float z);

    /**
     * Records a three-component float vector (vec3) uniform binding.
     *
     * @param name the uniform name
     * @param vec3 JOML Vector3f object
     * @return this instance for chaining
     */
    CgShaderBindings vec3(String name, Vector3f vec3);

    /**
     * Records a four-component float vector (vec4) uniform binding.
     *
     * @param name the uniform name
     * @param x    the X component
     * @param y    the Y component
     * @param z    the Z component
     * @param w    the W component
     * @return this instance for chaining
     */
    CgShaderBindings vec4(String name, float x, float y, float z, float w);

    /**
     * Records a four-component float vector (vec4) uniform binding.
     *
     * @param name the uniform name
     * @param vec4 JOML Vector4f object
     * @return this instance for chaining
     */
    CgShaderBindings vec4(String name, Vector4f vec4);
    
    /**
     * Records a scalar int array uniform binding ({@code glUniform1iv} equivalent).
     * The array is converted to an IntBuffer internally.
     *
     * <p>Each element maps to one {@code int} uniform in a GLSL array
     * (e.g. {@code uniform int values[4];}). This does <strong>not</strong>
     * support {@code ivec} or matrix array uploads.</p>
     *
     * @param name  the uniform name
     * @param array the int values
     * @return this instance for chaining
     */
    CgShaderBindings array(String name, int[] array);
    
    /**
     * Records a scalar float array uniform binding ({@code glUniform1fv} equivalent).
     * The array is converted to a FloatBuffer internally.
     *
     * <p>Each element maps to one {@code float} uniform in a GLSL array
     * (e.g. {@code uniform float weights[8];}). This does <strong>not</strong>
     * support {@code vec} or {@code mat} array uploads.</p>
     *
     * @param name  the uniform name
     * @param array the float values
     * @return this instance for chaining
     */
    CgShaderBindings array(String name, float[] array);

    /**
     * Records a scalar int array uniform binding from an {@link IntBuffer}
     * ({@code glUniform1iv} equivalent).
     *
     * <p>Each element in the buffer maps to one {@code int} uniform in a GLSL
     * array (e.g. {@code uniform int indices[4];}). This does <strong>not</strong>
     * support {@code ivec} array uploads.</p>
     *
     * <p>The buffer is captured at record-time; position and limit are preserved as-is.</p>
     *
     * @param name   the uniform name
     * @param buffer the int data
     * @return this instance for chaining
     */
    CgShaderBindings buffer(String name, IntBuffer buffer);

    /**
     * Records a scalar float array uniform binding from a {@link FloatBuffer}
     * ({@code glUniform1fv} equivalent).
     *
     * <p>Each element in the buffer maps to one {@code float} uniform in a GLSL
     * array (e.g. {@code uniform float weights[8];}). This does <strong>not</strong>
     * support {@code vec} or {@code mat} array uploads — use {@link #mat3(String, FloatBuffer)}
     * or {@link #mat4(String, FloatBuffer)} for matrix uniforms.</p>
     *
     * <p>The buffer is captured at record-time; position and limit are preserved as-is.</p>
     *
     * @param name   the uniform name
     * @param buffer the float data
     * @return this instance for chaining
     */
    CgShaderBindings buffer(String name, FloatBuffer buffer);

    /**
     * Records a 3x3 float matrix uniform binding from a FloatBuffer (9 elements, column-major).
     *
     * @param name   the uniform name
     * @param buffer a 9-element FloatBuffer in column-major order
     * @return this instance for chaining
     */
    CgShaderBindings mat3(String name, FloatBuffer buffer);

    /**
     * Records a 3×3 float matrix uniform binding from a JOML {@link Matrix3f}.
     *
     * <p>The matrix is serialized to column-major order internally at
     * apply-time, so callers do not need to perform manual
     * {@link FloatBuffer} conversion.</p>
     *
     * @param name   the uniform name as declared in the GLSL source
     * @param matrix the JOML 3×3 matrix to upload
     * @return this instance for chaining
     */
    CgShaderBindings mat3(String name, Matrix3f matrix);
    
    /**
     * Records a 4x4 float matrix uniform binding from a FloatBuffer (16 elements, column-major).
     *
     * @param name   the uniform name as declared in the GLSL source
     * @param buffer a 16-element FloatBuffer in column-major order
     * @return this instance for chaining
     */
    CgShaderBindings mat4(String name, FloatBuffer buffer);

    /**
     * Records a 4×4 float matrix uniform binding from a JOML {@link Matrix4f}.
     *
     * <p>The matrix is serialized to column-major order internally at
     * apply-time, so callers do not need to perform manual
     * {@link FloatBuffer} conversion.</p>
     *
     * @param name   the uniform name as declared in the GLSL source
     * @param matrix the JOML 4×4 matrix to upload
     * @return this instance for chaining
     */
    CgShaderBindings mat4(String name, Matrix4f matrix);

    /**
     * Records a color uniform binding from a packed ARGB integer.
     *
     * <p>The integer is decomposed into four 0..1 float components in RGBA order:
     * <br>Bit layout (MSB first): {@code AAAA_AAAA RRRR_RRRR GGGG_GGGG BBBB_BBBB}
     * <br>Each 8-bit channel is divided by 255.0 to normalize to [0..1].</p>
     *
     * @param name the uniform name (should expect a vec4)
     * @param argb a packed ARGB color integer (e.g., {@code 0xFF8040C0})
     * @return this instance for chaining
     */
    CgShaderBindings colorARGB(String name, int argb);

    /**
     * Records a color uniform binding from a packed RGB integer and a separate alpha float.
     * Equivalent to NPCDBC ShaderHelper.uniformColor. Produces RGBA vec4 with components in [0..1].
     *
     * <p>The RGB integer is decomposed into three 0..1 float components:
     * <br>Bit layout (MSB first): {@code xxxxxxxx RRRR_RRRR GGGG_GGGG BBBB_BBBB}
     * <br>Each 8-bit channel is divided by 255.0 to normalize to [0..1].
     * The alpha float is used directly (expected to already be in [0..1]).</p>
     *
     * @param name  the uniform name (should expect a vec4)
     * @param rgb   a packed RGB color integer (e.g., {@code 0x8040C0})
     * @param alpha the alpha component in [0..1]
     * @return this instance for chaining
     */
    CgShaderBindings colorRGB(String name, int rgb, float alpha);

    /**
     * Records a sampler binding for a {@link CgTexture}. At apply-time,
     * activates the given texture unit, binds the texture to its native GL
     * target, and sets the sampler uniform to the unit index.
     *
     * @param name    the sampler uniform name as declared in GLSL
     * @param unit    zero-based texture unit (0 = GL_TEXTURE0)
     * @param texture the texture to bind
     * @return this instance for chaining
     */
    CgShaderBindings sampler(String name, int unit, CgTexture texture);

    /**
     * Records a sampler binding for a raw GL texture id with target GL_TEXTURE_2D.
     *
     * @param name        the sampler uniform name as declared in GLSL
     * @param unit        zero-based texture unit (0 = GL_TEXTURE0)
     * @param glTextureId raw GL texture object id
     * @return this instance for chaining
     */
    default CgShaderBindings sampler(String name, int unit, int glTextureId) {
        return sampler(name, unit, glTextureId, CgGL.GL_TEXTURE_2D);
    }
    /**
     * Records a sampler binding for a raw GL texture id with an explicit
     * target. Use for FBO attachments or any texture not wrapped in
     * {@link CgTexture} (e.g. cubemaps via {@code 0x8513}, 2D arrays via
     * {@code 0x8C1A}, 3D textures via {@code 0x806F}).
     *
     * @param name        the sampler uniform name as declared in GLSL
     * @param unit        zero-based texture unit (0 = GL_TEXTURE0)
     * @param glTextureId raw GL texture object id
     * @param glTarget    GL texture target constant
     * @return this instance for chaining
     */
    CgShaderBindings sampler(String name, int unit, int glTextureId, int glTarget);

    /**
     * Records a persistent UBO block binding. Calls {@code glUniformBlockBinding} on EVERY
     * shader path (SSBO and TBO) to wire the block index to its binding slot.
     * Idempotent — writing the same integer to GL program state is effectively free.
     * Does NOT call {@code glBindBufferBase} — per-context binding is handled by
     * {@code CgMaterialPipeline.beginFrame()}.
     * This op lives in persistent {@link CgShader#bindings()}, not ephemeral bindings,
     * so it survives hot-reloads and automatically rewires the block after recompile.
     *
     * @param buffer the UBO to wire
     * @return this instance for chaining
     */
    CgShaderBindings ubo(CgUniformBuffer buffer);

    /**
     * Removes all accumulated bindings without applying them.
     *
     * <p>After this call, subsequent {@link #apply(CgShader)} calls
     * will have no effect until new bindings are recorded.</p>
     */
    void clear();

    /**
     * Applies all accumulated bindings to the given managed shader's program.
     *
     * <p>This method:
     * <ol>
     *   <li>Checks if the shader is compiled; returns early if not</li>
     *   <li>Resets warn-once tracking if the program ID has changed</li>
     *   <li>Executes each recorded binding operation in order</li>
     * </ol></p>
     *
     * <p>The underlying program must already be bound (via
     * {@link CgShaderProgram#bind()}) before this method is called. Binding
     * a shader via {@link CgShader#bind()} or
     * {@link CgShader#bindScoped()} will do this automatically.</p>
     *
     * @param shader the managed shader to apply bindings to
     */
    void apply(CgShader shader);
}
