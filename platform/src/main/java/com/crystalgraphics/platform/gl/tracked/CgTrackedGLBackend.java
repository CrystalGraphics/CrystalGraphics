package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.CgDeviceInfo;
import com.crystalgraphics.platform.device.command.CgAccess;
import com.crystalgraphics.platform.device.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureRegion;
import com.crystalgraphics.platform.device.resource.CgTimerQuery;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.device.shader.CgShaderModule;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.CgGLBackend;
import com.crystalgraphics.platform.gl.tracked.gl.GlEnums;
import com.crystalgraphics.platform.gl.tracked.gl.GlNames;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedBuffers;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedFramebuffers;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedGlErrors;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedPrograms;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedRenderState;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedTextures;
import com.crystalgraphics.platform.gl.tracked.gl.TrackedVertexArrays;
import com.crystalgraphics.platform.gl.tracked.memory.CgAllocation;
import com.crystalgraphics.platform.gl.tracked.tracker.CgDrawState;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTarget;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTrackedProgram;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTracker;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTrackerStats;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * {@code CgGLBackend} over a {@link CgDevice}: the GL subset CrystalGraphics calls (spec §5.0), with GL's own
 * semantics, turned into passes, pipelines and per-draw bindings by a {@link CgTracker}. Nothing above
 * {@code CgGL} changes; a process installs one backend.
 *
 * <pre>{@code
 * CgTrackedGLBackend gl = new CgTrackedGLBackend(device, new ShadercGlslCompiler(), debug);
 * CgGL.init(gl);
 * CgCapabilities.init(new CgTrackedGLContext());
 * ... draw through the engine as on GL ...
 * gl.endFrame();          // once a frame, before the host presents
 * }</pre>
 *
 * <p>What GL allows and a device cannot do throws {@code UnsupportedOperationException} naming it: a sampler
 * object, a texture swizzle, 8-bit indices, a draw buffer after {@code GL_NONE}. The census ({@code CENSUS.md} beside
 * {@code CgGLBackend}) lists what core, CrystalGUI and the hosts reach.</p>
 */
public final class CgTrackedGLBackend extends CgGLBackend {

    private static final int GL_MAX_RENDERBUFFER_SIZE = 0x84E8, GL_MAX_COLOR_ATTACHMENTS = 0x8CDF;
    private static final int GL_MAX_UNIFORM_BLOCK_SIZE = 0x8A30, GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT = 0x8A34;
    private static final int GL_COMPUTE_SHADER_BIT = 0x20;
    private static final int GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT = 0x90DF, GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT = 0x919F;
    private static final int GL_MAX_TEXTURE_BUFFER_SIZE = 0x8C2B, GL_MAX_VIEWPORT_DIMS = 0x0D3A;
    private static final int GL_MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF;

    private final CgDevice device;
    private final CgTracker tracker;
    private final TrackedGlErrors errors = new TrackedGlErrors();
    private final TrackedRenderState state;
    private final TrackedVertexArrays vaos;
    private final TrackedBuffers buffers;
    private final TrackedPrograms programs;
    private final TrackedTextures textures;
    private final TrackedFramebuffers framebuffers;
    private final boolean debug;
    private final CgGlslCompiler compiler;
    private final CgTrackedProgram[] clearPrograms = new CgTrackedProgram[8];
    private final double[] q = new double[16];
    private final Map<Long, Long> syncs = new HashMap<>();
    private final GlNames<CgTimerQuery> queries = new GlNames<>("Query");
    private long nextSync = 1;
    private CgTimerQuery timing;

    /**
     * @param compiler what a program's link compiles its GLSL with
     * @param debug    refuse what GL leaves undefined and a device cannot survive: feedback loops (decision 21)
     */
    public CgTrackedGLBackend(CgDevice device, CgGlslCompiler compiler, boolean debug) {
        this.device = device;
        this.debug = debug;
        this.tracker = new CgTracker(device, debug);
        this.compiler = compiler;
        tracker.setClearPrograms(this::clearProgram);
        CgTarget surface = CgTarget.surface(device);
        this.state = new TrackedRenderState(tracker, errors, surface.width(), surface.height());
        this.vaos = new TrackedVertexArrays(errors);
        this.buffers = new TrackedBuffers(tracker, errors, vaos);
        vaos.buffers(buffers);
        this.programs = new TrackedPrograms(tracker, compiler, errors);
        this.textures = new TrackedTextures(tracker, errors, buffers);
        this.framebuffers = new TrackedFramebuffers(tracker, errors, textures, buffers);
        tracker.bindTarget(surface);
    }

    public CgTracker tracker() { return tracker; }

    private static final String CLEAR_VERTEX = """
            #version 330 core
            in vec2 cg_ClearPosition;
            in vec4 cg_ClearColor;
            out vec4 v_color;
            void main() { gl_Position = vec4(cg_ClearPosition, 0.0, 1.0); v_color = cg_ClearColor; }
            """;

    /** One output per attachment: the pipeline's write masks choose which one the clear reaches. */
    private static String clearFragment(int colors) {
        StringBuilder s = new StringBuilder("#version 330 core\nin vec4 v_color;\n");
        for (int i = 0; i < colors; i++) s.append("layout(location = ").append(i).append(") out vec4 o").append(i).append(";\n");
        s.append("void main() {\n");
        for (int i = 0; i < colors; i++) s.append("    o").append(i).append(" = v_color;\n");
        return s.append("}\n").toString();
    }

