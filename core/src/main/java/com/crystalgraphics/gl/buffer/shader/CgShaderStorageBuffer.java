package com.crystalgraphics.gl.buffer.shader;


import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.api.shader.CgShader;
import com.crystalgraphics.platform.gl.CgGL;

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
 *
 * <p>Shader wiring calls {@code glShaderStorageBlockBinding} via {@code ARBProgramInterfaceQuery}
 * (block index lookup) and {@code ARBShaderStorageBufferObject} (binding assignment) to
 * wire the SSBO block to its binding slot post-link.</p>
 */
public final class CgShaderStorageBuffer extends CgShaderBuffer {

    /**
     * @param name            debug label for this buffer
     * @param format          typed format descriptor (mandatory)
     * @param path            SSBO hardware path (GL43 core or ARB)
     * @param bindingLocation immutable GL binding point
     */
    CgShaderStorageBuffer(String name, CgBufferFormat format,
                          CgCapabilities.ShaderBufferPath path, int bindingLocation) {
        super(name, format, CgGL.GL_SHADER_STORAGE_BUFFER, bindingLocation);
        this.path = path;
    }

    @Override
    protected void bindInternal() {
        CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, bindingLocation, getGlBufferId());
    }

    @Override
    protected void unbindInternal() {
        CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, bindingLocation, 0);
    }

    /**
     * Wires the SSBO block {@link #getName()} to {@link #bindingLocation} via
     * {@code glShaderStorageBlockBinding}. Called once per program link.
     * If the block is absent (optimised out or not declared), silently skips.
     */
    @Override
    public void wireShader(CgShader shader) {
        int programId = shader.getProgram().getId();
        int idx = CgGL.glGetProgramResourceIndex(programId, CgGL.GL_SHADER_STORAGE_BLOCK, getName());
        if (idx != CgGL.GL_INVALID_INDEX) {
            CgGL.glShaderStorageBlockBinding(programId, idx, bindingLocation);
        }
    }
}
