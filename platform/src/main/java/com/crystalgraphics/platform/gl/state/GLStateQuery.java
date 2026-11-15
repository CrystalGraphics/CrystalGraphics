package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;

import java.util.Arrays;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * A context's state read with {@code glGet}, a {@link GLHostState.Domain} at a time: exact, and a stall on a threaded
 * driver. Also the two things a section does to a host's context beyond {@link GLContextState}: its alpha test, and the
 * defaults of what a cached host never declares.
 */
final class GLStateQuery {

    private static final int GL_ALPHA_TEST = 0x0BC0, GL_CONTEXT_PROFILE_MASK = 0x9126, GL_CONTEXT_CORE_PROFILE_BIT = 1;
    /** The attribute groups of a compatibility context's attribute stack, and its client stack's pixel store. */
    private static final int GL_POLYGON_BIT = 0x8, GL_DEPTH_BUFFER_BIT = 0x100, GL_STENCIL_BUFFER_BIT = 0x400,
            GL_VIEWPORT_BIT = 0x800, GL_ENABLE_BIT = 0x2000, GL_COLOR_BUFFER_BIT = 0x4000, GL_TEXTURE_BIT = 0x40000,
            GL_SCISSOR_BIT = 0x80000, GL_CLIENT_PIXEL_STORE_BIT = 0x1;

    private static final int[][] STENCIL = {
            {GL_STENCIL_FUNC, GL_STENCIL_REF, GL_STENCIL_VALUE_MASK, GL_STENCIL_FAIL, GL_STENCIL_PASS_DEPTH_FAIL,
                    GL_STENCIL_PASS_DEPTH_PASS, GL_STENCIL_WRITEMASK},
            {GL_STENCIL_BACK_FUNC, GL_STENCIL_BACK_REF, GL_STENCIL_BACK_VALUE_MASK, GL_STENCIL_BACK_FAIL,
                    GL_STENCIL_BACK_PASS_DEPTH_FAIL, GL_STENCIL_BACK_PASS_DEPTH_PASS, GL_STENCIL_BACK_WRITEMASK}};
    private static final int[] BINDINGS = {GL_DRAW_INDIRECT_BUFFER_BINDING, GL_DISPATCH_INDIRECT_BUFFER_BINDING,
            GL_PARAMETER_BUFFER_BINDING, GL_PIXEL_PACK_BUFFER_BINDING, GL_PIXEL_UNPACK_BUFFER_BINDING};

    final CgGLDriver gl;
    /** A compatibility context, which has an alpha test every fragment the device writes passes through. */
    final boolean compatibility;
    private final int[] one = new int[1], two = new int[2], four = new int[4];
    private final float[] f2 = new float[2], f4 = new float[4];
    private boolean alphaOff;
    /** The attribute groups {@link #carry} has asked for this section, then the ones pushed; 0 for none. */
    private int carrying, pushed;
    private boolean pushedPixelStore;

    GLStateQuery(CgGLDriver gl) {
        this.gl = gl;
        compatibility = (gl.glGetInteger(GL_CONTEXT_PROFILE_MASK) & GL_CONTEXT_CORE_PROFILE_BIT) == 0;
    }

    /**
     * The attribute groups that hold {@code domain} on a compatibility context, 0 where none does (the program, the
     * framebuffers and the vertex array), or on a core context.
     */
    int attribBits(GLHostState.Domain domain) {
        if (!compatibility) return 0;
        switch (domain) {
            case BLEND: return GL_COLOR_BUFFER_BIT | GL_ENABLE_BIT;
            case COLOR_MASK: return GL_COLOR_BUFFER_BIT;
            case DEPTH: return GL_DEPTH_BUFFER_BIT | GL_ENABLE_BIT;
            case CULL:
            case POLYGON: return GL_POLYGON_BIT | GL_ENABLE_BIT;
            case STENCIL: return GL_STENCIL_BUFFER_BIT | GL_ENABLE_BIT;
            case VIEWPORT: return GL_VIEWPORT_BIT;
            case SCISSOR: return GL_SCISSOR_BIT | GL_ENABLE_BIT;
            case TEXTURES: return GL_TEXTURE_BIT;
            default: return 0;
        }
    }

