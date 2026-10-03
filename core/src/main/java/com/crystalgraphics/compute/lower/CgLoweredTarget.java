package com.crystalgraphics.compute.lower;

import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;

/**
 * What a context below compute gives a lowered kernel: how engine buffers are declared there, what a geometry stage
 * may emit, how large a texture and a buffer texture may be, and whether a count the GPU wrote can size a draw.
 * {@link #current()} on the render thread; {@link #GL33} for a test.
 *
 * <pre>{@code
 * CgLoweredEmitter.Stages stages = CgLoweredEmitter.emit(source, kernel, keywords, pass, CgLoweredTarget.current());
 * }</pre>
 *
 * @param drawsGpuCounts an indirect draw takes its count from a buffer ({@code ARB_draw_indirect}, and not tier G33)
 * @param tierGlsl       the lowest GLSL of the context's tier: 400 at G40, 330 at G33, so a tier forced on a newer
 *                       context compiles as its weakest would
 */
public record CgLoweredTarget(CgCapabilities.ShaderBufferPath bufferPath, int maxGeometryVertices,
                              int maxGeometryComponents, int maxTextureSize, int maxTextureBufferSize,
                              boolean drawsGpuCounts, int tierGlsl) {

    /** GL 3.3's guaranteed limits, buffers as textures, no indirect draws. */
    public static final CgLoweredTarget GL33 = new CgLoweredTarget(CgCapabilities.ShaderBufferPath.TBO, 256, 1024, 1024,
            65536, false, 330);

    /** The current context's. */
    public static CgLoweredTarget current() {
        CgCapabilities caps = CgCapabilities.detect();
        boolean g33 = caps.computeTier() == CgCapabilities.ComputeTier.G33;
        return new CgLoweredTarget(caps.shaderBufferPath(), CgGL.glGetInteger(CgGL.GL_MAX_GEOMETRY_OUTPUT_VERTICES),
                CgGL.glGetInteger(CgGL.GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS), CgGL.glGetInteger(CgGL.GL_MAX_TEXTURE_SIZE),
                caps.getMaxTextureBufferSize(), caps.drawIndirect() && !g33, g33 ? 330 : 400);
    }

    /** Engine buffers as storage blocks, as on a context with them; else as buffer textures. */
    public boolean storageBlocks() {
        return bufferPath != CgCapabilities.ShaderBufferPath.TBO;
    }

    /** The GLSL a lowered stage compiles at: its tier's, or 4.30 where engine buffers are storage blocks. */
    public int glsl() {
        return bufferPath == CgCapabilities.ShaderBufferPath.SSBO_GL43 ? 430 : tierGlsl;
    }

    /** The {@code #version} line, with the storage-buffer extension where blocks need it below GL 4.3. */
    String version() {
        String version = "#version " + glsl() + " core\n";
        return bufferPath == CgCapabilities.ShaderBufferPath.SSBO_ARB
                ? version + "#extension GL_ARB_shader_storage_buffer_object : require\n" : version;
    }

    /** What a geometry stage emitting {@code words} captured words and a position may emit per invocation. */
    public int geometryVertices(int words) {
        return Math.max(1, Math.min(maxGeometryVertices, maxGeometryComponents / (words + 4)));
    }
}
