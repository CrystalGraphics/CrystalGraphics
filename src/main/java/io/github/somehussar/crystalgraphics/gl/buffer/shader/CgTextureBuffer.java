package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;

/**
 * TBO-backed {@link CgShaderBuffer} fallback for hardware without SSBO support (GL 3.1+).
 *
 * <h3>Structure</h3>
 * <p>The parent {@link CgShaderBuffer} owns a {@code CgStreamBuffer} with target
 * {@code GL_ARRAY_BUFFER}. This class additionally owns a {@code GL_TEXTURE_BUFFER} texture
 * ({@link #tboTexId}) that is attached to that buffer object once at construction via
 * {@code glTexBuffer}.</p>
 *
 * <h3>Why re-attachment is not needed after GPU buffer growth</h3>
 * <p>Per the OpenGL specification, {@code glTexBuffer} binds the texture to the buffer
 * <em>object</em> (identified by its GL name), not to the buffer's current storage allocation.
 * When {@link io.github.somehussar.crystalgraphics.gl.buffer.MapAndOrphanStreamBuffer} or
 * {@link io.github.somehussar.crystalgraphics.gl.buffer.SubDataStreamBuffer} calls
 * {@code glBufferData} to grow the GPU buffer, the buffer object ID stays the same — only
 * the backing store changes. The texture attachment therefore remains valid and the shader
 * reads from the new storage without any re-attachment call.</p>
 *
 * <h3>Bind/unbind</h3>
 * <p>Binds {@link #tboTexId} to {@code GL_TEXTURE_BUFFER} on the reserved texture unit
 * ({@link #DEFAULT_TBO_TEXTURE_UNIT}). The caller must set the {@code cg_ObjectTBO} sampler
 * uniform to {@link #getTboTextureUnit()} once after program link.</p>
 */
public final class CgTextureBuffer extends CgShaderBuffer {

    /** GL texture unit this TBO is bound to. Fixed at {@link #DEFAULT_TBO_TEXTURE_UNIT}. */
    private final int textureUnit;

    /**
     * The {@code GL_TEXTURE_BUFFER} texture object.
     * Attached to the parent's stream buffer once at construction and never re-attached.
     * Deleted by {@link #deleteGlResources()}.
     */
    private final int tboTexId;

    /**
     * @param floatPerRecord floats per per-object record; use {@link #FLOATS_PER_OBJECT}
     *                        for the default CrystalShader ABI
     * @param initialCapacity number of records to pre-allocate
     */
    CgTextureBuffer(int floatPerRecord, int initialCapacity) {
        super(floatPerRecord, initialCapacity, GL15.GL_ARRAY_BUFFER);
        this.textureUnit = DEFAULT_TBO_TEXTURE_UNIT;
        this.tboTexId = GL11.glGenTextures();
        attachTextureToBuffer();
    }

    /**
     * Activates {@link #textureUnit} and binds {@link #tboTexId} to {@code GL_TEXTURE_BUFFER}.
     */
    @Override
    protected void bindInternal() {
        CgTexture.active(textureUnit);
        CgTexture.bind(GL31.GL_TEXTURE_BUFFER, tboTexId);
    }

    /**
     * Unbinds the texture and restores {@code GL_TEXTURE0} as the active unit.
     */
    @Override
    protected void unbindInternal() {
        CgTexture.active(textureUnit);
        CgTexture.bind(GL31.GL_TEXTURE_BUFFER, 0);
        CgTexture.active(0);
    }

    /**
     * Returns the GL texture unit this TBO is bound to.
     * Pass this value as the {@code cg_ObjectTBO} sampler uniform after program link.
     */
    @Override
    public int getTboTextureUnit() {
        return textureUnit;
    }

    /** Returns {@link CgCapabilities.ShaderBufferPath#TBO}. */
    @Override
    public CgCapabilities.ShaderBufferPath getPath() {
        return CgCapabilities.ShaderBufferPath.TBO;
    }

    /**
     * Deletes {@link #tboTexId}. Called by the parent {@link #delete()} after the
     * stream buffer has been deleted.
     */
    @Override
    protected void deleteGlResources() {
        GL11.glDeleteTextures(tboTexId);
    }

    /**
     * Attaches the parent's GL buffer object to {@link #tboTexId} via {@code glTexBuffer}.
     * Uses {@code GL_RGBA32F} so each texel is 4 floats — matching the 11-texel-per-object
     * layout declared in {@code cg_env.glsl}.
     * Called once from the constructor; never needs to be called again even after GPU buffer growth.
     */
    private void attachTextureToBuffer() {
        CgTexture.active(textureUnit);
        CgTexture.bind(GL31.GL_TEXTURE_BUFFER, tboTexId);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, getGlBufferId());
        CgTexture.bind(GL31.GL_TEXTURE_BUFFER, 0);
        CgTexture.active(0);
    }
}
