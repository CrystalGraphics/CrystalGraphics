package com.crystalgraphics.compute.program;

import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import com.crystalgraphics.material.CgShaderCompile;
import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.emit.CgKernelTarget;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgKernelDecl;
import com.crystalgraphics.material.CgMaterialProperties;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgComputePass;
import com.crystalgraphics.platform.rhi.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.shader.CgGlslCompiler;
import com.crystalgraphics.platform.rhi.shader.CgGlslSources;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;
import com.crystalgraphics.render.draw.CgDeviceStates;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    /** {@link #glsl} with its includes expanded: what {@link #explicit} compiled. */
    private final String expanded;
    /** What it was emitted for. */
    private final CgKernelTarget target;
    private final int[] counterPoints;
    /** Where checked mode's report slot binds, after every buffer and count; -1 unchecked. */
    private final int checkPoint;
    private final int[] groupLimits = new int[3];
    private final CgMaterialProperties properties;

    private final CgGlslCompiler.ExplicitCompute explicit;
    /** The device its pipeline is on; null before the first {@link #devicePipeline}. */
    private CgRHI pipelineOf;
    private CgShaderModule deviceModule;
    private CgComputePipeline devicePipeline;
    private CgPipelineLayout deviceLayout;
    private ByteBuffer push;
    private boolean deleted;

    private CgKernelProgram(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, String glsl,
                            String expanded, CgKernelTarget target, CgGlslCompiler.ExplicitCompute explicit) {
        this.source = source;
        this.kernel = kernel;
        this.keywords = keywords;
        this.glsl = glsl;
        this.expanded = expanded;
        this.target = target;
        this.explicit = explicit;
        this.properties = new CgMaterialProperties(copies(source.properties()));
        this.counterPoints = new int[source.buffers().size()];
        for (CgBufferDecl b : source.buffers()) counterPoints[b.index()] = CgKernelEmitter.counterPoint(source, b);
        this.checkPoint = target.checked() ? CgKernelEmitter.checkPoint(source) : -1;
        CgCapabilities caps = CgCapabilities.detect();
        for (int axis = 0; axis < 3; axis++) groupLimits[axis] = caps.maxComputeWorkGroupCount(axis);
    }

    /**
     * Compiles {@code kernel} of {@code source} with {@code keywords} for the current device.
     *
     * @throws IllegalStateException with the compiler's log, and the emitted source, if it fails to compile
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
     * {@link #build}, returning before it has compiled: where the compiler runs on any thread, the compile runs on a
     * worker while frames go on ({@link CgShaderCompile}).
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
        String label = made.source().path() + " " + kernel.name() + keywords;
        String expanded = made.expanded();
        return new Pending(made.source(), kernel, keywords, made.glsl(), expanded, made.target(),
                CgShaderCompile.start(() -> CgGlslCompiler.get().compileExplicitCompute(expanded, label)));
    }

    /**
     * A kernel's GLSL for a target, emitted and with its includes expanded: what {@link #submit} compiles. CPU only, so a
     * worker makes it ahead of the frame that submits.
     */
    public record Sources(CgComputeSource source, CgKernelTarget target, String glsl, String expanded) {
        public static Sources of(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, CgKernelTarget target) {
            // GL's native float atomics are NV_shader_atomic_float, which a device's compiler refuses: the
            // compare-and-swap form, the bytes alike.
            String glsl = CgKernelEmitter.emit(source, kernel, keywords, target.withFloatAtomics(false));
            return new Sources(source, target, glsl, CgGlslSources.ascii(new CgShaderPreprocessor().process(glsl, source.path())));
        }
    }

    /** A kernel's program compiling: {@link #finish} makes it, waiting for its compile only if it has not finished. */
    public static final class Pending {
        private final CgComputeSource source;
        private final CgKernelDecl kernel;
        private final Set<String> keywords;
        private final String glsl, expanded;
        private final CgKernelTarget target;
        private final CgShaderCompile<CgGlslCompiler.ExplicitCompute> compile;

        private Pending(CgComputeSource source, CgKernelDecl kernel, Set<String> keywords, String glsl, String expanded,
                        CgKernelTarget target, CgShaderCompile<CgGlslCompiler.ExplicitCompute> compile) {
            this.source = source;
            this.kernel = kernel;
            this.keywords = keywords;
            this.glsl = glsl;
            this.expanded = expanded;
            this.target = target;
            this.compile = compile;
        }

        /** Whether {@link #finish} would return without waiting. */
        public boolean isDone() {
            return compile.isDone();
        }

        /** @throws IllegalStateException with the compiler's log, and the emitted source, if it failed to compile */
        public CgKernelProgram finish() {
            CgGlslCompiler.ExplicitCompute explicit;
            try {
                explicit = compile.get();
            } catch (RuntimeException e) {
                throw new IllegalStateException("[" + source.path() + "] kernel " + kernel.name() + keywords + ": "
                        + e.getMessage() + "\n--- emitted ---\n" + numbered(expanded), e);
            }
            return new CgKernelProgram(source, kernel, keywords, glsl, expanded, target, explicit);
        }

        /** Drops a compile nobody will finish: it runs to its end unread. */
        public void delete() {
        }
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

    /** The kernel as written ({@code CG_EXPLICIT}): its module and the sets it declares. */
    public CgGlslCompiler.ExplicitCompute explicit() {
        return explicit;
    }

    /** Its pipeline on {@code rhi}, made once a device; {@link #deviceLayout} is its layout. */
    public CgComputePipeline devicePipeline(CgRHI rhi) {
        deviceParts(rhi);
        if (devicePipeline == null) devicePipeline = rhi.createComputePipeline(label(), deviceModule, deviceLayout);
        return devicePipeline;
    }

    /**
     * Whether the installed device has built what its pipeline needs, starting it if nothing has (GL's program, on the
     * link worker): what a precache waits for, so its first dispatch waits on no link. Render thread.
     */
    public boolean deviceReady() {
        if (!CgRHI.installed()) return true;
        CgRHI rhi = CgRHI.get();
        deviceParts(rhi);
        return rhi.prepareProgram(deviceLayout, deviceModule);
    }

    /** Its module and layout on {@code rhi}, made once a device: what its pipeline is made of. */
    private void deviceParts(CgRHI rhi) {
        if (pipelineOf == rhi) return;
        releaseDevice();
        CgGlslCompiler.ExplicitCompute k = explicit();
        deviceLayout = CgDeviceStates.layout(rhi, k);
        deviceModule = rhi.createShaderModule(CgShaderModule.Stage.COMPUTE, k.spirv(), label());
        if (push == null) push = ByteBuffer.allocateDirect(CgKernelEmitter.PUSH_BYTES).order(ByteOrder.nativeOrder());
        pipelineOf = rhi;
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
        if (devicePipeline != null) pipelineOf.release(devicePipeline);
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

    /** The binding point of append buffer {@code name}'s count. */
    public int counterPoint(String name) { return counterPoints[buffer(name).index()]; }

    /** The binding point of the count of the append buffer declared {@code index}-th; -1 for any other buffer. */
    public int counterPoint(int index) { return counterPoints[index]; }

    public boolean isDeleted() { return deleted; }

    public void delete() {
        deleted = true;
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
