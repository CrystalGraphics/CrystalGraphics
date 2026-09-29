package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.format.CgAttribFormat;
import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.device.shader.CgShaderModule;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.memory.CgAllocation;
import com.crystalgraphics.platform.gl.tracked.tracker.CgDrawState;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTrackedProgram;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTracker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Shaders and programs. A compile keeps the source; the link compiles both stages through a {@link CgGlslCompiler}
 * and answers every later GL query from its table. Loose uniforms are written into a CPU copy of each stage's
 * block and uploaded at a draw only when they changed.
 */
public final class TrackedPrograms {

    static final int GL_SHADER_TYPE = 0x8B4F, GL_DELETE_STATUS = 0x8B80, GL_VALIDATE_STATUS = 0x8B83;
    static final int GL_ATTACHED_SHADERS = 0x8B85, GL_SHADER_SOURCE_LENGTH = 0x8B88, GL_UNIFORM_BLOCK = 0x92E2;
    static final int GL_INT = 0x1404, GL_INT_VEC2 = 0x8B53, GL_INT_VEC3 = 0x8B54, GL_INT_VEC4 = 0x8B55;
    static final int GL_UNSIGNED_INT = 0x1405, GL_UNSIGNED_INT_VEC2 = 0x8DC6, GL_UNSIGNED_INT_VEC3 = 0x8DC7;
    static final int GL_UNSIGNED_INT_VEC4 = 0x8DC8;
    /** The vertex binding disabled inputs read their constant from: past any a vertex array uses. */
    static final int CONSTANT_BINDING = 15;

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
        CgGlslCompiler.Program table;
        CgTrackedProgram tracked;
        CgShaderModule vertexGl, vertexZero, fragment;
        ByteBuffer vertexData, fragmentData;
        boolean vertexDirty, fragmentDirty;
        CgAllocation vertexUpload, fragmentUpload;
        long vertexFrame = -1, fragmentFrame = -1;
        int[] blockBinding, storageBinding, samplerUnit;
        final List<int[]> locations = new ArrayList<>();          // {0 uniform | 1 sampler, index, element}
        final Map<String, Integer> locationByName = new HashMap<>();

