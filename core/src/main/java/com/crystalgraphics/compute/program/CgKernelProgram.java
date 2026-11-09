package com.crystalgraphics.compute.program;

import com.crystalgraphics.api.CgBindingPoints;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.gl.shader.CgProgram;
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
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.render.draw.CgDeviceStates;
import com.crystalgraphics.render.draw.CgPassConstants;
import com.crystalgraphics.util.CgBufferUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * One kernel and keyword set, compiled: its pipeline on the device, and the sets its buffers, images, samplers and
 * blocks bind at, as the emitter names them. A frame graph's compute pass dispatches it.
 *
 * <pre>{@code
 * CgKernelProgram simulate = CgCompute.load("mymod:shaders/particles.compute").kernel("Simulate").program();
 * compute.setPipeline(simulate.devicePipeline(rhi));
 * compute.setBindingSet(2, kernelSet);                 // CgDeviceSets, from simulate.deviceLayout()
 * simulate.dispatchOn(compute, particleCount, 1, 1);
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only.</li>
 *   <li>A dispatch orders nothing after it: the caller barriers what reads its writes, the frame graph its own.</li>
 *   <li>An append buffer's count is a separate {@code uint}, its word pushed with {@link #counterWordOn}; the caller
 *       zeroes it.</li>
 * </ul>
 */
public final class CgKernelProgram {

    private final CgComputeSource source;
    private final CgKernelDecl kernel;
    private final Set<String> keywords;
    private final String glsl;
    /** {@link #glsl} with its includes expanded: what {@link #explicit} compiles. */
    private final String expanded;
    /** What it was emitted for. */
    private final CgKernelTarget target;
    private final CgProgram program;
    private final int[] counterPoints;
    /** Where checked mode's report slot binds, after every buffer and count; -1 unchecked. */
    private final int checkPoint;
    private final int[] groupLimits = new int[3];
    private final CgMaterialProperties properties;

    private CgGlslCompiler.ExplicitCompute explicit;
    /** The device its pipeline is on; null before the first {@link #devicePipeline}. */
    private CgRHI pipelineOf;
    private CgShaderModule deviceModule;
    private CgComputePipeline devicePipeline;
    private CgPipelineLayout deviceLayout;
    private ByteBuffer push;

    private CgKernelProgram(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, String glsl,
                            String expanded, CgKernelTarget target, CgProgram program) {
        this.source = source;
        this.kernel = kernel;
        this.keywords = keywords;
        this.glsl = glsl;
        this.expanded = expanded;
        this.target = target;
        this.program = program;
        this.properties = new CgMaterialProperties(copies(source.properties()));
        this.counterPoints = new int[source.buffers().size()];
        for (CgBufferDecl b : source.buffers()) counterPoints[b.index()] = CgKernelEmitter.counterPoint(source, b);
        this.checkPoint = target.checked() ? CgKernelEmitter.checkPoint(source) : -1;
        CgCapabilities caps = CgCapabilities.detect();
        for (int axis = 0; axis < 3; axis++) groupLimits[axis] = caps.maxComputeWorkGroupCount(axis);
        try (CgGlScope scope = CgGlState.save(CgGlSlot.PROGRAM)) {
            CgGL.glUseProgram(program.getId());
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
        return submit(source, kernel, keywords, target).finish();
    }

    /**
     * {@link #build}, returning before the driver has linked: where it links on threads of its own
     * ({@code KHR_parallel_shader_compile}), the link runs while frames go on.
     *
     * <pre>{@code
     * CgKernelProgram.Pending pending = CgKernelProgram.submit(source, decl, keywords, CgKernelTarget.current());
     * // later frames:
     * if (pending.isDone()) program = pending.finish();   // throws as build would
     * }</pre>
     */
    public static Pending submit(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords,
                                 CgKernelTarget target) {
        return submit(Sources.of(source, kernel, keywords, target), kernel, keywords);
    }

    /** {@link #submit(CgComputeSource, CgKernelDecl, Set, CgKernelTarget)} from sources made already, on a worker. */
    public static Pending submit(Sources made, CgKernelDecl kernel, Set<String> keywords) {
        CgProgram program = CgProgram.create();
        try (CgProgram.Labelled named = CgProgram.labelled(made.source().path() + " " + kernel.name() + keywords)) {
            program.submitComputeLink(made.expanded(), made.driver());
        }
        return new Pending(made.source(), kernel, keywords, made.glsl(), made.expanded(), made.target(), program);
    }

    /**
     * A kernel's GLSL for a target, emitted and with its includes expanded: what {@link #submit} hands the driver. CPU
     * only, so a worker makes it ahead of the frame that submits.
     */
    public record Sources(CgComputeSource source, CgKernelTarget target, String glsl, String expanded, String driver) {
        public static Sources of(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, CgKernelTarget target) {
            String glsl = CgKernelEmitter.emit(source, kernel, keywords, target);
            String expanded = CgGL.checkSource(new CgShaderPreprocessor().process(glsl, source.path())).toString();
            return new Sources(source, target, glsl, expanded, CgProgram.forDriver(expanded));
        }
    }

    /** A kernel's program linking: {@link #finish} makes it, waiting for the driver only if it has not finished. */
    public static final class Pending {
        private final CgComputeSource source;
        private final CgKernelDecl kernel;
        private final Set<String> keywords;
        private final String glsl, expanded;
        private final CgKernelTarget target;
        private final CgProgram program;

        private Pending(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, String glsl, String expanded,
                        CgKernelTarget target, CgProgram program) {
            this.source = source;
            this.kernel = kernel;
            this.keywords = keywords;
            this.glsl = glsl;
            this.expanded = expanded;
            this.target = target;
            this.program = program;
        }

        /** Whether {@link #finish} would return without waiting. */
        public boolean isDone() {
            return program.isLinkDone();
        }

        /** @throws IllegalStateException with the driver's log, and the emitted source, if it failed to compile or link */
        public CgKernelProgram finish() {
            try {
                program.finishLink();
            } catch (IllegalStateException e) {
                program.delete();
                throw new IllegalStateException("[" + source.path() + "] kernel " + kernel.name() + keywords + ": "
                        + e.getMessage() + "\n--- emitted ---\n" + numbered(expanded), e);
            }
            return new CgKernelProgram(source, kernel, keywords, glsl, expanded, target, program);
        }

        /** Drops a link nobody will finish. */
        public void delete() {
            program.delete();
        }
    }

    /**
     * The engine's environment, whose GL points are sized from the driver's limits: by name until GL3. What the kernel
     * declares itself the emitter bound ({@link CgKernelEmitter}).
     */
    private void wire() {
        int id = program.getId();
        for (String token : source.engineBuffers()) {
            CgShaderBuffer buffer = CgEngineBufferRegistry.get(token).buffer().get();
            storageBlock(id, buffer.getName(), buffer.getBindingLocation());
        }
        uniformBlock(id, CgPassConstants.VIEW_BLOCK, CgBindingPoints.VIEW_UBO);
        uniformBlock(id, CgPassConstants.ENVIRONMENT_BLOCK, CgBindingPoints.ENVIRONMENT_UBO);
        uniformBlock(id, CgKernelEmitter.PROPERTY_BLOCK, CgBindingPoints.MATERIAL_PROPERTIES_UBO);
        unit(id, CgBindingPoints.DEPTH_TEXTURE_UNIFORM, CgBindingPoints.DEPTH_TEXTURE_UNIT);
        unit(id, CgBindingPoints.SCENE_COLOR_TEXTURE_UNIFORM, CgBindingPoints.SCENE_COLOR_TEXTURE_UNIT);
        unit(id, CgBindingPoints.LIGHTMAP_TEXTURE_UNIFORM, CgBindingPoints.LIGHTMAP_TEXTURE_UNIT);
        unit(id, CgBindingPoints.MEDIA_TEXTURE_UNIFORM, CgBindingPoints.MEDIA_TEXTURE_UNIT);
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

    // ── Bindings ──────────────────────────────────────────────────────────────

    /** Where a count at byte {@code offset} binds from: the storage-aligned offset at or below it. */
    public static long counterBase(long offset) {
        long align = Math.max(4, CgCapabilities.detect().storageOffsetAlignment());
        return offset / align * align;
    }

    /** This program's {@code Properties} values: set them as a material's. */
    public CgMaterialValues properties() {
        return properties;
    }

    // ── On a device ───────────────────────────────────────────────────────────

    /**
     * The kernel as written ({@code CG_EXPLICIT}): its module and the sets it declares. Compiled on first ask.
     *
     * @throws CgShaderModule.CompileException where it leaves a resource unbound, or no compiler is installed
     */
    public CgGlslCompiler.ExplicitCompute explicit() {
        if (explicit == null) {
            // GL's native float atomics are NV_shader_atomic_float, which a device's compiler refuses: its module takes
            // the compare-and-swap form, the bytes alike
            String forDevice = target.floatAtomics()
                    ? Sources.of(source, kernel, keywords, target.withFloatAtomics(false)).expanded() : expanded;
            explicit = CgGlslCompiler.get().compileExplicitCompute(forDevice, label());
        }
        return explicit;
    }

    /** Its pipeline on {@code rhi}, made once a device; {@link #deviceLayout} is its layout. */
    public CgComputePipeline devicePipeline(CgRHI rhi) {
        if (pipelineOf != rhi) {
            releaseDevice();
            CgGlslCompiler.ExplicitCompute k = explicit();
            deviceLayout = CgDeviceStates.layout(rhi, k);
            deviceModule = rhi.createShaderModule(CgShaderModule.Stage.COMPUTE, k.spirv(), label());
            devicePipeline = rhi.createComputePipeline(label(), deviceModule, deviceLayout);
            if (push == null) push = ByteBuffer.allocateDirect(CgKernelEmitter.PUSH_BYTES).order(ByteOrder.nativeOrder());
            pipelineOf = rhi;
        }
        return devicePipeline;
    }

    /** {@link #devicePipeline}'s layout: the sets a dispatch binds. */
    public CgPipelineLayout deviceLayout() {
        return deviceLayout;
    }

    /**
     * {@code x * y * z} elements through {@code pass}, its pipeline set and every set bound: groups round up, the
     * dispatch's base and count pushed, and a count past the device's group limit runs as several, each from its own
     * {@code CG_DISPATCH_BASE}.
     */
    public void dispatchOn(CgComputePass pass, int x, int y, int z) {
        if (x <= 0 || y <= 0 || z <= 0) return;
        int gx = groups(x, kernel.sizeX()), gy = groups(y, kernel.sizeY()), gz = groups(z, kernel.sizeZ());
        for (int bz = 0; bz < gz; bz += groupLimits[2]) {
            for (int by = 0; by < gy; by += groupLimits[1]) {
                for (int bx = 0; bx < gx; bx += groupLimits[0]) {
                    pushDispatch(pass, bx * kernel.sizeX(), by * kernel.sizeY(), bz * kernel.sizeZ(), x, y, z);
                    pass.dispatch(Math.min(groupLimits[0], gx - bx), Math.min(groupLimits[1], gy - by),
                            Math.min(groupLimits[2], gz - bz));
                }
            }
        }
    }

    /** Group counts from three {@code uint}s at {@code offset} of {@code args}, written on the GPU, through {@code pass}. */
    public void dispatchIndirectOn(CgComputePass pass, CgGpuBuffer args, long offset) {
        pushDispatch(pass, 0, 0, 0, -1, -1, -1);
        pass.dispatchIndirect(args, offset);
    }

    /**
     * Where in its bound range the count of the append buffer declared {@code index}-th sits, for a count at byte
     * {@code offset} bound from {@link #counterBase}: the word pushed with the next {@link #dispatchOn}.
     */
    public void counterWordOn(int index, long offset) {
        if (counterPoints[index] < 0) return;
        int word = counterPoints[index] - source.buffers().size();
        push.putInt(CgKernelEmitter.PUSH_WORDS_AT + 4 * word, (int) ((offset - counterBase(offset)) >>> 2));
    }

    /** Checked mode's report point, after every buffer and count; -1 unchecked. */
    public int checkPoint() {
        return checkPoint;
    }

    private void pushDispatch(CgComputePass pass, int baseX, int baseY, int baseZ, int countX, int countY, int countZ) {
        int bytes = Math.min(CgKernelEmitter.PUSH_BYTES, deviceLayout.pushConstantBytes());
        if (bytes == 0) return;
        push.putInt(0, baseX).putInt(4, baseY).putInt(8, baseZ).putInt(16, countX).putInt(20, countY).putInt(24, countZ);
        push.limit(bytes).position(0);
        pass.pushConstants(0, push);
        push.clear();
    }

    private String label() {
        return source.path() + " " + kernel.name() + keywords;
    }

    private void releaseDevice() {
        if (pipelineOf == null) return;
        pipelineOf.release(devicePipeline);
        pipelineOf.release(deviceModule);
        pipelineOf = null;
        devicePipeline = null;
        deviceModule = null;
    }

    private static int groups(int count, int size) {
        return (count + size - 1) / size;
    }

    // ── What it is ────────────────────────────────────────────────────────────

    public CgKernelDecl kernel() { return kernel; }

    /** The file it was compiled from. */
    public CgComputeSource source() { return source; }

    public Set<String> keywords() { return keywords; }

    /** The GLSL this was compiled from, before its includes were expanded. */
    public String glsl() { return glsl; }

    public int programId() { return program.getId(); }

    /** The binding point of append buffer {@code name}'s count. */
    public int counterPoint(String name) { return counterPoints[buffer(name).index()]; }

    /** The binding point of the count of the append buffer declared {@code index}-th; -1 for any other buffer. */
    public int counterPoint(int index) { return counterPoints[index]; }

    public boolean isDeleted() { return program.isDeleted(); }

    public void delete() {
        program.delete();
        releaseDevice();
    }

    /** Buffer {@code name}'s place among the kernel's buffers: its binding is {@code FIRST_KERNEL_BUFFER} past it. */
    public int bufferIndex(String name) {
        return buffer(name).index();
    }

    private CgBufferDecl buffer(String name) {
        CgBufferDecl b = source.buffer(name);
        if (b == null) throw new IllegalArgumentException("[" + source.path() + "] no buffer '" + name + "'");
        return b;
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
