package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import org.lwjgl.opengl.GL11;
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
 * When the underlying stream buffer calls {@code glBufferData} to grow the GPU buffer, the
 * buffer object ID stays the same — only the backing store changes. The texture attachment
 * therefore remains valid without any re-attachment call.</p>
 *
 * <h3>Bind/unbind</h3>
 * <p>Binds {@link #tboTexId} to {@code GL_TEXTURE_BUFFER} on the reserved texture unit
 * ({@link #DEFAULT_TBO_TEXTURE_UNIT}). The caller must set the {@code cg_ObjectTBO} sampler
 * uniform to {@link #bindingLocation} once after program link.</p>
 */
public final class CgTextureBuffer extends CgShaderBuffer {

    /**
     * Default GL texture unit reserved for the TBO object-data sampler ({@code cg_ObjectTBO}).
     * Set this sampler uniform once after program link on the TBO path:
     * {@code shader.set1i("cg_ObjectTBO", CgTextureBuffer.DEFAULT_TBO_TEXTURE_UNIT)}.
     */
    public static final int DEFAULT_TBO_TEXTURE_UNIT = 7;

    /**
     * The {@code GL_TEXTURE_BUFFER} texture object.
     * Attached to the parent's stream buffer once at construction and never re-attached.
     * Deleted by {@link #deleteGlResources()}.
     */
    private final int tboTexId;

    /**
     * @param format          typed format descriptor (mandatory)
     * @param bindingLocation ignored — TBOs always bind to {@link #DEFAULT_TBO_TEXTURE_UNIT}
     */
    CgTextureBuffer(CgBufferFormat format, int bindingLocation) {
        super(format, GL15.GL_ARRAY_BUFFER, DEFAULT_TBO_TEXTURE_UNIT);
        this.path = CgCapabilities.ShaderBufferPath.TBO;
        this.tboTexId = GL11.glGenTextures();
        attachTextureToBuffer();
    }

    /**
     * Attaches the parent's GL buffer object to {@link #tboTexId} via {@code glTexBuffer}.
     * Uses {@code GL_RGBA32F} so each texel is 4 floats.
     * Called once from the constructor; never needs to be called again even after GPU buffer growth.
     */
    private void attachTextureToBuffer() {
        CgTexture.active(bindingLocation);
        CgTexture.bind(GL31.GL_TEXTURE_BUFFER, tboTexId);
        GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, getGlBufferId());
        CgTexture.bind(GL31.GL_TEXTURE_BUFFER, 0);
        CgTexture.active(0);
    }
    
    /**
     * Activates texture unit of {@link #bindingLocation} and binds {@link #tboTexId} to {@code GL_TEXTURE_BUFFER}.
     */
    @Override
    protected void bindInternal() {
        CgTexture.active(bindingLocation);
        CgTexture.bind(GL31.GL_TEXTURE_BUFFER, tboTexId);
    }

    /**
     * Unbinds the texture and restores {@code GL_TEXTURE0} as the active unit.
     */
    @Override
    protected void unbindInternal() {
        CgTexture.active(bindingLocation);
        CgTexture.bind(GL31.GL_TEXTURE_BUFFER, 0);
        CgTexture.active(0);
    }

    /**
     * Deletes {@link #tboTexId}. Called by the parent {@link #delete()} after the
     * stream buffer has been deleted.
     */
    @Override
    protected void deleteGlResources() {
        GL11.glDeleteTextures(tboTexId);
    }
}
