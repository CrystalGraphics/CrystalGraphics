package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.format.CgAttribFormat;
import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.pipeline.CgComputePipeline;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.device.shader.CgShaderModule;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.memory.CgAllocation;
import com.crystalgraphics.platform.gl.tracked.tracker.CgDrawState;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTrackedProgram;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTracker;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Shaders and programs. A compile keeps the source; the link compiles the program's stages through a
 * {@link CgGlslCompiler} and answers every later GL query from its table. Loose uniforms are written into a CPU copy
 * of each stage's block and uploaded at a draw only when they changed. A compute program's pipeline is made at link,
 * since nothing of GL's state goes into it.
 */
public final class TrackedPrograms {

    static final int GL_SHADER_TYPE = 0x8B4F, GL_DELETE_STATUS = 0x8B80, GL_VALIDATE_STATUS = 0x8B83;
    static final int GL_ATTACHED_SHADERS = 0x8B85, GL_SHADER_SOURCE_LENGTH = 0x8B88, GL_UNIFORM_BLOCK = 0x92E2;
    static final int GL_INT = 0x1404, GL_INT_VEC2 = 0x8B53, GL_INT_VEC3 = 0x8B54, GL_INT_VEC4 = 0x8B55;
    static final int GL_UNSIGNED_INT = 0x1405, GL_UNSIGNED_INT_VEC2 = 0x8DC6, GL_UNSIGNED_INT_VEC3 = 0x8DC7;
    static final int GL_UNSIGNED_INT_VEC4 = 0x8DC8;
    /** The vertex binding disabled inputs read their constant from: past any a vertex array uses. */
    static final int CONSTANT_BINDING = 15;
    private static final CgTraceChannel GL = CgTrace.channel("crystalgraphics.gl");
    private static final int SPIRV = CgTrace.name("shader.spirv"), PIPELINE = CgTrace.name("shader.computePipeline");
    private static final int SPIRV_WAIT = CgTrace.name("shader.spirvWait");

    static final class Shader {
        final int type;
        String source = "";
        int attachments;
        boolean deleteRequested;

        Shader(int type) { this.type = type; }
    }

    static final class Program {
        final int name;
        final List<Integer> attached = new ArrayList<>();
        final Map<String, Integer> attribBindings = new HashMap<>();
        boolean linked, deleteRequested;
        String log = "";
        CgGlslCompiler.Reflection table;
        /** A graphics program's; a compute program has {@link #pipeline} instead. */
        CgTrackedProgram tracked;
        CgComputePipeline pipeline;
        /** What the link made, released when the program is linked again or deleted. */
        final List<CgDeviceObject> objects = new ArrayList<>();
        /** A kernel's loose uniforms are in {@code vertexData}, at their vertex offsets. */
        ByteBuffer vertexData, fragmentData;
        boolean vertexDirty, fragmentDirty;
        CgAllocation vertexUpload, fragmentUpload;
        long vertexFrame = -1, fragmentFrame = -1;
        int[] blockBinding, storageBinding, samplerUnit, imageUnit;
        final List<int[]> locations = new ArrayList<>();          // {0 uniform | 1 sampler | 2 image, index, element}
        final Map<String, Integer> locationByName = new HashMap<>();
        /** A link whose shaderc runs on the worker; the first use of the program finishes it. */
        Future<CgGlslCompiler.Reflection> pending;
        String pendingLabel;

        Program(int name) { this.name = name; }
    }

    /** Resolves a sampler's texture unit to what a draw binds. The texture domain answers it. */
    interface Samplers {
        void bind(CgDrawState state, CgGlslCompiler.Sampler sampler, int unit);
    }

    /** Resolves a storage image's image unit to what a dispatch binds. The texture domain answers it. */
    interface Images {
        void bind(CgDrawState state, CgGlslCompiler.Image image, int unit);
    }

