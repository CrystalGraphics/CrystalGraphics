package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;

import java.util.List;
import java.util.function.BiConsumer;

/**
 * What the GL device's context gives, as {@link CgGpuReport} facts: the driver's identity, each feature a tier is chosen
 * from (core, the extension that gives it, or {@code no}), and the limits that bound it. What {@code CgGLDevice.describe}
 * answers.
 *
 * <pre>{@code
 * GLReport.describe(driver, facts::put);   // "version" "4.6", "compute" "core", "computeInvocations" "1024" ...
 * }</pre>
 *
 * <p>A limit is read only where its feature is present; an extension {@code CgDisabledExtensions} hides reads "no".</p>
 */
final class GLReport {

    private static final int GL_MAJOR_VERSION = 0x821B, GL_MINOR_VERSION = 0x821C, GL_CONTEXT_PROFILE_MASK = 0x9126,
            GL_VENDOR = 0x1F00, GL_RENDERER = 0x1F01, GL_VERSION = 0x1F02, GL_SHADING_LANGUAGE_VERSION = 0x8B8C,
            GL_NUM_PROGRAM_BINARY_FORMATS = 0x87FE, GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS = 0x90EB,
            GL_MAX_COMPUTE_WORK_GROUP_COUNT = 0x91BE, GL_MAX_COMPUTE_WORK_GROUP_SIZE = 0x91BF,
            GL_MAX_COMPUTE_SHARED_MEMORY_SIZE = 0x8262, GL_MAX_SHADER_STORAGE_BLOCK_SIZE = 0x90DE,
            GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS = 0x90DD, GL_MAX_IMAGE_UNITS = 0x8F38, GL_SUBGROUP_SIZE_KHR = 0x9532,
            GL_SUBGROUP_SUPPORTED_STAGES_KHR = 0x9533, GL_SUBGROUP_SUPPORTED_FEATURES_KHR = 0x9534,
            GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_COMPONENTS = 0x8C80, GL_MAX_TRANSFORM_FEEDBACK_INTERLEAVED_COMPONENTS = 0x8C8A,
            GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS = 0x8C8B, GL_MAX_TRANSFORM_FEEDBACK_BUFFERS = 0x8E70,
            GL_MAX_TEXTURE_BUFFER_SIZE = 0x8C2B, GL_MAX_GEOMETRY_OUTPUT_VERTICES = 0x8DE0,
            GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS = 0x8DE1, GL_MAX_TEXTURE_SIZE = 0x0D33;

    /** Core from {@code core} (major * 10 + minor, 0 for never), or through the first extension listed. */
    private record Feature(String key, int core, String... extensions) {
        String in(int version, CgGLDriver gl) {
            if (core > 0 && version >= core) return "core";
            for (String extension : extensions) {
                if (gl.hasExtension(extension) && !CgDisabledExtensions.all().contains(extension)) return extension.substring(3);
            }
            return "no";
        }
    }

    private static final List<Feature> FEATURES = List.of(
            new Feature("compute", 43, "GL_ARB_compute_shader"),
            new Feature("storage", 43, "GL_ARB_shader_storage_buffer_object"),
            new Feature("images", 42, "GL_ARB_shader_image_load_store"),
            new Feature("atomicCounters", 42, "GL_ARB_shader_atomic_counters"),
            new Feature("subgroups", 0, "GL_KHR_shader_subgroup"),
            new Feature("floatAtomics", 0, "GL_NV_shader_atomic_float"),
            new Feature("drawIndirect", 40, "GL_ARB_draw_indirect"),
            new Feature("multiDrawIndirect", 43, "GL_ARB_multi_draw_indirect"),
            new Feature("indirectCount", 46, "GL_ARB_indirect_parameters"),
            new Feature("baseInstance", 42, "GL_ARB_base_instance"),
            new Feature("drawParameters", 46, "GL_ARB_shader_draw_parameters"),
            new Feature("feedbackCount", 40, "GL_ARB_transform_feedback2"),
            new Feature("feedbackStreams", 40, "GL_ARB_transform_feedback3"),
            new Feature("bindless", 0, "GL_ARB_bindless_texture", "GL_NV_bindless_texture"),
            new Feature("bc7", 42, "GL_ARB_texture_compression_bptc"),
            new Feature("parallelCompile", 0, "GL_KHR_parallel_shader_compile", "GL_ARB_parallel_shader_compile"),
            new Feature("programBinary", 41, "GL_ARB_get_program_binary"),
            new Feature("bufferStorage", 44, "GL_ARB_buffer_storage"),
            new Feature("spirv", 46, "GL_ARB_gl_spirv"));

