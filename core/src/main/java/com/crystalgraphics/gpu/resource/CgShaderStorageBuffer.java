package com.crystalgraphics.gpu.resource;


import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.rhi.binding.CgBindings;
import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.render.shader.CgBindingModel;

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
     * @param slot            the slot its shaders read it from
     * @param lifetime        {@link CgBufferLifetime#FRAME} for the frame ring, bound by range
     */
    CgShaderStorageBuffer(String name, CgBufferLayout format,
                          CgCapabilities.ShaderBufferPath path, CgBindingModel.Slot slot, CgBufferLifetime lifetime) {
        super(name, format, CgStreamBuffer.Use.STORAGE, slot, lifetime);
        this.path = path;
    }

    /** On the frame ring, by range at the latest upload, in the storage growth may have replaced. */
    @Override
    protected boolean describeInternal(CgBindings out, int binding) {
        CgGpuBuffer buffer = dataBuffer.gpuBuffer();
        if (buffer == null) return false;
        int bytes = dataBuffer.getCommittedBytes();
        if (dataBuffer.offsetMovesPerUpload() && bytes > 0) {
            out.buffer(binding, CgBindingLayout.Type.STORAGE_BUFFER, buffer, dataBuffer.getWriteOffset(), dataBuffer.bindingBytes());
        } else {
            out.buffer(binding, CgBindingLayout.Type.STORAGE_BUFFER, buffer, 0, buffer.size());
        }
        return true;
    }
}