    private final CgTracker tracker;
    private final CgDevice device;
    private final CgGlslCompiler compiler;
    private final TrackedGlErrors errors;
    private final GlNames<Object> names = new GlNames<>("Shader or program");
    private Program current;
    private final Map<List<CgPipelineDesc.VertexBuffer>, Map<List<CgPipelineDesc.VertexAttrib>, List<CgPipelineDesc.VertexBuffer>>>
            withConstants = new HashMap<>();
    private CgAllocation constantValues;
    /** Where links run shaderc, from {@link #compileInBackground}; null: at the link. */
    private ExecutorService worker;

    public TrackedPrograms(CgTracker tracker, CgGlslCompiler compiler, TrackedGlErrors errors) {
        this.tracker = tracker;
        this.device = tracker.device();
        this.compiler = compiler;
        this.errors = errors;
    }

    /**
     * Links run shaderc on a worker from now on, as a driver with {@code KHR_parallel_shader_compile} compiles on its
     * own threads: {@code GL_COMPLETION_STATUS_KHR} says when it is done, and anything else asked of the program
     * waits for it, then makes its modules and pipeline here. The compiler must be safe to call from both threads.
     */
    public void compileInBackground() {
        if (worker != null) return;
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "crystalgraphics-shaderc");
            thread.setDaemon(true);
            return thread;
        });
    }

    // ── shaders ────────────────────────────────────────────────────────────────

    public int createShader(int type) {
        if (type != CgGL.GL_VERTEX_SHADER && type != CgGL.GL_FRAGMENT_SHADER && type != CgGL.GL_COMPUTE_SHADER) {
            errors.invalidEnum("glCreateShader", type);
            return 0;
        }
        return names.add(new Shader(type));
    }

    public void source(int shader, CharSequence source) { shader(shader).source = source.toString(); }

    public int shaderi(int shader, int pname) {
        Shader s = shader(shader);
        switch (pname) {
            case CgGL.GL_COMPILE_STATUS: return CgGL.GL_TRUE;   // compiled at link, where both stages meet
            case CgGL.GL_INFO_LOG_LENGTH: return 0;
            case GL_SHADER_TYPE: return s.type;
            case GL_DELETE_STATUS: return s.deleteRequested ? 1 : 0;
            case GL_SHADER_SOURCE_LENGTH: return s.source.length() + 1;
            default: errors.invalidEnum("glGetShaderiv", pname); return 0;
        }
    }

    public void deleteShader(int shader) {
        if (!names.exists(shader)) return;
        Shader s = shader(shader);
        s.deleteRequested = true;
        if (s.attachments == 0) names.remove(shader);
    }

    // ── programs ───────────────────────────────────────────────────────────────

    public int createProgram() {
        int name = names.next();
        return names.add(new Program(name));
    }

    public void attach(int program, int shader) {
        Program p = program(program);
        if (p.attached.contains(shader)) { errors.invalidOperation("glAttachShader: already attached"); return; }
        p.attached.add(shader);
        shader(shader).attachments++;
    }

    public void detach(int program, int shader) {
        Program p = program(program);
        if (!p.attached.remove((Integer) shader)) { errors.invalidOperation("glDetachShader: not attached"); return; }
        Shader s = shader(shader);
        if (--s.attachments == 0 && s.deleteRequested) names.remove(shader);
    }

    public void attachedShaders(int program, IntBuffer count, IntBuffer shaders) {
        Program p = program(program);
        int n = Math.min(p.attached.size(), shaders.remaining());
        for (int i = 0; i < n; i++) shaders.put(shaders.position() + i, p.attached.get(i));
        if (count != null) count.put(count.position(), n);
    }

    public void bindAttribLocation(int program, int index, CharSequence name) {
        program(program).attribBindings.put(name.toString(), index);
    }

    public void link(int program) {
        Program p = lookup(program);
        drop(p);
        Shader vs = null, fs = null, cs = null;
        for (int s : p.attached) {
            Shader sh = shader(s);
            if (sh.type == CgGL.GL_VERTEX_SHADER) vs = sh;
            else if (sh.type == CgGL.GL_COMPUTE_SHADER) cs = sh;
            else fs = sh;
        }
        String label = "program " + program;
        if (cs != null) {
            if (vs != null || fs != null) {
                fail(p, "A compute shader links alone");
                return;
            }
            String source = cs.source;
            compile(p, label, () -> compiler.compileCompute(source, label));
            return;
        }
        if (vs == null || fs == null) {
            fail(p, "A program needs a vertex and a fragment shader");
            return;
        }
        String vertex = vs.source, fragment = fs.source;
        Map<String, Integer> attribs = new HashMap<>(p.attribBindings);
        compile(p, label, () -> compiler.compile(vertex, fragment, attribs, label));
    }

    /**
     * shaderc on the worker, finished at the program's next use; at once without a worker, or for the program in use,
     * whose draws read it directly.
     */
    private void compile(Program p, String label, Callable<CgGlslCompiler.Reflection> spirv) {
        if (worker == null || p == current) {
            CgGlslCompiler.Reflection t;
            try (CgTrace.Zone ignored = CgTrace.zone(GL, SPIRV)) {
                t = spirv.call();
            } catch (CgShaderModule.CompileException e) {
                fail(p, e.getMessage());
                return;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            install(p, t, label);
            return;
        }
        p.pending = worker.submit(() -> {
            try (CgTrace.Zone ignored = CgTrace.zone(GL, SPIRV)) {
                return spirv.call();
            }
        });
        p.pendingLabel = label;
    }

    /** Waits for a link's shaderc, then makes its modules and pipeline: here, on the owner thread. */
    private void finish(Program p) {
        Future<CgGlslCompiler.Reflection> pending = p.pending;
        String label = p.pendingLabel;
        p.pending = null;
        p.pendingLabel = null;
        CgGlslCompiler.Reflection t;
        try (CgTrace.Zone ignored = pending.isDone() ? null : CgTrace.zone(GL, SPIRV_WAIT)) {
            t = pending.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof CgShaderModule.CompileException compile) {
                fail(p, compile.getMessage());
                return;
            }
            throw new IllegalStateException(label + ": shaderc failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(label + ": interrupted waiting for shaderc", e);
        }
        install(p, t, label);
    }

    /** A link nobody will finish: superseded by another, or the program deleted. */
    private static void drop(Program p) {
        if (p.pending == null) return;
        p.pending.cancel(false);
        p.pending = null;
        p.pendingLabel = null;
    }

    /** The device's half of a link: the binding layout, the modules and, for a kernel, its pipeline. */
    private void install(Program p, CgGlslCompiler.Reflection reflection, String label) {
        releaseLinked(p);
        if (reflection instanceof CgGlslCompiler.ComputeProgram t) {
            CgBindingLayout layout = device.createBindingLayout(label, t.slots());
            CgShaderModule module = device.createShaderModule(CgShaderModule.Stage.COMPUTE, t.spirv(), label);
            try (CgTrace.Zone ignored = CgTrace.zone(GL, PIPELINE)) {
                p.pipeline = device.createComputePipeline(label, module, layout);
            }
            p.objects.addAll(List.of(p.pipeline, module, layout));
            p.vertexData = uniformBlock(t.uniformBinding(), t.uniformSize());
            p.fragmentData = null;
        } else {
            CgGlslCompiler.Program t = (CgGlslCompiler.Program) reflection;
            CgBindingLayout layout = device.createBindingLayout(label, t.slots());
            CgShaderModule vertexGl = device.createShaderModule(CgShaderModule.Stage.VERTEX, t.vertexGlDepth(), label);
            CgShaderModule vertexZero = device.createShaderModule(CgShaderModule.Stage.VERTEX, t.vertexZeroToOne(), label);
            CgShaderModule fragment = device.createShaderModule(CgShaderModule.Stage.FRAGMENT, t.fragment(), label);
            p.objects.addAll(List.of(vertexGl, vertexZero, fragment, layout));
            p.tracked = new CgTrackedProgram(label, layout, vertexGl, vertexZero, fragment);
            p.vertexData = uniformBlock(t.vertexUniformBinding(), t.vertexUniformSize());
            p.fragmentData = uniformBlock(t.fragmentUniformBinding(), t.fragmentUniformSize());
        }
        linked(p, reflection);
    }

    private static void fail(Program p, String log) {
        p.linked = false;
        p.log = log;
    }

    private static ByteBuffer uniformBlock(int binding, int size) {
        return binding < 0 ? null : ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder());
    }

    /** The table every GL query answers from, and a location per uniform element, sampler and image. */
    private static void linked(Program p, CgGlslCompiler.Reflection t) {
        p.table = t;
        p.vertexDirty = p.fragmentDirty = true;
        p.blockBinding = new int[t.uniformBlocks().size()];
        p.storageBinding = new int[t.storageBlocks().size()];
        p.samplerUnit = new int[t.samplers().size()];
        p.imageUnit = new int[images(t).size()];
        p.locations.clear();
        p.locationByName.clear();
        for (int i = 0; i < t.uniforms().size(); i++) {
            CgGlslCompiler.Uniform u = t.uniforms().get(i);
            for (int e = 0; e < u.count(); e++) {
                int loc = p.locations.size();
                p.locations.add(new int[] {0, i, e});
                p.locationByName.put(u.name() + "[" + e + "]", loc);
                if (e == 0) p.locationByName.put(u.name(), loc);
            }
        }
        for (int i = 0; i < t.samplers().size(); i++) {
            p.locationByName.put(t.samplers().get(i).name(), p.locations.size());
            p.locations.add(new int[] {1, i, 0});
        }
        for (int i = 0; i < images(t).size(); i++) {
            p.locationByName.put(images(t).get(i).name(), p.locations.size());
            p.locations.add(new int[] {2, i, 0});
        }
        p.linked = true;
        p.log = "";
    }

    private static List<CgGlslCompiler.Image> images(CgGlslCompiler.Reflection t) {
        return t instanceof CgGlslCompiler.ComputeProgram c ? c.images() : List.of();
    }

    public int programi(int program, int pname) {
        if (pname == CgGL.GL_COMPLETION_STATUS_KHR) {
            Program pending = lookup(program);
            return pending.pending == null || pending.pending.isDone() ? CgGL.GL_TRUE : CgGL.GL_FALSE;
        }
        Program p = program(program);
        CgGlslCompiler.Reflection t = p.table;
        switch (pname) {
            case CgGL.GL_LINK_STATUS: return p.linked ? CgGL.GL_TRUE : CgGL.GL_FALSE;
            case CgGL.GL_INFO_LOG_LENGTH: return p.log.isEmpty() ? 0 : p.log.length() + 1;
            case GL_VALIDATE_STATUS: return CgGL.GL_TRUE;
            case GL_DELETE_STATUS: return p.deleteRequested ? 1 : 0;
            case GL_ATTACHED_SHADERS: return p.attached.size();
            case CgGL.GL_ACTIVE_UNIFORMS: return t == null ? 0 : t.uniforms().size() + t.samplers().size() + images(t).size();
            case CgGL.GL_ACTIVE_ATTRIBUTES: return t instanceof CgGlslCompiler.Program g ? g.attributes().size() : 0;
            case CgGL.GL_ACTIVE_UNIFORM_BLOCKS: return t == null ? 0 : t.uniformBlocks().size();
            case CgGL.GL_ACTIVE_UNIFORM_MAX_LENGTH: {
                int max = 0;
                if (t != null) {
                    for (CgGlslCompiler.Uniform u : t.uniforms()) max = Math.max(max, u.name().length() + 4);
                    for (CgGlslCompiler.Sampler s : t.samplers()) max = Math.max(max, s.name().length() + 1);
                    for (CgGlslCompiler.Image i : images(t)) max = Math.max(max, i.name().length() + 1);
                }
                return max;
            }
            default: errors.invalidEnum("glGetProgramiv", pname); return 0;
        }
    }

    public String log(int program, int maxLength) {
        String log = program(program).log;
        return log.length() > maxLength ? log.substring(0, Math.max(0, maxLength)) : log;
    }

    public void use(int program) {
        Program next = program == 0 ? null : program(program);
        Program previous = current;
        current = next;
        if (previous != null && previous != next && previous.deleteRequested) destroy(previous);
    }

    public void deleteProgram(int program) {
        if (!names.exists(program)) return;
        Program p = lookup(program);
        drop(p);
        p.deleteRequested = true;
        if (p != current) destroy(p);
    }

    public int uniformLocation(int program, CharSequence name) {
        Program p = program(program);
        if (!p.linked) { errors.invalidOperation("glGetUniformLocation on a program that is not linked"); return -1; }
        return p.locationByName.getOrDefault(name.toString(), -1);
    }

    /** {@code glGetActiveUniform}: the name, with {@code sizeType[0]} its count and {@code [1]} its GL type. */
    public String activeUniform(int program, int index, int maxLength, IntBuffer sizeType) {
        CgGlslCompiler.Reflection t = program(program).table;
        String name;
        int size, type;
        if (index < t.uniforms().size()) {
            CgGlslCompiler.Uniform u = t.uniforms().get(index);
            name = u.count() > 1 ? u.name() + "[0]" : u.name();
            size = u.count();
            type = u.glType();
        } else if (index < t.uniforms().size() + t.samplers().size()) {
            CgGlslCompiler.Sampler s = t.samplers().get(index - t.uniforms().size());
            name = s.name();
            size = 1;
            type = s.glType();
        } else {
            CgGlslCompiler.Image i = images(t).get(index - t.uniforms().size() - t.samplers().size());
            name = i.name();
            size = 1;
            type = i.glType();
        }
        sizeType.put(sizeType.position(), size);
        sizeType.put(sizeType.position() + 1, type);
        return name.length() > maxLength ? name.substring(0, maxLength) : name;
    }

    public int uniformBlockIndex(int program, CharSequence name) {
        return indexOf(program(program).table.uniformBlocks(), name.toString());
    }

    public void uniformBlockBinding(int program, int index, int binding) {
        program(program).blockBinding[index] = binding;
    }

    public int resourceIndex(int program, int iface, CharSequence name) {
        Program p = program(program);
        if (iface == CgGL.GL_SHADER_STORAGE_BLOCK) return indexOf(p.table.storageBlocks(), name.toString());
        if (iface == GL_UNIFORM_BLOCK) return indexOf(p.table.uniformBlocks(), name.toString());
        errors.invalidEnum("glGetProgramResourceIndex", iface);
        return -1;
    }

    public void storageBlockBinding(int program, int index, int binding) {
        program(program).storageBinding[index] = binding;
    }

    // ── uniforms ───────────────────────────────────────────────────────────────

    /** {@code glUniform1..4f}: one element's components. */
    public void floats(int location, float x, float y, float z, float w, int n) {
        Program p = writable(location);
        if (p == null) return;
        int[] l = p.locations.get(location);
        if (l[0] != 0) { errors.invalidOperation("glUniformf on a sampler or an image"); return; }
        CgGlslCompiler.Uniform u = p.table.uniforms().get(l[1]);
        float[] v = {x, y, z, w};
        for (int r = 0; r < Math.min(n, u.rows()); r++) put(p, u, l[2], 0, r, v[r]);
    }

    /** {@code glUniform1i}: a sampler's texture unit, an image's image unit, or an integer uniform. */
    public void int1(int location, int v) {
        Program p = writable(location);
        if (p == null) return;
        int[] l = p.locations.get(location);
        if (l[0] != 0) {
            unit(p, l, v);
            return;
        }
        CgGlslCompiler.Uniform u = p.table.uniforms().get(l[1]);
        if (!u.integer()) { errors.invalidOperation("glUniform1i on a float uniform"); return; }
        put(p, u, l[2], 0, 0, v);
    }

    /** {@code glUniform1fv}: consecutive elements from the location's. */
    public void floatArray(int location, FloatBuffer values) {
        Program p = writable(location);
        if (p == null) return;
        int[] l = p.locations.get(location);
        if (l[0] != 0) { errors.invalidOperation("glUniform1fv on a sampler or an image"); return; }
        CgGlslCompiler.Uniform u = p.table.uniforms().get(l[1]);
        for (int i = 0; i < values.remaining() && l[2] + i < u.count(); i++) put(p, u, l[2] + i, 0, 0, values.get(values.position() + i));
    }

    /** {@code glUniform1iv}: consecutive integer elements, or one sampler's or image's unit. */
    public void intArray(int location, IntBuffer values) {
        Program p = writable(location);
        if (p == null) return;
        int[] l = p.locations.get(location);
        if (l[0] != 0) {
            unit(p, l, values.get(values.position()));
            return;
        }
        CgGlslCompiler.Uniform u = p.table.uniforms().get(l[1]);
        for (int i = 0; i < values.remaining() && l[2] + i < u.count(); i++) put(p, u, l[2] + i, 0, 0, values.get(values.position() + i));
    }

    /** {@code glUniformMatrix3fv}/{@code 4fv}: GL's column-major floats, or row-major with {@code transpose}. */
    public void matrix(int location, boolean transpose, FloatBuffer values, int n) {
        Program p = writable(location);
        if (p == null) return;
        int[] l = p.locations.get(location);
        if (l[0] != 0) { errors.invalidOperation("glUniformMatrix" + n + "fv on a sampler or an image"); return; }
        CgGlslCompiler.Uniform u = p.table.uniforms().get(l[1]);
        if (u.columns() != n || u.rows() != n) { errors.invalidOperation("glUniformMatrix" + n + "fv on a different type"); return; }
        int elements = values.remaining() / (n * n);
        for (int e = 0; e < elements && l[2] + e < u.count(); e++) {
            int base = values.position() + e * n * n;
            for (int c = 0; c < n; c++) {
                for (int r = 0; r < n; r++) {
                    put(p, u, l[2] + e, c, r, values.get(base + (transpose ? r * n + c : c * n + r)));
                }
            }
        }
    }

    private static void unit(Program p, int[] location, int unit) {
        if (location[0] == 1) p.samplerUnit[location[1]] = unit;
        else p.imageUnit[location[1]] = unit;
    }

    private Program writable(int location) {
        if (location == -1) return null;
        if (current == null || !current.linked) {
            errors.invalidOperation("glUniform with no linked program in use");
            return null;
        }
        if (location < 0 || location >= current.locations.size()) {
            errors.invalidOperation("glUniform at location " + location + ", which the program does not have");
            return null;
        }
        return current;
    }

    private static void put(Program p, CgGlslCompiler.Uniform u, int element, int column, int row, float value) {
        int at = element * u.stride() + column * u.matrixStride() + row * 4;
        if (u.vertexOffset() >= 0) {
            if (u.integer()) p.vertexData.putInt(u.vertexOffset() + at, (int) value);
            else p.vertexData.putFloat(u.vertexOffset() + at, value);
            p.vertexDirty = true;
        }
        if (u.fragmentOffset() >= 0) {
            if (u.integer()) p.fragmentData.putInt(u.fragmentOffset() + at, (int) value);
            else p.fragmentData.putFloat(u.fragmentOffset() + at, value);
            p.fragmentDirty = true;
        }
    }

    // ── the draw ───────────────────────────────────────────────────────────────

    /** Puts the current program and everything it reads into {@code state}. */
    public void apply(CgDrawState state, TrackedBuffers buffers, Samplers samplers) {
        CgGlslCompiler.Program t = graphics("Draw");
        Program p = current;
        state.program = p.tracked;
        state.clearBindings();
        if (p.vertexData != null) state.uniform(t.vertexUniformBinding(), vertexUpload(p), 0, t.vertexUniformSize());
        if (p.fragmentData != null) {
            long frame = device.frameIndex();
            if (p.fragmentDirty || p.fragmentFrame != frame) {
                p.fragmentUpload = upload(p.fragmentData);
                p.fragmentFrame = frame;
                p.fragmentDirty = false;
            }
            state.uniform(t.fragmentUniformBinding(), p.fragmentUpload, 0, t.fragmentUniformSize());
        }
        blocks(state, buffers, p, t);
        for (int i = 0; i < t.samplers().size(); i++) samplers.bind(state, t.samplers().get(i), p.samplerUnit[i]);
    }

    /** Puts the current compute program's bindings into {@code state}, and answers the pipeline to dispatch. */
    public CgComputePipeline applyCompute(CgDrawState state, TrackedBuffers buffers, Samplers samplers, Images images) {
        Program p = current;
        if (p == null || !p.linked || !(p.table instanceof CgGlslCompiler.ComputeProgram t))
            throw new IllegalStateException("glDispatchCompute with no linked compute program in use");
        state.clearBindings();
        if (p.vertexData != null) state.uniform(t.uniformBinding(), vertexUpload(p), 0, t.uniformSize());
        blocks(state, buffers, p, t);
        for (int i = 0; i < t.samplers().size(); i++) samplers.bind(state, t.samplers().get(i), p.samplerUnit[i]);
        for (int i = 0; i < t.images().size(); i++) images.bind(state, t.images().get(i), p.imageUnit[i]);
        return p.pipeline;
    }

    /** The current program's table, which must be a graphics program's. */
    private CgGlslCompiler.Program graphics(String what) {
        Program p = current;
        if (p == null || !p.linked) throw new IllegalStateException(what + " with no linked program in use");
        if (!(p.table instanceof CgGlslCompiler.Program t))
            throw new IllegalStateException(what + " with compute program " + p.name + " in use: dispatch it");
        return t;
    }

    private CgAllocation vertexUpload(Program p) {
        long frame = device.frameIndex();
        if (p.vertexDirty || p.vertexFrame != frame) {
            p.vertexUpload = upload(p.vertexData);
            p.vertexFrame = frame;
            p.vertexDirty = false;
        }
        return p.vertexUpload;
    }

    /** The uniform and storage blocks, from the buffers at their binding points. */
    private static void blocks(CgDrawState state, TrackedBuffers buffers, Program p, CgGlslCompiler.Reflection t) {
        for (int i = 0; i < t.uniformBlocks().size(); i++) {
            int point = p.blockBinding[i];
            CgAllocation a = boundStorage(buffers, buffers.uniformName[point], t.uniformBlocks().get(i).name(), point, p);
            long offset = buffers.uniformOffset[point];
            long size = buffers.uniformSize[point] < 0 ? a.size() - offset : buffers.uniformSize[point];
            state.uniform(t.uniformBlocks().get(i).binding(), a, offset, size);
        }
        for (int i = 0; i < t.storageBlocks().size(); i++) {
            int point = p.storageBinding[i];
            CgAllocation a = boundStorage(buffers, buffers.storageName[point], t.storageBlocks().get(i).name(), point, p);
            long offset = buffers.storageOffset[point];
            long size = buffers.storageSize[point] < 0 ? a.size() - offset : buffers.storageSize[point];
            state.storage(t.storageBlocks().get(i).binding(), a, offset, size);
        }
    }

    /** The current program alone into {@code state}, with nothing it reads bound: enough to build its pipeline. */
    public void applyProgram(CgDrawState state) {
        graphics("A pipeline");
        state.program = current.tracked;
    }

    /**
     * An input the vertex array leaves disabled reads GL's current attribute value, {@code (0, 0, 0, 1)}. A device has
     * no such thing, so those inputs read one constant at stride 0 from a binding of their own.
     */
    public void feedDisabledInputs(CgDrawState state) {
        boolean[] provided = new boolean[TrackedVertexArrays.ATTRIBS];
        for (CgPipelineDesc.VertexBuffer vb : state.vertexLayouts) {
            for (CgPipelineDesc.VertexAttrib a : vb.attribs()) provided[a.location()] = true;
        }
        List<CgPipelineDesc.VertexAttrib> constants = null;
        for (CgGlslCompiler.Attribute a : ((CgGlslCompiler.Program) current.table).attributes()) {
            if (a.location() < provided.length && provided[a.location()]) continue;
            if (constants == null) constants = new ArrayList<>();
            CgAttribFormat f = constantFormat(a.glType());
            constants.add(new CgPipelineDesc.VertexAttrib(a.location(), f, f == CgAttribFormat.FLOAT32X4 ? 0 : 16));
        }
        if (constants == null) return;
        List<CgPipelineDesc.VertexBuffer> base = state.vertexLayouts;
        List<CgPipelineDesc.VertexAttrib> fed = constants;
        // Cached by value, so a draw's layout list is the same object every frame and keeps its pipeline.
        state.vertexLayouts = withConstants.computeIfAbsent(base, k -> new HashMap<>()).computeIfAbsent(fed, k -> {
            List<CgPipelineDesc.VertexBuffer> layouts = new ArrayList<>(base);
            layouts.add(new CgPipelineDesc.VertexBuffer(CONSTANT_BINDING, 0, false, fed));
            return List.copyOf(layouts);
        });
        if (constantValues == null) {
            constantValues = tracker.allocate(32, true, "disabled vertex inputs");
            constantValues.memory().putFloat(0).putFloat(0).putFloat(0).putFloat(1).putInt(0).putInt(0).putInt(0).putInt(1);
        }
        state.vertexBuffer(CONSTANT_BINDING, constantValues, 0);
    }

    private static CgAttribFormat constantFormat(int glType) {
        switch (glType) {
            case GL_INT: case GL_INT_VEC2: case GL_INT_VEC3: case GL_INT_VEC4: return CgAttribFormat.SINT32X4;
            case GL_UNSIGNED_INT: case GL_UNSIGNED_INT_VEC2: case GL_UNSIGNED_INT_VEC3: case GL_UNSIGNED_INT_VEC4:
                return CgAttribFormat.UINT32X4;
            default: return CgAttribFormat.FLOAT32X4;
        }
    }

    public int currentName() { return current == null ? 0 : current.name; }

    private CgAllocation upload(ByteBuffer data) {
        CgAllocation a = tracker.frameAllocate(data.capacity());
        ByteBuffer to = a.memory();
        to.put(data.duplicate().clear());
        return a;
    }

    private static CgAllocation boundStorage(TrackedBuffers buffers, int name, String block, int point, Program p) {
        TrackedBuffers.GlBuffer b = buffers.get(name);
        if (b == null || b.storage.allocation() == null)
            throw new IllegalStateException(p.tracked.label() + " reads block " + block + " at binding point " + point
                    + ", which has no buffer");
        return b.storage.allocation();
    }

    private void destroy(Program p) {
        releaseLinked(p);
        for (int s : new ArrayList<>(p.attached)) detach(p.name, s);
        names.remove(p.name);
    }

    private void releaseLinked(Program p) {
        if (p.tracked != null) tracker.forgetPipelines(p.tracked);
        for (CgDeviceObject o : p.objects) tracker.release(o);
        p.objects.clear();
        p.tracked = null;
        p.pipeline = null;
    }

    private static int indexOf(List<CgGlslCompiler.Block> blocks, String name) {
        for (int i = 0; i < blocks.size(); i++) if (blocks.get(i).name().equals(name)) return i;
        return CgGL.GL_INVALID_INDEX;
    }

    private Shader shader(int name) {
        Object o = names.get(name);
        if (!(o instanceof Shader s)) throw new IllegalArgumentException(name + " is not a shader");
        return s;
    }

    /** The program, its link finished: everything asked of a program but its completion status waits for it. */
    private Program program(int name) {
        Program p = lookup(name);
        if (p.pending != null) finish(p);
        return p;
    }

    private Program lookup(int name) {
        Object o = names.get(name);
        if (!(o instanceof Program p)) throw new IllegalArgumentException(name + " is not a program");
        return p;
    }

    public int query(int pname, double[] out) {
        if (pname == CgGL.GL_CURRENT_PROGRAM) return TrackedRenderState.one(out, currentName());
        return -1;
    }
}
