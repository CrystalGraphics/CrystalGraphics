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

    /** Hardware path this instance was created for. */
    private final CgCapabilities.ShaderBufferPath path;

    /**
     * @param path            SSBO capability path ({@code SSBO_GL43} or {@code SSBO_ARB})
     * @param floatPerRecord floats per per-object record; use {@link #FLOATS_PER_OBJECT}
     *                        for the default CrystalShader ABI
     * @param initialCapacity number of records to pre-allocate
     */
    CgShaderStorageBuffer(CgCapabilities.ShaderBufferPath path, int floatPerRecord, int initialCapacity) {
        super(floatPerRecord, initialCapacity, GL43.GL_SHADER_STORAGE_BUFFER);
        this.path = path;
    }

    /**
     * Binds the SSBO to {@link #BINDING_POINT} (binding = 0) via {@code glBindBufferBase}.
     * Both GL43 core and ARB paths share the same constant value and entry point.
     */
    @Override
    protected void bindInternal() {
        bind(BINDING_POINT);
    }

    /**
     * Binds the SSBO to the given binding P (binding = n) via {@code glBindBufferBase}.
     * Both GL43 core and ARB paths share the same constant value and entry point.
     */
    public void bind(int binding) {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, getGlBufferId());
    }


    /**
     * Unbinds by binding buffer 0 to {@link #BINDING_POINT}.
     */
    @Override
    protected void unbindInternal() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING_POINT, 0);
    }

    /**
     * Returns {@code SSBO_GL43} or {@code SSBO_ARB} — the path this instance was created on.
     */
    @Override
    public CgCapabilities.ShaderBufferPath getPath() {
        return path;
    }
}