    private GLReport() {}

    static void describe(CgGLDriver gl, BiConsumer<String, String> fact) {
        int version = gl.glGetInteger(GL_MAJOR_VERSION) * 10 + gl.glGetInteger(GL_MINOR_VERSION);
        fact.accept("api", "gl");
        fact.accept("version", version / 10 + "." + version % 10);
        fact.accept("profile", (gl.glGetInteger(GL_CONTEXT_PROFILE_MASK) & 1) != 0 ? "core" : "compatibility");
        fact.accept("renderer", gl.glGetString(GL_RENDERER));
        fact.accept("vendor", gl.glGetString(GL_VENDOR));
        fact.accept("driver", gl.glGetString(GL_VERSION));
        fact.accept("glsl", gl.glGetString(GL_SHADING_LANGUAGE_VERSION));
        boolean compute = false, storage = false, images = false, subgroups = false, binary = false, streams = false;
        for (Feature feature : FEATURES) {
            String in = feature.in(version, gl);
            fact.accept(feature.key(), in);
            boolean has = !"no".equals(in);
            switch (feature.key()) {
                case "compute" -> compute = has;
                case "storage" -> storage = has;
                case "images" -> images = has;
                case "subgroups" -> subgroups = has;
                case "programBinary" -> binary = has;
                case "feedbackStreams" -> streams = has;
                default -> { }
            }
        }
        if (binary) fact.accept("programBinaryFormats", integer(gl, GL_NUM_PROGRAM_BINARY_FORMATS));
        if (compute) {
            fact.accept("computeInvocations", integer(gl, GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS));
            fact.accept("computeGroupSize", dimensions(gl, GL_MAX_COMPUTE_WORK_GROUP_SIZE));
            fact.accept("computeGroupCount", dimensions(gl, GL_MAX_COMPUTE_WORK_GROUP_COUNT));
            fact.accept("computeSharedMemory", integer(gl, GL_MAX_COMPUTE_SHARED_MEMORY_SIZE));
        }
        if (storage) {
            fact.accept("storageBlockSize", integer(gl, GL_MAX_SHADER_STORAGE_BLOCK_SIZE));
            fact.accept("storageBindings", integer(gl, GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS));
        }
        if (images) fact.accept("imageUnits", integer(gl, GL_MAX_IMAGE_UNITS));
        if (subgroups) {
            fact.accept("subgroupSize", integer(gl, GL_SUBGROUP_SIZE_KHR));
            fact.accept("subgroupStages", CgGpuReport.bitNames(gl.glGetInteger(GL_SUBGROUP_SUPPORTED_STAGES_KHR),
                    "vertex", "fragment", "geometry", "tessControl", "tessEval", "compute"));
            fact.accept("subgroupOps", CgGpuReport.subgroupOps(gl.glGetInteger(GL_SUBGROUP_SUPPORTED_FEATURES_KHR)));
        }
        fact.accept("feedbackSeparate", integer(gl, GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS) + "x"
                + integer(gl, GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_COMPONENTS));
        fact.accept("feedbackInterleaved", integer(gl, GL_MAX_TRANSFORM_FEEDBACK_INTERLEAVED_COMPONENTS));
        if (streams) fact.accept("feedbackBuffers", integer(gl, GL_MAX_TRANSFORM_FEEDBACK_BUFFERS));
        fact.accept("texelBuffer", integer(gl, GL_MAX_TEXTURE_BUFFER_SIZE));
        fact.accept("geometryOutput", integer(gl, GL_MAX_GEOMETRY_OUTPUT_VERTICES) + "/"
                + integer(gl, GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS));
        fact.accept("textureSize", integer(gl, GL_MAX_TEXTURE_SIZE));
    }

    private static String integer(CgGLDriver gl, int pname) {
        return Integer.toString(gl.glGetInteger(pname));
    }

    private static String dimensions(CgGLDriver gl, int pname) {
        int[] v = new int[1];
        StringBuilder out = new StringBuilder();
        for (int axis = 0; axis < 3; axis++) {
            gl.glGetIntegeri_v(pname, axis, v);
            if (axis > 0) out.append('x');
            out.append(v[0]);
        }
        return out.toString();
    }
}
