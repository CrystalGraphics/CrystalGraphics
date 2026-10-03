package com.crystalgraphics.compute.program;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.api.shader.CgShaderProgram;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccess;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.api.buffer.CgBufferLifetime;
import com.crystalgraphics.gl.buffer.shader.CgEngineBufferRegistry;
import com.crystalgraphics.gl.buffer.shader.CgShaderBuffer;
import com.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import com.crystalgraphics.gl.material.CgMaterialProperties;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.state.CgGlScope;
import com.crystalgraphics.platform.gl.state.CgGlSlot;
import com.crystalgraphics.platform.gl.state.CgGlState;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One kernel and keyword set, compiled for the current context and wired: its buffers, images, samplers and blocks at
 * the bindings the emitter names. Dispatched directly here, or by a frame graph's compute pass.
 *
 * <pre>{@code
 * CgKernelProgram simulate = CgCompute.load("mymod:shaders/particles.compute").kernel("Simulate").program();
 * simulate.properties().set1f("_Drag", 0.1f);
 * try (CgGlScope scope = simulate.scope()) {
 *     simulate.use()
 *             .buffer("STATE", stateBuffer)
 *             .dispatch(particleCount);
 * }
 * CgGL.cgBufferBarrier(stateBuffer, CgAccess.COMPUTE_WRITE, CgAccess.VERTEX_READ);   // before a draw reads it
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only, as every GL object; {@link #use} first, inside a {@link #scope}.</li>
 *   <li>A dispatch orders nothing after it: the caller barriers what reads its writes, the frame graph its own.</li>
 *   <li>An append buffer's count is a separate {@code uint} bound with {@link #counter}, and the caller zeroes it.</li>
 * </ul>
 */
public final class CgKernelProgram {

    private final CgComputeSource source;
    private final CgKernelDecl kernel;
    private final Set<String> keywords;
    private final String glsl;
    private final CgShaderProgram program;
    private final int dispatchLocation;
    private final int[] counterPoints;
    private final int[] groupLimits = new int[3];
    private final IntBuffer dispatch = CgBufferUtils.createIntBuffer(6);
    private final CgMaterialProperties properties;
    private final List<CgMaterialProperty> samplers = new ArrayList<>();
    private final CgUniformBuffer propertyBlock;
    private CgUniformBuffer frameBlock;
    private float[] frameValues;

    private CgKernelProgram(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, String glsl,
                            CgShaderProgram program) {
        this.source = source;
        this.kernel = kernel;
        this.keywords = keywords;
        this.glsl = glsl;
        this.program = program;
        this.properties = new CgMaterialProperties(copies(source.properties()));
        for (CgMaterialProperty p : properties.all()) if (p.getType().isSampler()) samplers.add(p);
        this.propertyBlock = properties.hasUboProps()
                ? new CgUniformBuffer(CgKernelEmitter.PROPERTY_BLOCK, properties.buildUboFormat(),
                        CgBindingPoints.MATERIAL_PROPERTIES_UBO, CgBufferLifetime.FRAME)
                : null;
        this.counterPoints = new int[source.buffers().size()];
        int next = source.buffers().size();
        for (CgBufferDecl b : source.buffers()) counterPoints[b.index()] = b.access() == CgBufferAccess.APPEND ? next++ : -1;
        CgCapabilities caps = CgCapabilities.detect();
        for (int axis = 0; axis < 3; axis++) groupLimits[axis] = caps.maxComputeWorkGroupCount(axis);
        try (CgGlScope scope = CgGlState.save(CgGlSlot.PROGRAM)) {
            CgGL.glUseProgram(program.getId());
            this.dispatchLocation = CgGL.glGetUniformLocation(program.getId(), CgKernelEmitter.DISPATCH_UNIFORM);
            wire();
        }
    }

    /**
     * Compiles {@code kernel} of {@code source} with {@code keywords} for the current context.
     *
     * @throws IllegalStateException with the driver's log, and the emitted source, if it fails to compile or link
     */
    public static CgKernelProgram build(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords) {
        return build(source, kernel, keywords, CgKernelTarget.current());
    }

    /** As {@link #build(CgComputeSource, CgKernelDecl, Set)}, for a target this context runs: subgroups emulated. */
    public static CgKernelProgram build(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords,
                                        CgKernelTarget target) {
        String glsl = CgKernelEmitter.emit(source, kernel, keywords, target);
        String expanded = new CgShaderPreprocessor().process(glsl, source.path());
        CgShaderProgram program;
        try {
            program = CgShaderProgram.compileCompute(expanded);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("[" + source.path() + "] kernel " + kernel.name() + keywords + ": "
                    + e.getMessage() + "\n--- emitted ---\n" + numbered(expanded), e);
        }
        return new CgKernelProgram(source, kernel, keywords, glsl, program);
    }

    private void wire() {
        int id = program.getId();
        for (CgBufferDecl b : source.buffers()) {
            storageBlock(id, CgKernelEmitter.bufferBlock(b), b.index());
            storageBlock(id, CgKernelEmitter.bitsBlock(b), b.index());
            if (counterPoints[b.index()] >= 0) storageBlock(id, CgKernelEmitter.counterBlock(b), counterPoints[b.index()]);
        }
        for (String token : source.engineBuffers()) {
            CgShaderBuffer buffer = CgEngineBufferRegistry.get(token).buffer().get();
            storageBlock(id, buffer.getName(), buffer.getBindingLocation());
        }
        uniformBlock(id, CgPassConstants.BLOCK_NAME, CgBindingPoints.FRAME_DATA_UBO);
        uniformBlock(id, CgKernelEmitter.PROPERTY_BLOCK, CgBindingPoints.MATERIAL_PROPERTIES_UBO);
        for (CgImageDecl image : source.images()) unit(id, image.uniform(), image.index());
        for (int i = 0; i < samplers.size(); i++) unit(id, samplers.get(i).getName(), i);
        unit(id, CgBindingPoints.DEPTH_TEXTURE_UNIFORM, CgBindingPoints.DEPTH_TEXTURE_UNIT);
        unit(id, CgBindingPoints.SCENE_COLOR_TEXTURE_UNIFORM, CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT);
    }

    private static void storageBlock(int program, String name, int point) {
        int index = CgGL.glGetProgramResourceIndex(program, CgGL.GL_SHADER_STORAGE_BLOCK, name);
        if (index != CgGL.GL_INVALID_INDEX) CgGL.glShaderStorageBlockBinding(program, index, point);
    }

    private static void uniformBlock(int program, String name, int point) {
        int index = CgGL.glGetUniformBlockIndex(program, name);
        if (index != CgGL.GL_INVALID_INDEX) CgGL.glUniformBlockBinding(program, index, point);
    }

    private static void unit(int program, String uniform, int unit) {
        int location = CgGL.glGetUniformLocation(program, uniform);
        if (location >= 0) CgGL.glUniform1i(location, unit);
    }

    // ── Use ───────────────────────────────────────────────────────────────────

    /** Saves what a dispatch changes, restored when the scope closes. */
    public static CgGlScope scope() {
        return CgGlState.save(CgGlSlot.PROGRAM, CgGlSlot.STORAGE_BUFFERS, CgGlSlot.IMAGES, CgGlSlot.TEXTURES,
                CgGlSlot.INDIRECT_BUFFERS);
    }

    /** Makes this the current program. */
    public CgKernelProgram use() {
        CgGL.glUseProgram(program.getId());
        return this;
    }

    /** {@code glBuffer} whole as buffer {@code name}. */
    public CgKernelProgram buffer(String name, int glBuffer) {
        CgGL.glBindBufferBase(CgGL.GL_SHADER_STORAGE_BUFFER, buffer(name).index(), glBuffer);
        return this;
    }

    /** {@code size} bytes of {@code glBuffer} from {@code offset}, aligned to the device's storage offset alignment. */
    public CgKernelProgram buffer(String name, int glBuffer, long offset, long size) {
        CgGL.glBindBufferRange(CgGL.GL_SHADER_STORAGE_BUFFER, buffer(name).index(), glBuffer, offset, size);
        return this;
    }

    /** Append buffer {@code name}'s count: the {@code uint} at {@code offset} in {@code glBuffer}. */
    public CgKernelProgram counter(String name, int glBuffer, long offset) {
        CgBufferDecl b = buffer(name);
        if (b.access() != CgBufferAccess.APPEND) throw new IllegalArgumentException(name + " is no append buffer");
        CgGL.glBindBufferRange(CgGL.GL_SHADER_STORAGE_BUFFER, counterPoints[b.index()], glBuffer, offset, 4);
        return this;
    }

    /** Mip {@code level} of {@code texture} as image {@code name}: every layer of an array, cube or 3D texture. */
    public CgKernelProgram image(String name, CgTexture texture, int level) {
        return image(name, texture.getId(), level);
    }

    /** As {@link #image(String, CgTexture, int)}, for a GL texture name. */
    public CgKernelProgram image(String name, int glTexture, int level) {
        CgImageDecl image = image(name);
        CgGL.glBindImageTexture(image.index(), glTexture, level, image.dimension() != CgImageDimension.D2, 0,
                access(image), image.format().glFormat);
        return this;
    }

    /** One layer of an array, cube or 3D texture, as a 2D image {@code name}. */
    public CgKernelProgram image(String name, CgTexture texture, int level, int layer) {
        CgImageDecl image = image(name);
        CgGL.glBindImageTexture(image.index(), texture.getId(), level, false, layer, access(image), image.format().glFormat);
        return this;
    }

    /** A sampler property's texture, bound at the next dispatch. */
    public CgKernelProgram texture(String property, CgTexture texture) {
        for (int unit = 0; unit < samplers.size(); unit++) {
            if (samplers.get(unit).getName().equals(property)) {
                samplers.get(unit).setTexture(unit, texture);
                return this;
            }
        }
        throw new IllegalArgumentException("[" + source.path() + "] no sampler property '" + property + "'");
    }

    /** This program's {@code Properties} values, uploaded at every dispatch: set them as a material's. */
    public CgShaderBindings properties() {
        return properties;
    }

    /** The frame block a kernel reads {@code cg_Time} and the camera from, uploaded at every dispatch. */
    public CgKernelProgram frame(CgPassConstants constants) {
        if (frameBlock == null) {
            frameBlock = new CgUniformBuffer(CgPassConstants.BLOCK_NAME, CgPassConstants.FORMAT,
                    CgBindingPoints.FRAME_DATA_UBO, CgBufferLifetime.FRAME);
            frameValues = new float[CgPassConstants.FLOATS];
        }
        constants.write(frameValues, 0);
        return this;
    }

    // ── Dispatch ──────────────────────────────────────────────────────────────

    /** {@code count} elements in one dimension. */
    public void dispatch(int count) {
        dispatch(count, 1, 1);
    }

    /**
     * {@code x * y * z} elements: groups round up, and a count past the device's group limit runs as several
     * dispatches, each from its own {@code CG_DISPATCH_BASE}.
     */
    public void dispatch(int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) return;
        prepare();
        int gx = groups(x, kernel.sizeX()), gy = groups(y, kernel.sizeY()), gz = groups(z, kernel.sizeZ());
        for (int bz = 0; bz < gz; bz += groupLimits[2]) {
            for (int by = 0; by < gy; by += groupLimits[1]) {
                for (int bx = 0; bx < gx; bx += groupLimits[0]) {
                    setDispatch(bx * kernel.sizeX(), by * kernel.sizeY(), bz * kernel.sizeZ(), x, y, z);
                    CgGL.glDispatchCompute(Math.min(groupLimits[0], gx - bx), Math.min(groupLimits[1], gy - by),
                            Math.min(groupLimits[2], gz - bz));
                }
            }
        }
    }

    /** Whole work groups: {@code CG_DISPATCH_COUNT} is every invocation they hold. */
    public void dispatchGroups(int x, int y, int z) {
        dispatch(x * kernel.sizeX(), y * kernel.sizeY(), z * kernel.sizeZ());
    }

    /** Group counts from three {@code uint}s at {@code offset} in {@code glBuffer}, written on the GPU. */
    public void dispatchIndirect(int glBuffer, long offset) {
        prepare();
        setDispatch(0, 0, 0, -1, -1, -1);
        CgGL.glBindBuffer(CgGL.GL_DISPATCH_INDIRECT_BUFFER, glBuffer);
        CgGL.glDispatchComputeIndirect(offset);
    }

    private void prepare() {
        if (propertyBlock != null) {
            properties.writeUboProps(propertyBlock.writer());
            propertyBlock.endRecord();
            propertyBlock.upload();
            propertyBlock.bind();
        }
        if (frameBlock != null) {
            frameBlock.uploadRaw(frameValues, frameValues.length);
            frameBlock.bind();
        }
        properties.bindSamplerTextures();
    }

    private void setDispatch(int baseX, int baseY, int baseZ, int countX, int countY, int countZ) {
        dispatch.put(0, baseX).put(1, baseY).put(2, baseZ).put(3, countX).put(4, countY).put(5, countZ);
        CgGL.glUniform1(dispatchLocation, dispatch);
    }

    private static int groups(int count, int size) {
        return (count + size - 1) / size;
    }

    // ── What it is ────────────────────────────────────────────────────────────

    public CgKernelDecl kernel() { return kernel; }

    public Set<String> keywords() { return keywords; }

    /** The GLSL this was compiled from, before its includes were expanded. */
    public String glsl() { return glsl; }

    public int programId() { return program.getId(); }

    /** The binding point of append buffer {@code name}'s count. */
    public int counterPoint(String name) { return counterPoints[buffer(name).index()]; }

    public boolean isDeleted() { return program.isDeleted(); }

    public void delete() {
        program.delete();
        if (propertyBlock != null) propertyBlock.delete();
        if (frameBlock != null) frameBlock.delete();
    }

    private CgBufferDecl buffer(String name) {
        CgBufferDecl b = source.buffer(name);
        if (b == null) throw new IllegalArgumentException("[" + source.path() + "] no buffer '" + name + "'");
        return b;
    }

    private CgImageDecl image(String name) {
        CgImageDecl i = source.image(name);
        if (i == null) throw new IllegalArgumentException("[" + source.path() + "] no image '" + name + "'");
        return i;
    }

    private static int access(CgImageDecl image) {
        return image.access() == CgImageAccess.READONLY ? CgGL.GL_READ_ONLY
                : image.access() == CgImageAccess.WRITEONLY ? CgGL.GL_WRITE_ONLY : CgGL.GL_READ_WRITE;
    }

    /** Each program holds its own values: a parsed property is the file's, shared by every kernel. */
    private static List<CgMaterialProperty> copies(List<CgMaterialProperty> declared) {
        List<CgMaterialProperty> out = new ArrayList<>(declared.size());
        for (CgMaterialProperty p : declared) out.add(p.copyWithDefaults());
        return out;
    }

    private static String numbered(String text) {
        StringBuilder sb = new StringBuilder();
        String[] lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) sb.append(String.format("%4d  ", i + 1)).append(lines[i]).append('\n');
        return sb.toString();
    }
}