    /**
     * Leaves {@code domain} unknown in {@code s} and has the attribute stack keep it instead: pushed by {@link #push}
     * as the section opens, popped by {@link #pop} as it closes. No driver round trip; the device sets each field before
     * relying on it. Only where {@link #attribBits} holds the domain.
     */
    void carry(GLHostState.Domain domain, GLContextState s) {
        carrying |= attribBits(domain);
        unknown(domain, s);
    }

    /** Pushes what {@link #carry} asked for this section, and the pixel store with it; nothing where nothing was. */
    void push() {
        if (carrying == 0) return;
        if (pushed != 0) throw new IllegalStateException("a host section opened with the last one's attributes still pushed");
        gl.glPushAttrib(carrying);
        gl.glPushClientAttrib(GL_CLIENT_PIXEL_STORE_BIT);
        pushed = carrying;
        pushedPixelStore = true;
        carrying = 0;
    }

    /** Pops what {@link #push} pushed: every carried field back to the host's, as it was when the section opened. */
    void pop() {
        if (pushedPixelStore) gl.glPopClientAttrib();
        if (pushed != 0) gl.glPopAttrib();
        pushed = 0;
        pushedPixelStore = false;
    }

    /** {@code domain}'s fields of {@code s} unknown. */
    private static void unknown(GLHostState.Domain domain, GLContextState s) {
        final int u = GLContextState.UNKNOWN;
        switch (domain) {
            case BLEND:
                Arrays.fill(s.blendEnabled, u);
                Arrays.fill(s.blendSrcRgb, u);
                Arrays.fill(s.blendDstRgb, u);
                Arrays.fill(s.blendSrcAlpha, u);
                Arrays.fill(s.blendDstAlpha, u);
                Arrays.fill(s.blendEqRgb, u);
                Arrays.fill(s.blendEqAlpha, u);
                return;
            case COLOR_MASK:
                Arrays.fill(s.colorMask, u);
                return;
            case DEPTH:
                s.caps[GLContextState.cap(GL_DEPTH_TEST)] = u;
                s.depthFunc = s.depthMask = u;
                return;
            case CULL:
                s.caps[GLContextState.cap(GL_CULL_FACE)] = u;
                s.cullFace = s.frontFace = u;
                return;
            case STENCIL:
                s.caps[GLContextState.cap(GL_STENCIL_TEST)] = u;
                Arrays.fill(s.stencilFunc, u);
                Arrays.fill(s.stencilRef, u);
                Arrays.fill(s.stencilValueMask, u);
                Arrays.fill(s.stencilFail, u);
                Arrays.fill(s.stencilDepthFail, u);
                Arrays.fill(s.stencilPass, u);
                Arrays.fill(s.stencilWriteMask, u);
                return;
            case POLYGON:
                s.caps[GLContextState.cap(GL_POLYGON_OFFSET_FILL)] = u;
                s.caps[GLContextState.cap(GL_POLYGON_OFFSET_LINE)] = u;
                s.caps[GLContextState.cap(GL_POLYGON_OFFSET_POINT)] = u;
                s.polygonMode = u;
                s.offsetFactor = s.offsetUnits = Float.NaN;
                return;
            case VIEWPORT:
                s.viewportX = s.viewportY = s.viewportW = s.viewportH = Float.NaN;
                return;
            case SCISSOR:
                s.caps[GLContextState.cap(GL_SCISSOR_TEST)] = u;
                s.scissorX = s.scissorY = s.scissorW = s.scissorH = u;
                return;
            case TEXTURES:
                Arrays.fill(s.unitTexture, u);
                return;
            default:
                throw new IllegalArgumentException(domain + " has no attribute group");
        }
    }

