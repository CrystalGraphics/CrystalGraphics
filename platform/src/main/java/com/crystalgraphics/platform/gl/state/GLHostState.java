package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * What a host sharing the GL device's context believes the context has, read as a host section opens so the section
 * can set it back as it closes. A host with a state cache (Minecraft's {@code GlStateManager}) answers from the cache,
 * whose belief is what its next call compares against; {@link #byQuery} reads GL itself.
 *
 * <pre>{@code
 * CgGLDevice device = new CgGLDevice(driver, translator, width, height, false, GLHostState.byQuery(driver));
 * device.fromHost();      // reads the host's state, then sets the device's conventions
 * ... record ...
 * device.toHost();        // what the section changed, set back
 * }</pre>
 *
 * <p>A field the host keeps no belief about stays {@link GLContextState#UNKNOWN}: the section leaves it as it finds it.</p>
 */
public interface GLHostState {

    /** Fills {@code into} with what the host believes; on the context's thread. */
    void read(GLContextState into);

    /** The section closed and the context went back to the host. */
    default void left() {}

    /**
     * Sets back the conventions the device's section set ({@code CgGLDevice}: sRGB writes, zero-to-one clip depth,
     * seamless cube maps, program point size, tight pixel rows) to what the host draws under, through {@code gl}. Nothing
     * for a host that declares none.
     */
    default void conventions(CgGLDriver gl) {}

    /**
     * The host is {@code CgGL} in the same context, while it and the device draw side by side: what it has is unknown
     * to the device on entry (every field set again), and {@code CgGL}'s shadow is dropped on leaving (Unreal's
     * {@code RHIInvalidateCachedState}, both ways).
     */
    static GLHostState sharedWithCgGL() {
        return new GLHostState() {
            @Override public void read(GLContextState into) { into.invalidate(); }

            @Override public void left() { CgGlState.invalidateAllIfPresent(); }

            /** {@code CgGL} sets none of the device's conventions: it draws under GL's defaults. */
            @Override
            public void conventions(CgGLDriver gl) {
                gl.glDisable(GL_FRAMEBUFFER_SRGB);
                gl.glDisable(GL_TEXTURE_CUBE_MAP_SEAMLESS);
                gl.glDisable(GL_PROGRAM_POINT_SIZE);
                gl.glClipControl(GL_LOWER_LEFT, GL_NEGATIVE_ONE_TO_ONE);
                gl.glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
                gl.glPixelStorei(GL_PACK_ALIGNMENT, 4);
            }
        };
    }

    /** Reads every field from GL by {@code glGet}: exact, and a stall on a threaded driver, so for hosts with no cache. */
    static GLHostState byQuery(CgGLDriver gl) {
        int[] one = new int[1], two = new int[2], four = new int[4];
        float[] f2 = new float[2], f4 = new float[4];
        int[][] stencil = {
                {GL_STENCIL_FUNC, GL_STENCIL_REF, GL_STENCIL_VALUE_MASK, GL_STENCIL_FAIL, GL_STENCIL_PASS_DEPTH_FAIL,
                        GL_STENCIL_PASS_DEPTH_PASS, GL_STENCIL_WRITEMASK},
                {GL_STENCIL_BACK_FUNC, GL_STENCIL_BACK_REF, GL_STENCIL_BACK_VALUE_MASK, GL_STENCIL_BACK_FAIL,
                        GL_STENCIL_BACK_PASS_DEPTH_FAIL, GL_STENCIL_BACK_PASS_DEPTH_PASS, GL_STENCIL_BACK_WRITEMASK}};
        int[] bindings = {GL_DRAW_INDIRECT_BUFFER_BINDING, GL_DISPATCH_INDIRECT_BUFFER_BINDING,
                GL_PARAMETER_BUFFER_BINDING, GL_PIXEL_PACK_BUFFER_BINDING, GL_PIXEL_UNPACK_BUFFER_BINDING};
        return s -> {
            for (int i = 0; i < GLContextState.CAPS.length; i++) s.caps[i] = gl.glIsEnabled(GLContextState.CAPS[i]) ? 1 : 0;
            for (int i = 0; i < GLContextState.DRAW_BUFFERS; i++) {
                s.blendEnabled[i] = gl.glIsEnabledi(GL_BLEND, i) ? 1 : 0;
                s.blendSrcRgb[i] = indexed(gl, GL_BLEND_SRC_RGB, i, one);
                s.blendDstRgb[i] = indexed(gl, GL_BLEND_DST_RGB, i, one);
                s.blendSrcAlpha[i] = indexed(gl, GL_BLEND_SRC_ALPHA, i, one);
                s.blendDstAlpha[i] = indexed(gl, GL_BLEND_DST_ALPHA, i, one);
                s.blendEqRgb[i] = indexed(gl, GL_BLEND_EQUATION_RGB, i, one);
                s.blendEqAlpha[i] = indexed(gl, GL_BLEND_EQUATION_ALPHA, i, one);
                gl.glGetIntegeri_v(GL_COLOR_WRITEMASK, i, four);
                s.colorMask[i] = (four[0] != 0 ? 1 : 0) | (four[1] != 0 ? 2 : 0) | (four[2] != 0 ? 4 : 0) | (four[3] != 0 ? 8 : 0);
            }
            s.program = gl.glGetInteger(GL_CURRENT_PROGRAM);
            s.vertexArray = gl.glGetInteger(GL_VERTEX_ARRAY_BINDING);
            s.drawFramebuffer = gl.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
            s.readFramebuffer = gl.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
            s.cullFace = gl.glGetInteger(GL_CULL_FACE_MODE);
            s.frontFace = gl.glGetInteger(GL_FRONT_FACE);
            gl.glGetIntegerv(GL_POLYGON_MODE, two);
            s.polygonMode = two[0] == two[1] ? two[0] : GLContextState.UNKNOWN;
            s.offsetFactor = gl.glGetFloat(GL_POLYGON_OFFSET_FACTOR);
            s.offsetUnits = gl.glGetFloat(GL_POLYGON_OFFSET_UNITS);
            s.depthFunc = gl.glGetInteger(GL_DEPTH_FUNC);
            s.depthMask = gl.glGetInteger(GL_DEPTH_WRITEMASK) != 0 ? 1 : 0;
            for (int f = 0; f < 2; f++) {
                s.stencilFunc[f] = gl.glGetInteger(stencil[f][0]);
                s.stencilRef[f] = gl.glGetInteger(stencil[f][1]);
                s.stencilValueMask[f] = gl.glGetInteger(stencil[f][2]);
                s.stencilFail[f] = gl.glGetInteger(stencil[f][3]);
                s.stencilDepthFail[f] = gl.glGetInteger(stencil[f][4]);
                s.stencilPass[f] = gl.glGetInteger(stencil[f][5]);
                s.stencilWriteMask[f] = gl.glGetInteger(stencil[f][6]);
            }
            gl.glGetFloatv(GL_VIEWPORT, f4);
            s.viewportX = f4[0];
            s.viewportY = f4[1];
            s.viewportW = f4[2];
            s.viewportH = f4[3];
            gl.glGetFloatv(GL_DEPTH_RANGE, f2);
            s.depthNear = f2[0];
            s.depthFar = f2[1];
            gl.glGetIntegerv(GL_SCISSOR_BOX, four);
            s.scissorX = four[0];
            s.scissorY = four[1];
            s.scissorW = four[2];
            s.scissorH = four[3];
            s.clipOrigin = gl.glGetInteger(GL_CLIP_ORIGIN);
            s.clipDepth = gl.glGetInteger(GL_CLIP_DEPTH_MODE);
            s.unpackAlignment = gl.glGetInteger(GL_UNPACK_ALIGNMENT);
            s.packAlignment = gl.glGetInteger(GL_PACK_ALIGNMENT);
            for (int i = 0; i < GLContextState.POINTS; i++) {
                s.uniformBuffer[i] = indexed(gl, GL_UNIFORM_BUFFER_BINDING, i, one);
                s.uniformOffset[i] = gl.glGetInteger64i(GL_UNIFORM_BUFFER_START, i);
                s.uniformSize[i] = gl.glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, i);
                s.storageBuffer[i] = indexed(gl, GL_SHADER_STORAGE_BUFFER_BINDING, i, one);
                s.storageOffset[i] = gl.glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, i);
                s.storageSize[i] = gl.glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, i);
            }
            int active = gl.glGetInteger(GL_ACTIVE_TEXTURE);
            for (int i = 0; i < GLContextState.UNITS; i++) {
                gl.glActiveTexture(GL_TEXTURE0 + i);
                s.unitTexture[i] = gl.glGetInteger(GL_TEXTURE_BINDING_2D);
                s.unitSampler[i] = gl.glGetInteger(GL_SAMPLER_BINDING);
            }
            gl.glActiveTexture(active);
            for (int i = 0; i < bindings.length; i++) s.targetBuffer[i] = gl.glGetInteger(bindings[i]);
        };
    }

    private static int indexed(CgGLDriver gl, int name, int index, int[] one) {
        gl.glGetIntegeri_v(name, index, one);
        return one[0];
    }
}
