package com.crystalgraphics.platform.gl;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Extensions the engine treats as absent: for a driver whose implementation of one misbehaves, and for the harness's
 * downlevel contexts, where Mesa lists some that a real context of that version would not and cannot turn them off.
 *
 * <pre>{@code
 * -Dcrystalgraphics.gl.disableExtensions=GL_ARB_buffer_storage,GL_KHR_parallel_shader_compile
 *
 * // a backend probing an entry point itself:
 * if (caps.glClearBufferSubData != 0L && (caps.OpenGL43 || !CgDisabledExtensions.has("GL_ARB_clear_buffer_object"))) ...
 * }</pre>
 *
 * <ul>
 *   <li>Names only, separated by commas or spaces: a feature core in the context's version stays. Force a tier to turn
 *       one of those off ({@code -Dcrystalgraphics.compute.tier}, {@code -Dcrystalgraphics.shaderBuffer.tier}).</li>
 *   <li>{@link CgCapabilities} reads the context through {@link #filter}, and the GPU report leaves them out; a backend
 *       that probes an entry point itself asks {@link #has}.</li>
 * </ul>
 */
public final class CgDisabledExtensions {

    private static final Set<String> NAMES = parse(System.getProperty("crystalgraphics.gl.disableExtensions"));

    private CgDisabledExtensions() {}

    /** Whether {@code name} ({@code GL_ARB_compute_shader}) is to be treated as absent. */
    public static boolean has(String name) {
        return NAMES.contains(name);
    }

    /** Every disabled name, in the order given. */
    public static Set<String> all() {
        return NAMES;
    }

    /** {@code gl} with every disabled extension answering false; {@code gl} itself when none is. */
    public static CgGLContext filter(CgGLContext gl) {
        return NAMES.isEmpty() ? gl : new Filtered(gl);
    }

    private static Set<String> parse(String property) {
        if (property == null || property.isBlank()) return Set.of();
        Set<String> names = new LinkedHashSet<>();
        for (String name : property.split("[,\\s]+")) if (!name.isEmpty()) names.add(name);
        return Collections.unmodifiableSet(names);
    }

    private static boolean on(boolean listed, String name) {
        return listed && !NAMES.contains(name);
    }

    private record Filtered(CgGLContext gl) implements CgGLContext {
        @Override public void probe() { gl.probe(); }
        @Override public boolean OpenGL30() { return gl.OpenGL30(); }
        @Override public boolean OpenGL32() { return gl.OpenGL32(); }
        @Override public boolean OpenGL33() { return gl.OpenGL33(); }
        @Override public boolean OpenGL40() { return gl.OpenGL40(); }
        @Override public boolean OpenGL42() { return gl.OpenGL42(); }
        @Override public boolean OpenGL43() { return gl.OpenGL43(); }
        @Override public boolean OpenGL44() { return gl.OpenGL44(); }
        @Override public boolean OpenGL46() { return gl.OpenGL46(); }
        @Override public boolean GL_ARB_map_buffer_range() { return on(gl.GL_ARB_map_buffer_range(), "GL_ARB_map_buffer_range"); }
        @Override public boolean GL_ARB_sync() { return on(gl.GL_ARB_sync(), "GL_ARB_sync"); }
        @Override public boolean GL_ARB_buffer_storage() { return on(gl.GL_ARB_buffer_storage(), "GL_ARB_buffer_storage"); }
        @Override public boolean GL_ARB_shader_storage_buffer_object() {
            return on(gl.GL_ARB_shader_storage_buffer_object(), "GL_ARB_shader_storage_buffer_object");
        }
        @Override public boolean GL_ARB_instanced_arrays() { return on(gl.GL_ARB_instanced_arrays(), "GL_ARB_instanced_arrays"); }
        @Override public boolean GL_ARB_sampler_objects() { return on(gl.GL_ARB_sampler_objects(), "GL_ARB_sampler_objects"); }
        @Override public boolean GL_ARB_explicit_attrib_location() {
            return on(gl.GL_ARB_explicit_attrib_location(), "GL_ARB_explicit_attrib_location");
        }
        @Override public boolean GL_ARB_timer_query() { return on(gl.GL_ARB_timer_query(), "GL_ARB_timer_query"); }
        @Override public boolean mappingIsFree() { return gl.mappingIsFree(); }
        @Override public boolean parallelShaderCompile() {
            return gl.parallelShaderCompile()
                    && !NAMES.contains("GL_KHR_parallel_shader_compile") && !NAMES.contains("GL_ARB_parallel_shader_compile");
        }
        @Override public boolean GL_ARB_compute_shader() { return on(gl.GL_ARB_compute_shader(), "GL_ARB_compute_shader"); }
        @Override public boolean GL_ARB_shader_image_load_store() {
            return on(gl.GL_ARB_shader_image_load_store(), "GL_ARB_shader_image_load_store");
        }
        @Override public boolean GL_ARB_draw_indirect() { return on(gl.GL_ARB_draw_indirect(), "GL_ARB_draw_indirect"); }
        @Override public boolean GL_ARB_multi_draw_indirect() { return on(gl.GL_ARB_multi_draw_indirect(), "GL_ARB_multi_draw_indirect"); }
        @Override public boolean GL_ARB_indirect_parameters() { return on(gl.GL_ARB_indirect_parameters(), "GL_ARB_indirect_parameters"); }
        @Override public boolean GL_ARB_shader_draw_parameters() {
            return on(gl.GL_ARB_shader_draw_parameters(), "GL_ARB_shader_draw_parameters");
        }
        @Override public boolean GL_ARB_transform_feedback2() { return on(gl.GL_ARB_transform_feedback2(), "GL_ARB_transform_feedback2"); }
        @Override public boolean GL_KHR_shader_subgroup() { return on(gl.GL_KHR_shader_subgroup(), "GL_KHR_shader_subgroup"); }
        @Override public boolean GL_NV_shader_atomic_float() { return on(gl.GL_NV_shader_atomic_float(), "GL_NV_shader_atomic_float"); }
        @Override public boolean GL_ARB_bindless_texture() { return on(gl.GL_ARB_bindless_texture(), "GL_ARB_bindless_texture"); }
    }
}
