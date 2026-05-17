package com.crystalgraphics.gl.buffer.shader;


import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.gl.CgGL;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.LogManager;

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
 * <p>Binds {@link #tboTexId} to {@code GL_TEXTURE_BUFFER} on the texture unit equal to
 * {@link #bindingLocation}. The {@code samplerBuffer} uniform is wired via
 * {@link #wireShader(CgShader)} which calls {@code glUniform1i(getName(), bindingLocation)}.</p>
 *
 * <h3>Texture unit assignment</h3>
 * <p>{@code bindingLocation} IS the texture unit. The engine object buffer uses
 * texture unit {@code CgBindingPoints.OBJECT_DATA_TBO} = {@code maxTexImageUnits - 1},
 * resolved at init time to avoid colliding with Minecraft's low-numbered units.</p>
 */
public final class CgTextureBuffer extends CgShaderBuffer {

    private static final Logger LOGGER = LogManager.getLogger("CgTextureBuffer");
    private static Boolean ARB_sampler_objects;    /**
     * The {@code GL_TEXTURE_BUFFER} texture object.
     * Attached to the parent's stream buffer once at construction and never re-attached.
     * Deleted by {@link #deleteGlResources()}.
     */
    private final int tboTexId;

    /**
     * @param name            sampler name used by {@link #wireShader(CgShader)} to locate
     *                        the {@code samplerBuffer} uniform in the active program
     * @param format          typed format descriptor (mandatory)
     * @param bindingLocation GL texture unit for this TBO; used as-is (no offset added here)
     */
    CgTextureBuffer(String name, CgBufferFormat format, int bindingLocation) {
        super(name, format, CgGL.GL_ARRAY_BUFFER, bindingLocation);
        this.path = CgCapabilities.ShaderBufferPath.TBO;
        this.tboTexId = CgGL.glGenTextures();
        attachTextureToBuffer();
    }

    /**
     * Attaches the parent's GL buffer object to {@link #tboTexId} via {@code glTexBuffer}.
     * Uses {@code GL_RGBA32F} so each texel is 4 floats.
     * Called once from the constructor; never needs to be called again even after GPU buffer growth.
     */
    private void attachTextureToBuffer() {
        CgTexture.active(bindingLocation);
        CgTexture.bind(CgGL.GL_TEXTURE_BUFFER, tboTexId);
        CgGL.glTexBuffer(CgGL.GL_TEXTURE_BUFFER, CgGL.GL_RGBA32F, getGlBufferId());
        CgTexture.bind(CgGL.GL_TEXTURE_BUFFER, 0);
        CgTexture.active(0);
    }
    
    /**
     * Activates the texture unit equal to {@link #bindingLocation} and binds {@link #tboTexId}
     * to {@code GL_TEXTURE_BUFFER}.
     *
     * <p>Intel driver bug: a sampler object bound to the same texture unit as a TBO causes
     * silent rendering breakage. Any sampler object on this unit is unbound first when
     * {@code GL_ARB_sampler_objects} is available.</p>
     */
    @Override
    protected void bindInternal() {
        CgTexture.active(bindingLocation);
        // Intel driver bug: sampler objects on the same unit as a TBO break rendering silently.
        if (ARB_sampler_objects == null) ARB_sampler_objects = CgCapabilities.detect().isSamplerObjectsSupported();
        if (ARB_sampler_objects) CgGL.glBindSampler(bindingLocation, 0);
        
        CgTexture.bind(CgGL.GL_TEXTURE_BUFFER, tboTexId);
    }

    /**
     * Unbinds the texture and restores {@code GL_TEXTURE0} as the active unit.
     */
    @Override
    protected void unbindInternal() {
        CgTexture.active(bindingLocation);
        CgTexture.bind(CgGL.GL_TEXTURE_BUFFER, 0);
        CgTexture.active(0);
    }

    /**
     * Wires the {@code samplerBuffer} uniform {@link #getName()} in {@code shader} to
     * {@link #bindingLocation}. Emits a warning if the uniform is absent (not declared
     * or optimized out).
     *
     * <p>Precondition: {@code shader.bind()} must have been called before this method.</p>
     */
    @Override
    public void wireShader(CgShader shader) {
        int loc = shader.getUniformLocation(getName());
        if (loc < 0) {
            LOGGER.warn("TBO '{}' not found in shader — samplerBuffer not declared or optimized out", getName());
            return;
        }
        shader.getProgram().setUniform1i(loc, bindingLocation);
    }

    /**
     * Deletes {@link #tboTexId}. Called by the parent {@link #delete()} after the
     * stream buffer has been deleted.
     */
    @Override
    protected void deleteGlResources() {
        CgGL.glDeleteTextures(tboTexId);
    }
}
