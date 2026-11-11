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
 * however many blends, culls and targets draw it. Linked on the {@link GLLinkWorker} where there is one, else at once.
 *
 * <pre>{@code
 * GLProgramCache.Program p = programs.acquire(label, layout, vs, fs);   // a pipeline's: starts the link if none has
 * if (programs.prepare(label, layout, vs, fs)) ...                      // a precache's: starts it, answers whether built
 * gl.glUseProgram(p.name(gl));                                          // the draw: waits only if it has not landed
 * programs.release(gl, p);                                              // the pipeline's delete
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only.</li>
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
    private final Map<Key, Program> programs = new HashMap<>();

    /** @param binaries kept binaries, or null; @param worker links off the render thread, or null */
    public GLProgramCache(CgGLDriver gl, GLShaderTranslator translator, GLProgramBinaries binaries, GLLinkWorker worker) {
        this.gl = gl;
        this.translator = translator;
        this.binaries = binaries;
        this.worker = worker;
    }

    /** One linked program and the binding points it was translated under. */
    public static final class Program {
        private final Key key;
        public final GLBindingPoints points;
        private GLProgramBuild build;
        private int name;
        private int refs;

        private Program(Key key, GLBindingPoints points) {
            this.key = key;
            this.points = points;
        }

        /** Whether {@link #name} answers without waiting for the worker. */
        public boolean ready() {
            return build == null || build.ready();
        }

        /** The program, waiting for the worker if its link has not landed. */
        public int name(CgGLDriver gl) {
            if (build != null) {
                GLProgramBuild b = build;
                build = null;
                name = b.program(gl);
            }
            return name;
        }
    }

    /** A pipeline's program, linked once for every pipeline of the same modules and layout. */
    public Program acquire(String label, CgPipelineLayout layout, GLShaderModule... modules) {
        Program p = program(label, layout, modules);
        p.refs++;
        return p;
    }

    /** Starts the program's link if nothing has; whether it is built. */
    public boolean prepare(String label, CgPipelineLayout layout, GLShaderModule... modules) {
        return program(label, layout, modules).ready();
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
        p = new Program(key, new GLBindingPoints(layout));
        if (worker != null) worker.submit(p.build = new GLProgramBuild(translator, binaries, p.points, label, modules));
        else p.name = GLPrograms.link(gl, translator, binaries, p.points, label, modules);
        programs.put(key, p);
        return p;
    }

    private static void delete(CgGLDriver gl, Program p) {
        // A build still on the worker lands unread; one landed and never asked has no name yet
        if (p.build == null && p.name != 0) gl.glDeleteProgram(p.name);
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