    /** What a colour clear under a partial write mask draws with, for a target of {@code colors} attachments. */
    private CgTrackedProgram clearProgram(int colors) {
        CgTrackedProgram cached = clearPrograms[colors - 1];
        if (cached != null) return cached;
        String label = "masked clear " + colors;
        CgGlslCompiler.Program p = compiler.compile(CLEAR_VERTEX, clearFragment(colors),
                Map.of("cg_ClearPosition", 0, "cg_ClearColor", 1), label);
        return clearPrograms[colors - 1] = new CgTrackedProgram(label, device.createBindingLayout(label, p.slots()),
                device.createShaderModule(CgShaderModule.Stage.VERTEX, p.vertexGlDepth(), label),
                device.createShaderModule(CgShaderModule.Stage.VERTEX, p.vertexZeroToOne(), label),
                device.createShaderModule(CgShaderModule.Stage.FRAGMENT, p.fragment(), label));
    }

    public CgTrackerStats stats() { return tracker.stats(); }

    /**
     * Compiles in the background from now on: a link returns at once with shaderc running on a worker, and the
     * program's first use waits for what is left, as a driver with {@code KHR_parallel_shader_compile} does. A host
     * turns it on; a test leaves every link finished at its call.
     *
     * <pre>{@code
     * CgTrackedGLBackend gl = new CgTrackedGLBackend(device, new ShadercGlslCompiler(spirvCache), false)
     *         .compileInBackground();
     * }</pre>
     */
    public CgTrackedGLBackend compileInBackground() {
        programs.compileInBackground();
        return this;
    }

    /** Ends the frame: every pass ends and the device submits. The host presents after it. */
    public void endFrame() { tracker.endFrame(); }

    TrackedRenderState renderState() { return state; }

    public TrackedVertexArrays vertexArrays() { return vaos; }

    public TrackedBuffers bufferObjects() { return buffers; }

    TrackedTextures textureObjects() { return textures; }

    TrackedPrograms programObjects() { return programs; }

    TrackedFramebuffers framebufferObjects() { return framebuffers; }

    // ── lifecycle and context ──────────────────────────────────────────────────

    @Override public void initContext() {}

    @Override public boolean isAvailable() { return true; }

    @Override public int getPriority() { return 0; }

    @Override public boolean isContextCurrent() { return device.ownedByCurrentThread(); }

    @Override public boolean ownedByCurrentThread() { return device.ownedByCurrentThread(); }

    @Override public int glGetError() { return errors.take(); }

    // ── queries ────────────────────────────────────────────────────────────────

    private int query(int pname) {
        int n = state.query(pname, q);
        if (n < 0) n = buffers.query(pname, q);
        if (n < 0) n = vaos.query(pname, q);
        if (n < 0) n = programs.query(pname, q);
        if (n < 0) n = textures.query(pname, q);
        if (n < 0) n = framebuffers.query(pname, q);
        if (n < 0) n = limits(pname);
        if (n < 0) {
            errors.invalidEnum("glGet", pname);
            q[0] = 0;
            n = 1;
        }
        return n;
    }

    private int limits(int pname) {
        CgDeviceInfo.Limits l = device.info().limits();
        switch (pname) {
            case CgGL.GL_MAX_TEXTURE_SIZE: case GL_MAX_RENDERBUFFER_SIZE: return one(l.maxTextureSize());
            case CgGL.GL_MAX_3D_TEXTURE_SIZE:          return one(l.max3DTextureSize());
            case CgGL.GL_MAX_ARRAY_TEXTURE_LAYERS:     return one(l.maxArrayLayers());
            case CgGL.GL_MAX_TEXTURE_IMAGE_UNITS:
            case CgGL.GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS: return one(l.maxTextureUnits());
            case CgGL.GL_MAX_DRAW_BUFFERS: case GL_MAX_COLOR_ATTACHMENTS: return one(l.maxColorAttachments());
            case CgGL.GL_MAX_VERTEX_ATTRIBS:           return one(l.maxVertexAttributes());
            case CgGL.GL_MAX_SAMPLES: case CgGL.GL_MAX_COLOR_TEXTURE_SAMPLES:
            case CgGL.GL_MAX_DEPTH_TEXTURE_SAMPLES:    return one(l.maxSamples());
            case CgGL.GL_MAX_UNIFORM_BUFFER_BINDINGS:  return one(l.maxUniformBufferBindings());
            case CgGL.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS: return one(l.maxStorageBufferBindings());
            case GL_MAX_UNIFORM_BLOCK_SIZE:            return one(l.maxUniformBlockSize());
            case GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT:   return one(l.uniformOffsetAlignment());
            case GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT: return one(l.storageOffsetAlignment());
            case GL_TEXTURE_BUFFER_OFFSET_ALIGNMENT:   return one(l.texelOffsetAlignment());
            case GL_MAX_TEXTURE_BUFFER_SIZE:           return one(l.maxTexelBufferElements());
            case GL_MAX_VIEWPORT_DIMS:                 q[0] = q[1] = l.maxViewportSize(); return 2;
            case GL_MAX_TEXTURE_MAX_ANISOTROPY:        return one(l.maxAnisotropy());
            case CgGL.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE:     return one(l.compute().sharedMemory());
            case CgGL.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS: return one(l.compute().invocations());
            case CgGL.GL_SUBGROUP_SIZE_KHR:                  return one(l.compute().subgroupSize());
            case CgGL.GL_SUBGROUP_SUPPORTED_STAGES_KHR:      return one(l.compute().subgroupOperations() != 0 ? GL_COMPUTE_SHADER_BIT : 0);
            case CgGL.GL_SUBGROUP_SUPPORTED_FEATURES_KHR:    return one(l.compute().subgroupOperations());
            case CgGL.GL_CONTEXT_PROFILE_MASK:         return one(1);
            case CgGL.GL_MAJOR_VERSION:                return one(4);
            case CgGL.GL_MINOR_VERSION:                return one(4);
            case CgGL.GL_NUM_EXTENSIONS:               return one(0);
            default: return -1;
        }
    }