    void read(GLHostState.Domain domain, GLContextState s) {
        switch (domain) {
            case BLEND:
                for (int i = 0; i < GLContextState.DRAW_BUFFERS; i++) {
                    s.blendEnabled[i] = gl.glIsEnabledi(GL_BLEND, i) ? 1 : 0;
                    s.blendSrcRgb[i] = indexed(GL_BLEND_SRC_RGB, i);
                    s.blendDstRgb[i] = indexed(GL_BLEND_DST_RGB, i);
                    s.blendSrcAlpha[i] = indexed(GL_BLEND_SRC_ALPHA, i);
                    s.blendDstAlpha[i] = indexed(GL_BLEND_DST_ALPHA, i);
                    s.blendEqRgb[i] = indexed(GL_BLEND_EQUATION_RGB, i);
                    s.blendEqAlpha[i] = indexed(GL_BLEND_EQUATION_ALPHA, i);
                }
                return;
            case COLOR_MASK:
                for (int i = 0; i < GLContextState.DRAW_BUFFERS; i++) {
                    gl.glGetIntegeri_v(GL_COLOR_WRITEMASK, i, four);
                    s.colorMask[i] = (four[0] != 0 ? 1 : 0) | (four[1] != 0 ? 2 : 0) | (four[2] != 0 ? 4 : 0)
                            | (four[3] != 0 ? 8 : 0);
                }
                return;
            case DEPTH:
                cap(s, GL_DEPTH_TEST);
                s.depthFunc = gl.glGetInteger(GL_DEPTH_FUNC);
                s.depthMask = gl.glGetInteger(GL_DEPTH_WRITEMASK) != 0 ? 1 : 0;
                return;
            case CULL:
                cap(s, GL_CULL_FACE);
                s.cullFace = gl.glGetInteger(GL_CULL_FACE_MODE);
                s.frontFace = gl.glGetInteger(GL_FRONT_FACE);
                return;
            case STENCIL:
                cap(s, GL_STENCIL_TEST);
                for (int f = 0; f < 2; f++) {
                    s.stencilFunc[f] = gl.glGetInteger(STENCIL[f][0]);
                    s.stencilRef[f] = gl.glGetInteger(STENCIL[f][1]);
                    s.stencilValueMask[f] = gl.glGetInteger(STENCIL[f][2]);
                    s.stencilFail[f] = gl.glGetInteger(STENCIL[f][3]);
                    s.stencilDepthFail[f] = gl.glGetInteger(STENCIL[f][4]);
                    s.stencilPass[f] = gl.glGetInteger(STENCIL[f][5]);
                    s.stencilWriteMask[f] = gl.glGetInteger(STENCIL[f][6]);
                }
                return;
            case POLYGON:
                cap(s, GL_POLYGON_OFFSET_FILL);
                cap(s, GL_POLYGON_OFFSET_LINE);
                cap(s, GL_POLYGON_OFFSET_POINT);
                // A core profile writes one mode (both faces); a compatibility profile front and back
                two[0] = two[1] = GLContextState.UNKNOWN;
                gl.glGetIntegerv(GL_POLYGON_MODE, two);
                s.polygonMode = two[1] == GLContextState.UNKNOWN || two[0] == two[1] ? two[0] : GLContextState.UNKNOWN;
                s.offsetFactor = gl.glGetFloat(GL_POLYGON_OFFSET_FACTOR);
                s.offsetUnits = gl.glGetFloat(GL_POLYGON_OFFSET_UNITS);
                return;
            case VIEWPORT:
                gl.glGetFloatv(GL_VIEWPORT, f4);
                s.viewportX = f4[0];
                s.viewportY = f4[1];
                s.viewportW = f4[2];
                s.viewportH = f4[3];
                return;
            case SCISSOR:
                cap(s, GL_SCISSOR_TEST);
                gl.glGetIntegerv(GL_SCISSOR_BOX, four);
                s.scissorX = four[0];
                s.scissorY = four[1];
                s.scissorW = four[2];
                s.scissorH = four[3];
                return;
            case PROGRAM:
                s.program = gl.glGetInteger(GL_CURRENT_PROGRAM);
                return;
            case FRAMEBUFFERS:
                s.drawFramebuffer = gl.glGetInteger(GL_DRAW_FRAMEBUFFER_BINDING);
                s.readFramebuffer = gl.glGetInteger(GL_READ_FRAMEBUFFER_BINDING);
                return;
            case VERTEX_ARRAY:
                s.vertexArray = gl.glGetInteger(GL_VERTEX_ARRAY_BINDING);
                return;
            case TEXTURES: {
                int active = gl.glGetInteger(GL_ACTIVE_TEXTURE);
                for (int i = 0; i < GLContextState.UNITS; i++) {
                    gl.glActiveTexture(GL_TEXTURE0 + i);
                    s.unitTexture[i] = gl.glGetInteger(GL_TEXTURE_BINDING_2D);
                    s.unitSampler[i] = gl.glGetInteger(GL_SAMPLER_BINDING);
                }
                gl.glActiveTexture(active);
                return;
            }
            default:
                throw new IllegalArgumentException(String.valueOf(domain));
        }
    }

