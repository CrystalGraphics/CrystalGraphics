package com.crystalgraphics.platform.gl.pipeline;

import com.crystalgraphics.platform.gl.binding.GLBindingPoints;
import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The GL device's linked programs, one per set of modules under one layout, shared by every pipeline of them whatever
 * state it adds: Unreal's linked program cache (`FOpenGLLinkedProgram`, `OpenGLShaders.cpp`), a program linked once
 * however many blends, culls and targets draw it. Built by the driver's own compiler threads where it has
 * {@code KHR_parallel_shader_compile} and {@code parallel} asks it ({@link GLParallelBuild}), else on the
 * {@link GLLinkWorker} where there is one, else at once.
 *
 * <pre>{@code
 * GLProgramCache.Program p = programs.acquire(label, layout, vs, fs);   // a pipeline's: starts the link if none has
 * if (programs.prepare(label, layout, vs, fs)) ...                      // a precache's: starts it, answers whether built
 * gl.glUseProgram(p.name(gl));                                          // the draw: waits only if it has not landed
 * programs.release(gl, p);                                              // the pipeline's delete
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only. The device calls {@link #endFrame} once a frame.</li>
 *   <li>A precache finishes one costly program a frame ({@link #prepare}); a draw finishes its own whatever it costs.</li>
 *   <li>A program prepared and never acquired is kept until a module of it is {@link #forget forgotten} or the device
 *       closes: a precache names what will draw.</li>
 *   <li>A program whose link failed throws its compile error from {@link Program#name}, at the draw, as a link there
 *       would.</li>
 * </ul>
 */
public final class GLProgramCache {

    private final CgGLDriver gl;
    private final GLShaderTranslator translator;
    private final GLProgramBinaries binaries;
    private final GLLinkWorker worker;
    private final boolean parallel;
    private final Map<Key, Program> programs = new HashMap<>();
    /** A finish past this is the driver compiling, not a binary loading or a status read. */
    private static final long COSTLY_FINISH_NANOS = 2_000_000L;
    /** Whether {@link #prepare} finished a program at a cost this frame. */
    private boolean finishedCostly;

    /**
     * @param binaries kept binaries, or null
     * @param worker links off the render thread, or null
     * @param parallel whether the driver's own compiler threads build them ({@link GLParallelBuild})
     */
    public GLProgramCache(CgGLDriver gl, GLShaderTranslator translator, GLProgramBinaries binaries, GLLinkWorker worker,
                          boolean parallel) {
        this.gl = gl;
        this.translator = translator;
        this.binaries = binaries;
        this.worker = worker;
        this.parallel = parallel;
    }

    /** A program being built off the render thread's time: on the link worker, or by the driver's own threads. */
    public interface Build {
        /** Whether {@link #program} answers without waiting. Never waits. */
        boolean ready(CgGLDriver gl);

        /** The program, waiting for it if it is not built. */
        int program(CgGLDriver gl);

        /** Its program deleted before it was asked for. */
        void cancel(CgGLDriver gl);
    }

    /** One linked program and the binding points it was translated under. */
    public static final class Program {
        private final Key key;
        private final CgGLDriver gl;
        public final GLBindingPoints points;
        private Build build;
        private int name;
        private int refs;
        /** Its link's failure, thrown at every ask. */
        private RuntimeException failed;

        private Program(Key key, CgGLDriver gl, GLBindingPoints points) {
            this.key = key;
            this.gl = gl;
            this.points = points;
        }

        /** Whether {@link #name} answers without waiting for its build. */
        public boolean ready() {
            return build == null || build.ready(gl);
        }

        /** The program, waiting for its build if it has not landed. */
        public int name(CgGLDriver gl) {
            if (build != null) {
                Build b = build;
                build = null;
                try {
                    name = b.program(gl);
                } catch (RuntimeException e) {
                    failed = e;
                }
            }
            if (failed != null) throw failed;
            return name;
        }
    }

    /** A pipeline's program, linked once for every pipeline of the same modules and layout. */
    public Program acquire(String label, CgPipelineLayout layout, GLShaderModule... modules) {
        Program p = program(label, layout, modules);
        p.refs++;
        return p;
    }

    /**
     * Starts the program's link if nothing has; whether it is built and loaded. Its binary is loaded here, once the worker
     * has it, so the precache polling this pays the driver's load inside its warm budget and the first draw nothing.
     * At most one program a frame is finished here at a cost ({@link #endFrame}): the rest answer false until a later one.
     */
    public boolean prepare(String label, CgPipelineLayout layout, GLShaderModule... modules) {
        Program p = program(label, layout, modules);
        if (!p.ready()) return false;
        if (p.build == null) return true;
        // NVIDIA compiles a program's back end at its first status read, on this thread, 0.1-0.5 s each, and every
        // context in the process waits on it: finishing every program that reported done in one frame held it 2.6 s
        if (finishedCostly) return false;
        long start = System.nanoTime();
        try {
            p.name(gl);
        } catch (RuntimeException e) {
            // Thrown again at its draw, as a link there would
        }
        if (System.nanoTime() - start > COSTLY_FINISH_NANOS) finishedCostly = true;
        return true;
    }

    /** A new frame: {@link #prepare} may finish another program at a cost. */
    public void endFrame() {
        finishedCostly = false;
    }

    /** A pipeline's program let go: deleted once no pipeline holds it. */
    public void release(CgGLDriver gl, Program p) {
        if (--p.refs > 0) return;
        programs.remove(p.key);
        delete(gl, p);
    }

    /** Every program of {@code module} no pipeline holds, deleted: the module's shader is gone (a reload, a release). */
    public void forget(GLShaderModule module) {
        for (Iterator<Program> it = programs.values().iterator(); it.hasNext(); ) {
            Program p = it.next();
            if (p.refs > 0 || !p.key.has(module)) continue;
            it.remove();
            delete(gl, p);
        }
    }

    /** Every program, at the device's close: pipelines still holding one are past drawing. */
    public void deleteAll() {
        List<Program> all = new ArrayList<>(programs.values());
        programs.clear();
        for (Program p : all) delete(gl, p);
    }

    private Program program(String label, CgPipelineLayout layout, GLShaderModule... modules) {
        Key key = new Key(layout, modules);
        Program p = programs.get(key);
        if (p != null) return p;
        p = new Program(key, gl, new GLBindingPoints(layout));
        if (parallel) {
            p.build = GLParallelBuild.start(gl, translator, binaries, p.points, label, modules);
        } else if (worker != null) {
            GLProgramBuild b = new GLProgramBuild(translator, binaries, p.points, label, modules);
            p.build = b;
            worker.submit(b);
        } else {
            p.name = GLPrograms.link(gl, translator, binaries, p.points, label, modules);
        }
        programs.put(key, p);
        return p;
    }

    private static void delete(CgGLDriver gl, Program p) {
        // A build still on the worker lands unread; one landed and never asked has no name yet
        if (p.build != null) p.build.cancel(gl);
        else if (p.name != 0) gl.glDeleteProgram(p.name);
    }

    /** A layout and its modules, each module by identity. */
    private static final class Key {
        private final CgPipelineLayout layout;
        private final GLShaderModule[] modules;
        private final int hash;

        Key(CgPipelineLayout layout, GLShaderModule[] modules) {
            this.layout = layout;
            this.modules = modules.clone();
            int h = layout.hashCode();
            for (GLShaderModule m : modules) h = 31 * h + System.identityHashCode(m);
            this.hash = h;
        }

        boolean has(GLShaderModule module) {
            for (GLShaderModule m : modules) if (m == module) return true;
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof Key k) || k.hash != hash || k.modules.length != modules.length || !k.layout.equals(layout)) {
                return false;
            }
            for (int i = 0; i < modules.length; i++) if (k.modules[i] != modules[i]) return false;
            return true;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return layout + " " + Arrays.toString(modules);
        }
    }
}