    private int one(double v) {
        q[0] = v;
        return 1;
    }

    @Override public int glGetInteger(int pname) { query(pname); return (int) q[0]; }

    @Override
    public void glGetInteger(int pname, IntBuffer params) {
        int n = query(pname);
        for (int i = 0; i < n; i++) params.put(params.position() + i, (int) q[i]);
    }

    @Override public boolean glGetBoolean(int pname) { query(pname); return q[0] != 0; }

    @Override
    public void glGetBoolean(int pname, ByteBuffer params) {
        int n = query(pname);
        for (int i = 0; i < n; i++) params.put(params.position() + i, (byte) (q[i] != 0 ? 1 : 0));
    }

    @Override public float glGetFloat(int pname) { query(pname); return (float) q[0]; }

    /** The device every call lands on. */
    public CgDevice device() {
        return device;
    }

    /** The device's own name, vendor and driver, as GL 4.4 core. */
    @Override
    public String glGetString(int name) {
        CgDeviceInfo info = device.info();
        switch (name) {
            case CgGL.GL_VENDOR:                   return info.vendor();
            case CgGL.GL_RENDERER:                 return info.name();
            case CgGL.GL_VERSION:                  return "4.4 CrystalGraphics tracked, driver " + info.driver();
            case CgGL.GL_SHADING_LANGUAGE_VERSION: return "4.40";
            default: throw new IllegalArgumentException("glGetString(0x" + Integer.toHexString(name) + ") is not modelled");
        }
    }

    /** Lists no extensions ({@code GL_NUM_EXTENSIONS} is 0): the device says what it has, through {@code CgDevice.describe}. */
    @Override
    public String glGetStringi(int name, int index) {
        throw new IllegalArgumentException("the tracked backend lists no extensions");
    }

    @Override
    public int glGetIntegeri(int target, int index) {
        switch (target) {
            case CgGL.GL_SHADER_STORAGE_BUFFER_BINDING: return buffers.storageName[index];
            case CgGL.GL_SHADER_STORAGE_BUFFER_START:   return (int) buffers.storageOffset[index];
            case CgGL.GL_SHADER_STORAGE_BUFFER_SIZE:    return (int) Math.max(0, buffers.storageSize[index]);
            case CgGL.GL_IMAGE_BINDING_NAME:    return textures.imageUnit(index)[0];
            case CgGL.GL_IMAGE_BINDING_LEVEL:   return textures.imageUnit(index)[1];
            case CgGL.GL_IMAGE_BINDING_LAYERED: return textures.imageUnit(index)[2] < 0 ? 1 : 0;
            case CgGL.GL_IMAGE_BINDING_LAYER:   return Math.max(0, textures.imageUnit(index)[2]);
            case CgGL.GL_IMAGE_BINDING_FORMAT:  return textures.imageUnit(index)[3];
            case CgGL.GL_IMAGE_BINDING_ACCESS:  return textures.imageUnit(index)[4];
            case CgGL.GL_MAX_COMPUTE_WORK_GROUP_SIZE: {
                CgDeviceInfo.Compute c = device.info().limits().compute();
                return index == 0 ? c.sizeX() : index == 1 ? c.sizeY() : c.sizeZ();
            }
            case CgGL.GL_MAX_COMPUTE_WORK_GROUP_COUNT: {
                CgDeviceInfo.Compute c = device.info().limits().compute();
                return index == 0 ? c.countX() : index == 1 ? c.countY() : c.countZ();
            }
            default:
                throw new IllegalArgumentException("glGetIntegeri(0x" + Integer.toHexString(target) + ") is not modelled");
        }
    }

    @Override
    public void glGetFloat(int pname, FloatBuffer params) {
        int n = query(pname);
        for (int i = 0; i < n; i++) params.put(params.position() + i, (float) q[i]);
    }

    // ── state ──────────────────────────────────────────────────────────────────

    @Override public void glEnable(int cap) { state.enable(cap, true); }

    @Override public void glDisable(int cap) { state.enable(cap, false); }

    @Override public void glBlendFunc(int s, int d) { state.blendFunc(s, d, s, d); }

    @Override public void glBlendFuncSeparate(int sRgb, int dRgb, int sA, int dA) { state.blendFunc(sRgb, dRgb, sA, dA); }

    @Override public void glBlendEquationSeparate(int rgb, int alpha) { state.blendEquation(rgb, alpha); }

    @Override public void glDepthMask(boolean flag) { state.depthMask(flag); }

    @Override public void glDepthFunc(int func) { state.depthFunc(func); }

    @Override public void glCullFace(int mode) { state.cullFace(mode); }

    @Override public void glFrontFace(int mode) { state.frontFace(mode); }

    @Override public void glPolygonMode(int face, int mode) { state.polygonMode(face, mode); }

    @Override public void glPolygonOffset(float factor, float units) { state.polygonOffset(factor, units); }

    @Override public void glColorMask(boolean r, boolean g, boolean b, boolean a) { state.colorMask(-1, r, g, b, a); }

    @Override public void glColorMaski(int buf, boolean r, boolean g, boolean b, boolean a) { state.colorMask(buf, r, g, b, a); }

    @Override public void glStencilFunc(int func, int ref, int mask) { state.stencilFunc(func, ref, mask); }

