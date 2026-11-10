package com.crystalgraphics.gl.buffer.shader;


import com.crystalgraphics.render.draw.CgBoundResources;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;

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
 * <p>Storage (creation, upload, resize, delete) is the parent {@link CgShaderBuffer}'s. This class owns its path
 * tag and what it notes for a pass's sets.</p>
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
     * @param lifetime        {@link CgBufferLifetime#FRAME} for the frame ring, bound by range
     */
    CgShaderStorageBuffer(String name, CgBufferFormat format,
                          CgCapabilities.ShaderBufferPath path, int bindingLocation, CgBufferLifetime lifetime) {
        super(name, format, CgStreamBuffer.Use.STORAGE, bindingLocation, lifetime);
        this.path = path;
    }

    /** On the frame ring, by range at the latest upload, in the storage growth may have replaced. */
    @Override
    protected void noteInternal() {
        int bytes = dataBuffer.getCommittedBytes();
        if (dataBuffer.offsetMovesPerUpload() && bytes > 0) {
            CgBoundResources.storage(bindingLocation, dataBuffer.gpuBuffer(), dataBuffer.getWriteOffset(),
                    dataBuffer.bindingBytes());
        } else {
            CgBoundResources.storage(bindingLocation, dataBuffer.gpuBuffer(), 0, 0);
        }
    }
}
