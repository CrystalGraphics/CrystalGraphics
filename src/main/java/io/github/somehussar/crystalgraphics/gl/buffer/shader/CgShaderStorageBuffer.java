package io.github.somehussar.crystalgraphics.gl.buffer.shader;

import io.github.somehussar.crystalgraphics.api.CgCapabilities;
import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
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
 *
 * <p>Shader wiring is a true no-op: the GLSL {@code layout(binding=N)} qualifier
 * automatically associates the SSBO block with its binding point at link time.</p>
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
        super(name, format, GL43.GL_SHADER_STORAGE_BUFFER, bindingLocation);
        this.path = path;
    }

    @Override
    protected void bindInternal() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingLocation, getGlBufferId());
    }

    @Override
    protected void unbindInternal() {
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, bindingLocation, 0);
    }

    /** SSBO wiring is a no-op — {@code layout(binding=N)} in GLSL handles it at link time. */
    @Override
    protected void wireShader(CgShader shader) {
        // no-op: GLSL layout(binding=N) qualifier automatically wires the SSBO block
    }
}