    /** What no domain holds: the device's conventions, the depth range, pixel store, indexed and generic buffers. */
    void rest(GLContextState s) {
        cap(s, GL_FRAMEBUFFER_SRGB);
        cap(s, GL_TEXTURE_CUBE_MAP_SEAMLESS);
        cap(s, GL_PROGRAM_POINT_SIZE);
        gl.glGetFloatv(GL_DEPTH_RANGE, f2);
        s.depthNear = f2[0];
        s.depthFar = f2[1];
        s.clipOrigin = gl.glGetInteger(GL_CLIP_ORIGIN);
        s.clipDepth = gl.glGetInteger(GL_CLIP_DEPTH_MODE);
        for (int i = 0; i < GLContextState.PIXEL_STORE.length; i++) s.pixelStore[i] = gl.glGetInteger(GLContextState.PIXEL_STORE[i]);
        for (int i = 0; i < GLContextState.POINTS; i++) {
            s.uniformBuffer[i] = indexed(GL_UNIFORM_BUFFER_BINDING, i);
            s.uniformOffset[i] = gl.glGetInteger64i(GL_UNIFORM_BUFFER_START, i);
            s.uniformSize[i] = gl.glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, i);
            s.storageBuffer[i] = indexed(GL_SHADER_STORAGE_BUFFER_BINDING, i);
            s.storageOffset[i] = gl.glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, i);
            s.storageSize[i] = gl.glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, i);
        }
        for (int i = 0; i < BINDINGS.length; i++) s.targetBuffer[i] = gl.glGetInteger(BINDINGS[i]);
    }

    /**
     * The host's alpha test off for the section, where it was on: {@code on} what the host believes, or
     * {@link GLContextState#UNKNOWN} to ask GL.
     */
    void alphaOff(int on) {
        if (!compatibility) return;
        if ((pushed & GL_ENABLE_BIT) != 0) {
            // The enable group is on the stack: the pop turns it back on where it was, so no need to ask
            gl.glDisable(GL_ALPHA_TEST);
            alphaOff = false;
            return;
        }
        alphaOff = on == GLContextState.UNKNOWN ? gl.glIsEnabled(GL_ALPHA_TEST) : on == 1;
        if (alphaOff) gl.glDisable(GL_ALPHA_TEST);
    }

    /** {@link #alphaOff}'s test back on. */
    void alphaBack(CgGLDriver through) {
        if (!alphaOff) return;
        alphaOff = false;
        through.glEnable(GL_ALPHA_TEST);
    }

    /**
     * What a host with a cache never declares, as GL's defaults: no pixel-transfer buffers or samplers bound, the
     * device's conventions off, the depth range whole, rows aligned to 4, and the host's clip range ({@code -1..1}, or
     * {@code 0..1} where it set clip control once, as Minecraft 26.2 does on GL).
     */
    static void defaults(CgGLDriver gl, boolean clipZeroToOne) {
        gl.glBindBuffer(GL_PIXEL_PACK_BUFFER, 0);
        gl.glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
        for (int unit = 0; unit < GLContextState.UNITS; unit++) gl.glBindSampler(unit, 0);
        gl.glDisable(GL_FRAMEBUFFER_SRGB);
        gl.glDisable(GL_TEXTURE_CUBE_MAP_SEAMLESS);
        gl.glDisable(GL_PROGRAM_POINT_SIZE);
        gl.glClipControl(GL_LOWER_LEFT, clipZeroToOne ? GL_ZERO_TO_ONE : GL_NEGATIVE_ONE_TO_ONE);
        gl.glDepthRangef(0f, 1f);
        gl.glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        gl.glPixelStorei(GL_PACK_ALIGNMENT, 4);
    }

    private void cap(GLContextState s, int cap) {
        s.caps[GLContextState.cap(cap)] = gl.glIsEnabled(cap) ? 1 : 0;
    }

    private int indexed(int name, int index) {
        gl.glGetIntegeri_v(name, index, one);
        return one[0];
    }
}