    @Override public void glStencilOp(int sfail, int dpfail, int dppass) { state.stencilOp(sfail, dpfail, dppass); }

    @Override public void glStencilMask(int mask) { state.stencilMask(mask); }

    @Override public void glViewport(int x, int y, int w, int h) { state.viewport(x, y, w, h); }

    @Override public void glScissor(int x, int y, int w, int h) { state.scissor(x, y, w, h); }

    @Override public void glLineWidth(float width) { state.lineWidth(width); }

    @Override public void glPointSize(float size) { state.pointSize(size); }

    @Override public void glAlphaFunc(int func, float ref) { state.alphaFunc(func, ref); }

    @Override public void glClearColor(float r, float g, float b, float a) {
        state.clearR = r; state.clearG = g; state.clearB = b; state.clearA = a;
    }

    @Override public void glClearDepth(double depth) { state.clearDepth = depth; }

    @Override public void glClearStencil(int s) { state.clearStencil = s; }

    @Override
    public void glClear(int mask) {
        if (framebuffers.applyDraw()) state.clear(mask);
        else errors.invalidFramebufferOperation("glClear with nothing attached to the framebuffer");
    }

    // ── framebuffers ───────────────────────────────────────────────────────────

    @Override public void bindFramebuffer(int target, int fbo) { framebuffers.bind(target, fbo); }

    @Override
    public void blitFramebuffer(int srcX0, int srcY0, int srcX1, int srcY1, int dstX0, int dstY0, int dstX1, int dstY1,
                                int mask, int filter) {
        framebuffers.blit(srcX0, srcY0, srcX1, srcY1, dstX0, dstY0, dstX1, dstY1, mask, filter);
    }

    @Override
    public void framebufferTextureLayer(int target, int attachment, int texture, int level, int layer) {
        framebuffers.textureLayer(target, attachment, texture, level, layer);
    }

    @Override public int genFramebuffers() { return framebuffers.gen(); }

    @Override public void deleteFramebuffers(int fbo) { framebuffers.delete(fbo); }

    @Override
    public void framebufferTexture2D(int target, int attachment, int texTarget, int texture, int level) {
        framebuffers.texture(target, attachment, texTarget, texture, level);
    }

    @Override public int checkFramebufferStatus(int target) { return framebuffers.status(target); }

    @Override public void drawBuffers(IntBuffer bufs) { framebuffers.drawBuffers(bufs); }

    @Override
    public int getFramebufferAttachmentParameteriv(int target, int attachment, int pname) {
        return framebuffers.attachmentParameter(target, attachment, pname);
    }

    @Override public void glDrawBuffer(int mode) { framebuffers.drawBuffer(mode); }

    @Override public void glReadBuffer(int mode) { framebuffers.readBuffer(mode); }

    @Override public int glGenRenderbuffers() { return framebuffers.genRenderbuffer(); }

    @Override public void glDeleteRenderbuffers(int rbo) { framebuffers.deleteRenderbuffer(rbo); }

    @Override public void glBindRenderbuffer(int target, int renderbuffer) { framebuffers.bindRenderbuffer(renderbuffer); }

    @Override
    public void glRenderbufferStorage(int target, int internalFormat, int width, int height) {
        framebuffers.renderbufferStorage(1, internalFormat, width, height);
    }

    @Override
    public void glRenderbufferStorageMultisample(int target, int samples, int internalFormat, int width, int height) {
        framebuffers.renderbufferStorage(samples, internalFormat, width, height);
    }

    @Override
    public void glFramebufferRenderbuffer(int target, int attachment, int renderbufferTarget, int renderbuffer) {
        framebuffers.renderbufferAttachment(target, attachment, renderbuffer);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, ByteBuffer pixels) {
        framebuffers.readPixels(x, y, width, height, format, type, textures.pack, pixels);
    }

    @Override
    public void glReadPixels(int x, int y, int width, int height, int format, int type, long packOffset) {
        framebuffers.readPixels(x, y, width, height, format, type, packOffset);
    }

    @Override
    public void copyImageSubData(int srcName, int srcTarget, int srcLevel, int srcX, int srcY, int srcZ,
                                 int dstName, int dstTarget, int dstLevel, int dstX, int dstY, int dstZ,
                                 int srcWidth, int srcHeight, int srcDepth) {
        CgGpuTexture src = image(srcName, srcTarget), dst = image(dstName, dstTarget);
        if (src == null || dst == null) {
            errors.invalidValue("glCopyImageSubData between images that do not exist");
            return;
        }
        tracker.transfer().copyTexture(src, new CgTextureRegion(srcLevel, srcX, srcY, srcZ, srcWidth, srcHeight, srcDepth),
                dst, new CgTextureRegion(dstLevel, dstX, dstY, dstZ, srcWidth, srcHeight, srcDepth));
    }

    private CgGpuTexture image(int name, int target) {
        if (target == CgGL.GL_RENDERBUFFER) return framebuffers.renderbufferImage(name);
        TrackedTextures.GlTexture t = textures.get(name);
        return t == null ? null : t.image;
    }

    // ── programs ───────────────────────────────────────────────────────────────

    @Override public int glCreateShader(int type) { return programs.createShader(type); }

    @Override public void glShaderSource(int shader, CharSequence source) { programs.source(shader, source); }

    @Override public void glCompileShader(int shader) {}

    @Override public int glGetShaderi(int shader, int pname) { return programs.shaderi(shader, pname); }

    @Override public String glGetShaderInfoLog(int shader, int maxLength) { return ""; }

    @Override public void glDeleteShader(int shader) { programs.deleteShader(shader); }

