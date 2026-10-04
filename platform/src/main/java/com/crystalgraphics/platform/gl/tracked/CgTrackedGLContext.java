package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.gl.CgGLContext;

/**
 * The tracked backend's capabilities: a GL 4.4 core profile, so every feature gate in {@code CgCapabilities} takes
 * its best path — SSBOs, the persistent frame ring, {@code glCopyImageSubData}. What depends on the device, such as
 * indirect counts, {@code CgCapabilities} asks the device.
 */
public final class CgTrackedGLContext implements CgGLContext {

    @Override public void probe() {}

    @Override public boolean OpenGL30() { return true; }

    @Override public boolean OpenGL32() { return true; }

    @Override public boolean OpenGL33() { return true; }

    @Override public boolean OpenGL40() { return true; }

    @Override public boolean OpenGL42() { return true; }

    @Override public boolean OpenGL43() { return true; }

    @Override public boolean OpenGL44() { return true; }

    @Override public boolean OpenGL46() { return false; }

    @Override public boolean GL_ARB_map_buffer_range() { return true; }

    @Override public boolean GL_ARB_sync() { return true; }

    @Override public boolean GL_ARB_buffer_storage() { return true; }

    @Override public boolean GL_ARB_shader_storage_buffer_object() { return true; }

    @Override public boolean GL_ARB_instanced_arrays() { return true; }

    @Override public boolean GL_ARB_sampler_objects() { return true; }

    @Override public boolean GL_ARB_explicit_attrib_location() { return true; }

    @Override public boolean GL_ARB_timer_query() { return true; }

    @Override public boolean mappingIsFree() { return true; }

    /**
     * {@code GL_COMPLETION_STATUS_KHR} is answered: at once where links run shaderc at the call, and from the worker
     * where the backend {@linkplain CgTrackedGLBackend#compileInBackground compiles in the background}.
     */
    @Override public boolean parallelShaderCompile() { return true; }

    @Override public boolean GL_ARB_compute_shader() { return true; }

    @Override public boolean GL_ARB_shader_image_load_store() { return true; }

    @Override public boolean GL_ARB_draw_indirect() { return true; }

    @Override public boolean GL_ARB_multi_draw_indirect() { return true; }

    @Override public boolean GL_ARB_indirect_parameters() { return true; }

    @Override public boolean GL_ARB_shader_draw_parameters() { return false; }

    @Override public boolean GL_ARB_base_instance() { return true; }

    /** Transform feedback is not carried: a device has compute instead. */
    @Override public boolean GL_ARB_transform_feedback2() { return false; }

    @Override public boolean GL_KHR_shader_subgroup() { return true; }

    @Override public boolean GL_NV_shader_atomic_float() { return false; }

    @Override public boolean GL_ARB_bindless_texture() { return false; }
}
