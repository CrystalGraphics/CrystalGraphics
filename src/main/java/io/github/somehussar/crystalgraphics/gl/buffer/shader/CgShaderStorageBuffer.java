package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

/**
 * SSBO-backed {@link CgShaderBuffer} implementation.
 *
 * <p>Supports two equivalent hardware paths — both use the same GL constant value
 * ({@code 0x90D2}) and the same {@code glBindBufferBase} entry point:</p>
 * <ul>
 *   <li>{@code GL_SHADER_STORAGE_BUFFER} via {@code GL43} (GL 4.3 core)</li>
 *   <li>{@code GL_SHADER_STORAGE_BUFFER} via {@code ARB_shader_storage_buffer_object}</li>
 * </ul>
 *
 * <p>The {@link CgCapabilities.ShaderBufferPath} (either {@code SSBO_GL43} or
 * {@code SSBO_ARB}) is recorded at construction time and exposed via {@link #getPath()}.
 * The bind/unbind logic is identical for both paths.</p>
 *
 * <p>GPU buffer management (stream buffer creation, upload, resize, delete) is handled
 * entirely by the parent {@link CgShaderBuffer}. This class only owns its path tag and
 * the bind/unbind GL calls.</p>
 */
public final class CgShaderStorageBuffer extends CgShaderBuffer {

    /**
     * @param floatPerRecord  floats per per-object record
     * @param initialCapacity number of records to pre-allocate
     */
    CgShaderStorageBuffer(int floatPerRecord, int initialCapacity, CgCapabilities.ShaderBufferPath path) {
        super(floatPerRecord, initialCapacity, GL43.GL_SHADER_STORAGE_BUFFER);
        this.path = path;
    }

    /**
     * Binds the SSBO to {@link #BINDING_POINT} (binding = 0) via {@code glBindBufferBase}.
     * Both GL43 core and ARB paths share the same constant value and entry point.
     */
    @Override
    protected void bindInternal() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingLocation, getGlBufferId());
    }


    /**
     * Binds the SSBO to the given binding P (binding = n) via {@code glBindBufferBase}.
     * Both GL43 core and ARB paths share the same constant value and entry point.
     */
    @Override
    protected void unbindInternal() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingLocation, 0);
    }
}