    @Override public int glCreateProgram() { return programs.createProgram(); }

    @Override public void glAttachShader(int program, int shader) { programs.attach(program, shader); }

    @Override public void glDetachShader(int program, int shader) { programs.detach(program, shader); }

    @Override
    public void glGetAttachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        programs.attachedShaders(program, count, shaders);
    }

    @Override public void glLinkProgram(int program) { programs.link(program); }

    @Override public int glGetProgrami(int program, int pname) { return programs.programi(program, pname); }

    @Override public String glGetProgramInfoLog(int program, int maxLength) { return programs.log(program, maxLength); }

    @Override public void glUseProgram(int program) { programs.use(program); }

    @Override public void glDeleteProgram(int program) { programs.deleteProgram(program); }

    @Override public int glGetUniformLocation(int program, CharSequence name) { return programs.uniformLocation(program, name); }

    @Override
    public String glGetActiveUniform(int program, int index, int maxLength, IntBuffer sizeTypeBuf) {
        return programs.activeUniform(program, index, maxLength, sizeTypeBuf);
    }

    @Override
    public void glBindAttribLocation(int program, int index, CharSequence name) {
        programs.bindAttribLocation(program, index, name);
    }

    @Override
    public int glGetProgramResourceIndex(int program, int programInterface, CharSequence name) {
        return programs.resourceIndex(program, programInterface, name);
    }

    @Override
    public void glShaderStorageBlockBinding(int program, int storageBlockIndex, int storageBlockBinding) {
        programs.storageBlockBinding(program, storageBlockIndex, storageBlockBinding);
    }

    @Override public int glGetUniformBlockIndex(int program, CharSequence name) { return programs.uniformBlockIndex(program, name); }

    @Override
    public void glUniformBlockBinding(int program, int uniformBlockIndex, int uniformBlockBinding) {
        programs.uniformBlockBinding(program, uniformBlockIndex, uniformBlockBinding);
    }

    @Override public void glUniform1i(int location, int v0) { programs.int1(location, v0); }

    @Override public void glUniform1f(int location, float v0) { programs.floats(location, v0, 0, 0, 0, 1); }

    @Override public void glUniform2f(int location, float v0, float v1) { programs.floats(location, v0, v1, 0, 0, 2); }

    @Override public void glUniform3f(int location, float v0, float v1, float v2) { programs.floats(location, v0, v1, v2, 0, 3); }

    @Override
    public void glUniform4f(int location, float v0, float v1, float v2, float v3) {
        programs.floats(location, v0, v1, v2, v3, 4);
    }

    @Override public void glUniform1(int location, FloatBuffer values) { programs.floatArray(location, values); }

    @Override public void glUniform1(int location, IntBuffer values) { programs.intArray(location, values); }

    @Override public void glUniformMatrix3(int location, boolean transpose, FloatBuffer value) { programs.matrix(location, transpose, value, 3); }

    @Override public void glUniformMatrix4(int location, boolean transpose, FloatBuffer value) { programs.matrix(location, transpose, value, 4); }

    @Override public void glUniformMatrix4fv(int location, boolean transpose, FloatBuffer value) { programs.matrix(location, transpose, value, 4); }

    // ── buffers and vertex arrays ──────────────────────────────────────────────

    @Override public int glGenBuffers() { return buffers.gen(); }

    @Override public void glBindBuffer(int target, int buffer) { buffers.bind(target, buffer); }

    @Override public void glBufferData(int target, ByteBuffer data, int usage) {
        buffers.data(target, data.remaining(), data, usage);
    }

    @Override public void glBufferData(int target, ShortBuffer data, int usage) {
        ByteBuffer bytes = ByteBuffer.allocateDirect(data.remaining() * 2).order(ByteOrder.nativeOrder());
        bytes.asShortBuffer().put(data.duplicate());
        buffers.data(target, bytes.remaining(), bytes, usage);
    }

    @Override public void glBufferData(int target, long size, int usage) { buffers.data(target, size, null, usage); }

    @Override public void glBufferSubData(int target, long offset, ByteBuffer data) { buffers.subData(target, offset, data); }

    @Override
    public void glCopyBufferSubData(int readTarget, int writeTarget, long readOffset, long writeOffset, long size) {
        buffers.copy(readTarget, writeTarget, readOffset, writeOffset, size);
    }

    @Override public void glDeleteBuffers(int buffer) { buffers.delete(buffer); }

    @Override public void glBindBufferBase(int target, int index, int buffer) { buffers.bindIndexed(target, index, buffer, 0, -1); }

    @Override
    public void glBindBufferRange(int target, int index, int buffer, long offset, long size) {
        buffers.bindIndexed(target, index, buffer, offset, size);
    }

    @Override public void glTexBuffer(int target, int internalFormat, int buffer) { textures.textureBuffer(target, internalFormat, buffer); }

    @Override
    public ByteBuffer glMapBufferRange(int target, long offset, long length, int access, ByteBuffer oldBuffer) {
        return buffers.map(target, offset, length, access);
    }

    @Override public boolean glUnmapBuffer(int target) { return buffers.unmap(target); }

    @Override public void glFlushMappedBufferRange(int target, long offset, long length) {}

    @Override public void glBufferStorage(int target, long size, int flags) { buffers.storage(target, size, flags); }

    @Override public int glGenVertexArrays() { return vaos.gen(); }

    @Override public void glBindVertexArray(int array) { vaos.bind(array); }

    @Override public void glDeleteVertexArrays(int array) { vaos.delete(array); }

    @Override public void glEnableVertexAttribArray(int index) { vaos.enable(index); }

    @Override
    public void glVertexAttribPointer(int index, int size, int type, boolean normalized, int stride, long pointer) {
        vaos.pointer(index, size, type, normalized, false, stride, pointer);
    }

    @Override
    public void glVertexAttribIPointer(int index, int size, int type, int stride, long pointer) {
        vaos.pointer(index, size, type, false, true, stride, pointer);
    }

    @Override public void glVertexAttribDivisor(int index, int divisor) { vaos.divisor(index, divisor); }

    // ── textures ───────────────────────────────────────────────────────────────

    @Override public int glGenTextures() { return textures.gen(); }

    @Override public void glBindTexture(int target, int texture) { textures.bind(target, texture); }

    @Override
    public void glDeleteTextures(int texture) {
        textures.delete(texture);
        framebuffers.detach(false, texture);
    }

    @Override public void glActiveTexture(int texture) { textures.active(texture); }

    @Override public void glBindSampler(int unit, int sampler) { textures.bindSampler(unit, sampler); }

    @Override public void glTexParameteri(int target, int pname, int param) { textures.parameter(target, pname, param); }

    @Override public void glGenerateMipmap(int target) { textures.generateMipmap(target); }

    @Override public void glPixelStorei(int pname, int param) { textures.pixelStore(pname, param); }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                             int format, int type, ByteBuffer pixels) {
        textures.image(target, level, internalFormat, width, height, 1, format, type, pixels);
    }

    @Override
    public void glTexImage2D(int target, int level, int internalFormat, int width, int height, int border,
                             int format, int type, FloatBuffer pixels) {
        textures.image(target, level, internalFormat, width, height, 1, format, type, bytes(pixels));
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                int format, int type, ByteBuffer pixels) {
        textures.subImage(target, level, xOffset, yOffset, 0, width, height, 1, format, type, pixels);
    }

    @Override
    public void glTexSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height,
                                int format, int type, FloatBuffer pixels) {
        textures.subImage(target, level, xOffset, yOffset, 0, width, height, 1, format, type, bytes(pixels));
    }

    @Override
    public void glTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border,
                             int format, int type, ByteBuffer pixels) {
        textures.image(target, level, internalFormat, width, height, depth, format, type, pixels);
    }

    @Override
    public void glTexImage3D(int target, int level, int internalFormat, int width, int height, int depth, int border,
                             int format, int type, FloatBuffer pixels) {
        textures.image(target, level, internalFormat, width, height, depth, format, type, bytes(pixels));
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height,
                                int depth, int format, int type, ByteBuffer pixels) {
        textures.subImage(target, level, xOffset, yOffset, zOffset, width, height, depth, format, type, pixels);
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height,
                                int depth, int format, int type, FloatBuffer pixels) {
        textures.subImage(target, level, xOffset, yOffset, zOffset, width, height, depth, format, type, bytes(pixels));
    }

    @Override
    public void glTexSubImage3D(int target, int level, int xOffset, int yOffset, int zOffset, int width, int height,
                                int depth, int format, int type, ShortBuffer pixels) {
        textures.subImage(target, level, xOffset, yOffset, zOffset, width, height, depth, format, type, bytes(pixels));
    }

    @Override
    public void glTexImage2DMultisample(int target, int samples, int internalFormat, int width, int height,
                                        boolean fixedSampleLocations) {
        textures.multisample(target, samples, internalFormat, width, height);
    }

    @Override
    public void glGetTexImage(int target, int level, int format, int type, ByteBuffer pixels) {
        textures.read(target, level, format, type, pixels);
    }

    /** A device image — hosted, the host's — under a GL name {@code CgTexture2D.wrap} can adopt. */
    @Override
    public int importHostTexture(Object hostHandle) {
        if (!(hostHandle instanceof CgGpuTexture image))
            throw new IllegalArgumentException("The tracked backend imports a CgGpuTexture, not " + hostHandle);
        return textures.adopt(image);
    }

    private static ByteBuffer bytes(FloatBuffer data) {
        if (data == null) return null;
        ByteBuffer b = ByteBuffer.allocateDirect(data.remaining() * 4).order(ByteOrder.nativeOrder());
        b.asFloatBuffer().put(data.duplicate());
        return b;
    }

    private static ByteBuffer bytes(ShortBuffer data) {
        if (data == null) return null;
        ByteBuffer b = ByteBuffer.allocateDirect(data.remaining() * 2).order(ByteOrder.nativeOrder());
        b.asShortBuffer().put(data.duplicate());
        return b;
    }

    // ── draws ──────────────────────────────────────────────────────────────────

    @Override public void glDrawArrays(int mode, int first, int count) { draw(mode, first, count, 1, -1, 0, 0); }

    @Override public void glDrawElements(int mode, int count, int type, long indices) { draw(mode, 0, count, 1, type, indices, 0); }

    @Override
    public void glDrawArraysInstanced(int mode, int first, int count, int instanceCount) {
        draw(mode, first, count, instanceCount, -1, 0, 0);
    }

    @Override
    public void glDrawElementsInstanced(int mode, int count, int type, long indices, int instanceCount) {
        draw(mode, 0, count, instanceCount, type, indices, 0);
    }

    @Override
    public void glDrawElementsInstancedBaseVertex(int mode, int count, int type, long indices, int instanceCount,
                                                  int baseVertex) {
        draw(mode, 0, count, instanceCount, type, indices, baseVertex);
    }

    /**
     * Builds the pipeline a {@code mode} draw would bind with the current program, vertex array, framebuffer and
     * state, without drawing: the device's own compiler takes the program then rather than at its first draw.
     * What the program reads need not be bound.
     *
     * <pre>{@code
     * CgGL.glUseProgram(program);
     * backend.buildPipeline(CgGL.GL_TRIANGLES);   // with an empty vertex array, every input reads (0, 0, 0, 1)
     * }</pre>
     */
    public void buildPipeline(int mode) {
        CgDrawState s = tracker.state;
        if (!framebuffers.applyDraw()) {
            errors.invalidFramebufferOperation("A pipeline for a framebuffer with nothing attached");
            return;
        }
        state.sync();
        vaos.apply(s);
        programs.applyProgram(s);
        programs.feedDisabledInputs(s);
        tracker.buildPipeline(GlEnums.topology(mode));
    }

    /** @param type the index type, or -1 for a draw of arrays */
    private void draw(int mode, int first, int count, int instances, int type, long indices, int baseVertex) {
        CgDrawState s = tracker.state;
        if (!prepareDraw(s)) return;
        CgPipelineDesc.Topology topology = GlEnums.topology(mode);
        if (type < 0) {
            tracker.draw(topology, count, instances, first, 0);
            return;
        }
        if (!indices(s, type)) return;
        tracker.drawIndexed(topology, count, instances, (int) (indices / (type == CgGL.GL_UNSIGNED_INT ? 4 : 2)),
                baseVertex, 0);
    }

    @Override public void glDrawArraysIndirect(int mode, long offset) { drawIndirect(mode, -1, offset, 1, 0, -1); }

    @Override
    public void glDrawElementsIndirect(int mode, int type, long offset) { drawIndirect(mode, type, offset, 1, 0, -1); }

    @Override
    public void glMultiDrawArraysIndirect(int mode, long offset, int drawCount, int stride) {
        drawIndirect(mode, -1, offset, drawCount, stride, -1);
    }

    @Override
    public void glMultiDrawElementsIndirect(int mode, int type, long offset, int drawCount, int stride) {
        drawIndirect(mode, type, offset, drawCount, stride, -1);
    }

    @Override
    public void glMultiDrawArraysIndirectCount(int mode, long offset, long countOffset, int maxDrawCount, int stride) {
        drawIndirect(mode, -1, offset, maxDrawCount, stride, countOffset);
    }

    @Override
    public void glMultiDrawElementsIndirectCount(int mode, int type, long offset, long countOffset, int maxDrawCount,
                                                 int stride) {
        drawIndirect(mode, type, offset, maxDrawCount, stride, countOffset);
    }

    /**
     * @param type        the index type, or -1 for a draw of arrays
     * @param stride      GL's: 0 for packed records
     * @param countOffset the draw count's place in the parameter buffer, or -1 for {@code draws} draws
     */
    private void drawIndirect(int mode, int type, long offset, int draws, int stride, long countOffset) {
        CgAllocation args = argumentsIn(buffers.drawIndirect, "An indirect draw"), count = null;
        if (args == null) return;
        if (countOffset >= 0 && (count = argumentsIn(buffers.parameter, "An indirect draw's count")) == null) return;
        CgDrawState s = tracker.state;
        if (!prepareDraw(s)) return;
        boolean indexed = type >= 0;
        if (indexed && !indices(s, type)) return;
        tracker.drawIndirect(GlEnums.topology(mode), indexed, args, offset, draws,
                stride != 0 ? stride : indexed ? 20 : 16, count, countOffset);
    }

    /** Everything a draw reads into {@code s}; false, with GL's error, when the framebuffer has nothing attached. */
    private boolean prepareDraw(CgDrawState s) {
        if (!framebuffers.applyDraw()) {
            errors.invalidFramebufferOperation("A draw with nothing attached to the framebuffer");
            return false;
        }
        state.sync();
        vaos.apply(s);
        programs.apply(s, buffers, textures);
        programs.feedDisabledInputs(s);
        return true;
    }

    /** The vertex array's element buffer as the draw's indices; false, with GL's error, when it has none. */
    private boolean indices(CgDrawState s, int type) {
        TrackedBuffers.GlBuffer elements = buffers.get(vaos.current().elementBuffer);
        if (elements == null) {
            errors.invalidOperation("glDrawElements with no element buffer bound to the vertex array");
            return false;
        }
        if (type == CgGL.GL_UNSIGNED_BYTE) throw new UnsupportedOperationException("8-bit indices: a device takes 16 or 32");
        s.indexBuffer(elements.storage.allocation(), 0, type == CgGL.GL_UNSIGNED_INT);
        return true;
    }

    /** The storage of buffer {@code name}, bound for {@code what}'s arguments; null, with GL's error, without one. */
    private CgAllocation argumentsIn(int name, String what) {
        TrackedBuffers.GlBuffer b = buffers.get(name);
        if (b == null || b.storage.allocation() == null) {
            errors.invalidOperation(what + " with no buffer bound for its arguments");
            return null;
        }
        return b.storage.allocation();
    }

    // ── compute ────────────────────────────────────────────────────────────────

    /** Kernels lower to transform feedback only below compute; a device runs them as compute (tier V). */
    @Override
    public void glTransformFeedbackVaryings(int program, String[] varyings, int bufferMode) {
        throw new UnsupportedOperationException("transform feedback: a device has none, and runs kernels as compute");
    }

    @Override
    public void glBeginTransformFeedback(int primitiveMode) {
        throw new UnsupportedOperationException("transform feedback: a device has none, and runs kernels as compute");
    }

    @Override
    public void glEndTransformFeedback() {
        throw new UnsupportedOperationException("transform feedback: a device has none, and runs kernels as compute");
    }

    @Override
    public void glDispatchCompute(int groupsX, int groupsY, int groupsZ) {
        CgComputePipeline p = programs.applyCompute(tracker.state, buffers, textures, textures);
        tracker.dispatch(p, groupsX, groupsY, groupsZ);
    }

    @Override
    public void glDispatchComputeIndirect(long offset) {
        CgAllocation args = argumentsIn(buffers.dispatchIndirect, "glDispatchComputeIndirect");
        if (args == null) return;
        tracker.dispatchIndirect(programs.applyCompute(tracker.state, buffers, textures, textures), args, offset);
    }

    /** GL's barrier is for a kernel's writes; every other hazard the tracker orders itself. */
    @Override
    public void glMemoryBarrier(int barriers) {
        tracker.memoryBarrier(CgAccess.COMPUTE_WRITE, GlEnums.accesses(barriers));
    }

    @Override
    public void glBindImageTexture(int unit, int texture, int level, boolean layered, int layer, int access, int format) {
        textures.bindImage(unit, texture, level, layered, layer, access, format);
    }

    /** This exact barrier, on the device memory under {@code buffer}. */
    @Override
    public void cgBufferBarrier(int buffer, int from, int to) {
        TrackedBuffers.GlBuffer b = buffers.get(buffer);
        if (b == null || b.storage.allocation() == null) {
            errors.invalidValue("cgBufferBarrier on buffer " + buffer + ", which has no storage");
            return;
        }
        tracker.bufferBarrier(b.storage.allocation(), from, to);
    }

    /** A device fill where the buffer's storage is device-local. */
    @Override
    public void cgFillBuffer(int buffer, long offset, long size, int value) {
        TrackedBuffers.GlBuffer b = buffers.get(buffer);
        if (b == null || b.storage.allocation() == null) {
            errors.invalidValue("cgFillBuffer on buffer " + buffer + ", which has no storage");
            return;
        }
        if (offset < 0 || size < 0 || (offset | size) % 4 != 0 || offset + size > b.storage.size()) {
            errors.invalidValue("cgFillBuffer of " + size + " bytes at " + offset + ": out of range or not whole words");
            return;
        }
        b.storage.fill(offset, size, value);
    }

    @Override public void cgBeginAsync() { tracker.beginAsync(); }

    @Override public long cgEndAsync() { return tracker.endAsync(); }

    @Override public void cgWaitAsync(long point) { tracker.waitAsync(point); }

    @Override
    public void cgImageBarrier(int texture, int from, int to) {
        TrackedTextures.GlTexture t = textures.get(texture);
        if (t == null || t.image == null) {
            errors.invalidValue("cgImageBarrier on texture " + texture + ", which has no image");
            return;
        }
        tracker.imageBarrier(t.image, from, to);
    }

    // ── sync, timers, host sections ────────────────────────────────────────────

    /** A fence is the frame that placed it: signalled once that frame retires. */
    @Override
    public long glFenceSync(int condition, int flags) {
        long id = nextSync++;
        syncs.put(id, device.frameIndex());
        return id;
    }

    /**
     * A zero timeout polls. A wait on the frame being recorded submits it first — a device that owns submission
     * can; a hosted one refuses, since its host submits and waiting would deadlock.
     */
    @Override
    public int glClientWaitSync(long sync, int flags, long timeout) {
        Long frame = syncs.get(sync);
        if (frame == null) {
            errors.invalidValue("glClientWaitSync on a fence that does not exist");
            return CgGL.GL_WAIT_FAILED;
        }
        if (frame <= device.retiredFrame()) return CgGL.GL_ALREADY_SIGNALED;
        // GL's flush bit, on a fence this frame recorded: submitting is what lets a spin on it ever end.
        boolean flush = (flags & CgGL.GL_SYNC_FLUSH_COMMANDS_BIT) != 0;
        if (flush && frame == device.frameIndex() && device.ownsSubmission()) tracker.endFrame();
        if (timeout == 0) return CgGL.GL_TIMEOUT_EXPIRED;
        if (!device.ownsSubmission()) throw new IllegalStateException("A blocking glClientWaitSync on a hosted device");
        if (frame == device.frameIndex()) tracker.endFrame();
        device.waitRetired(frame);
        return CgGL.GL_CONDITION_SATISFIED;
    }

    @Override public void glDeleteSync(long sync) { syncs.remove(sync); }

    @Override public int glGenQuery() { return queries.add(device.createTimerQuery("query")); }

    /** Timers may run inside a pass: timing a zone breaks nothing. */
    @Override
    public void glBeginTimeElapsedQuery(int query) {
        timing = queries.get(query);
        device.encoder().beginTimer(timing);
    }

    @Override
    public void glEndTimeElapsedQuery() {
        if (timing == null) { errors.invalidOperation("glEndQuery with no query running"); return; }
        device.encoder().endTimer(timing);
        timing = null;
    }

    @Override public boolean glIsQueryResultAvailable(int query) { return queries.get(query).resultNanos() >= 0; }

    @Override
    public long glGetQueryResultNanos(int query) {
        long nanos = queries.get(query).resultNanos();
        if (nanos < 0) errors.invalidOperation("A timer query read before its frame retired: poll glIsQueryResultAvailable");
        return Math.max(0, nanos);
    }

    @Override
    public void glDeleteQuery(int query) {
        CgTimerQuery q = queries.remove(query);
        if (q != null) tracker.release(q);
    }

    @Override public void toHost() { tracker.toHost(); }

    @Override public void fromHost() { tracker.fromHost(); }
}