        Program(int name) { this.name = name; }
    }

    /** Resolves a sampler's texture unit to what a draw binds. The texture domain answers it. */
    interface Samplers {
        void bind(CgDrawState state, CgGlslCompiler.Sampler sampler, int unit);
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

    public TrackedPrograms(CgTracker tracker, CgGlslCompiler compiler, TrackedGlErrors errors) {
        this.tracker = tracker;
        this.device = tracker.device();
        this.compiler = compiler;
        this.errors = errors;
    }

    // ── shaders ────────────────────────────────────────────────────────────────

    public int createShader(int type) {
        if (type != CgGL.GL_VERTEX_SHADER && type != CgGL.GL_FRAGMENT_SHADER) {
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
        Program p = program(program);
        Shader vs = null, fs = null;
        for (int s : p.attached) {
            Shader sh = shader(s);
            if (sh.type == CgGL.GL_VERTEX_SHADER) vs = sh;
            else fs = sh;
        }
        if (vs == null || fs == null) {
            p.linked = false;
            p.log = "A program needs a vertex and a fragment shader";
            return;
        }
        String label = "program " + program;
        CgGlslCompiler.Program t;
        try {
            t = compiler.compile(vs.source, fs.source, p.attribBindings, label);
        } catch (CgShaderModule.CompileException e) {
            p.linked = false;
            p.log = e.getMessage();
            return;
        }
        releaseLinked(p);
        CgBindingLayout layout = device.createBindingLayout(label, t.slots());
        p.vertexGl = device.createShaderModule(CgShaderModule.Stage.VERTEX, t.vertexGlDepth(), label);
        p.vertexZero = device.createShaderModule(CgShaderModule.Stage.VERTEX, t.vertexZeroToOne(), label);
        p.fragment = device.createShaderModule(CgShaderModule.Stage.FRAGMENT, t.fragment(), label);
        p.tracked = new CgTrackedProgram(label, layout, p.vertexGl, p.vertexZero, p.fragment);
        p.table = t;
        p.vertexData = t.vertexUniformBinding() < 0 ? null
                : ByteBuffer.allocateDirect(t.vertexUniformSize()).order(ByteOrder.nativeOrder());
        p.fragmentData = t.fragmentUniformBinding() < 0 ? null
                : ByteBuffer.allocateDirect(t.fragmentUniformSize()).order(ByteOrder.nativeOrder());
        p.vertexDirty = p.fragmentDirty = true;
        p.blockBinding = new int[t.uniformBlocks().size()];
        p.storageBinding = new int[t.storageBlocks().size()];
        p.samplerUnit = new int[t.samplers().size()];
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
        p.linked = true;
        p.log = "";
    }

    public int programi(int program, int pname) {
        Program p = program(program);
        CgGlslCompiler.Program t = p.table;
        switch (pname) {
            case CgGL.GL_LINK_STATUS: return p.linked ? CgGL.GL_TRUE : CgGL.GL_FALSE;
            case CgGL.GL_INFO_LOG_LENGTH: return p.log.isEmpty() ? 0 : p.log.length() + 1;
            case GL_VALIDATE_STATUS: return CgGL.GL_TRUE;
            case GL_DELETE_STATUS: return p.deleteRequested ? 1 : 0;
            case GL_ATTACHED_SHADERS: return p.attached.size();
            case CgGL.GL_ACTIVE_UNIFORMS: return t == null ? 0 : t.uniforms().size() + t.samplers().size();
            case CgGL.GL_ACTIVE_ATTRIBUTES: return t == null ? 0 : t.attributes().size();
            case CgGL.GL_ACTIVE_UNIFORM_BLOCKS: return t == null ? 0 : t.uniformBlocks().size();
            case CgGL.GL_ACTIVE_UNIFORM_MAX_LENGTH: {
                int max = 0;
                if (t != null) {
                    for (CgGlslCompiler.Uniform u : t.uniforms()) max = Math.max(max, u.name().length() + 4);
                    for (CgGlslCompiler.Sampler s : t.samplers()) max = Math.max(max, s.name().length() + 1);
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
        Program p = program(program);
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
        CgGlslCompiler.Program t = program(program).table;
        String name;
        int size, type;
        if (index < t.uniforms().size()) {
            CgGlslCompiler.Uniform u = t.uniforms().get(index);
            name = u.count() > 1 ? u.name() + "[0]" : u.name();
            size = u.count();
            type = u.glType();
        } else {
            CgGlslCompiler.Sampler s = t.samplers().get(index - t.uniforms().size());
            name = s.name();
            size = 1;
            type = s.glType();
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
        if (l[0] == 1) { errors.invalidOperation("glUniformf on a sampler"); return; }
        CgGlslCompiler.Uniform u = p.table.uniforms().get(l[1]);
        float[] v = {x, y, z, w};
        for (int r = 0; r < Math.min(n, u.rows()); r++) put(p, u, l[2], 0, r, v[r]);
    }

    /** {@code glUniform1i}: a sampler's unit, or an integer uniform. */
    public void int1(int location, int v) {
        Program p = writable(location);
        if (p == null) return;
        int[] l = p.locations.get(location);
        if (l[0] == 1) {
            p.samplerUnit[l[1]] = v;
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
        CgGlslCompiler.Uniform u = p.table.uniforms().get(l[1]);
        for (int i = 0; i < values.remaining() && l[2] + i < u.count(); i++) put(p, u, l[2] + i, 0, 0, values.get(values.position() + i));
    }

    /** {@code glUniform1iv}: consecutive integer elements, or one sampler's unit. */
    public void intArray(int location, IntBuffer values) {
        Program p = writable(location);
        if (p == null) return;
        int[] l = p.locations.get(location);
        if (l[0] == 1) {
            p.samplerUnit[l[1]] = values.get(values.position());
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
        Program p = current;
        if (p == null || !p.linked) throw new IllegalStateException("Draw with no linked program in use");
        CgGlslCompiler.Program t = p.table;
        state.program = p.tracked;
        state.clearBindings();
        long frame = device.frameIndex();
        if (p.vertexData != null) {
            if (p.vertexDirty || p.vertexFrame != frame) {
                p.vertexUpload = upload(p.vertexData);
                p.vertexFrame = frame;
                p.vertexDirty = false;
            }
            state.uniform(t.vertexUniformBinding(), p.vertexUpload, 0, t.vertexUniformSize());
        }
        if (p.fragmentData != null) {
            if (p.fragmentDirty || p.fragmentFrame != frame) {
                p.fragmentUpload = upload(p.fragmentData);
                p.fragmentFrame = frame;
                p.fragmentDirty = false;
            }
            state.uniform(t.fragmentUniformBinding(), p.fragmentUpload, 0, t.fragmentUniformSize());
        }
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
        for (int i = 0; i < t.samplers().size(); i++) samplers.bind(state, t.samplers().get(i), p.samplerUnit[i]);
    }

    /** Every input the program reads must come from the vertex array, which Vulkan requires and GL does not. */
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
        for (CgGlslCompiler.Attribute a : current.table.attributes()) {
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
        if (p.tracked == null) return;
        tracker.forgetPipelines(p.tracked);
        for (CgDeviceObject o : List.of(p.vertexGl, p.vertexZero, p.fragment, p.tracked.layout)) tracker.release(o);
        p.tracked = null;
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

    private Program program(int name) {
        Object o = names.get(name);
        if (!(o instanceof Program p)) throw new IllegalArgumentException(name + " is not a program");
        return p;
    }

    public int query(int pname, double[] out) {
        if (pname == CgGL.GL_CURRENT_PROGRAM) return TrackedRenderState.one(out, currentName());
        return -1;
    }
}
