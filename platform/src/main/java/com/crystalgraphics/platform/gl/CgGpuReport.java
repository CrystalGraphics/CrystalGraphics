package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * What this GPU and context give the engine: the API, the device, every feature a compute or draw tier is chosen
 * from, and the limits that bound it. Read once per context and logged as one line, so every client's log carries
 * its GPU's answers; {@code prodSmoke} gathers them into one table.
 *
 * <pre>{@code
 * CgGpuReport gpu = CgGpuReport.current();   // render thread, a context current
 * gpu.fact("compute");                        // "core", the extension or device feature that gives it, or "no"
 * gpu.fact("computeInvocations");             // "1024"; "n/a" where the feature is absent
 * gpu.line();                                 // "gl 4.6 core | NVIDIA GeForce ... | compute=core storage=core ..."
 * }</pre>
 *
 * <ul>
 *   <li>On GL it asks the driver. On the tracked backend it asks the device ({@code CgDevice.describe}), since the GL
 *       that backend answers is its own emulation. The keys are the same either way, so one table compares both.</li>
 *   <li>A limit is read only where its feature is present, and any error a query raised is drained.</li>
 * </ul>
 *
 * <p>Keys. Identity: {@code version profile renderer vendor driver}. Features: {@code compute storage images
 * atomicCounters subgroups floatAtomics drawIndirect multiDrawIndirect indirectCount baseInstance drawParameters
 * feedbackCount feedbackStreams bindless parallelCompile programBinary bufferStorage spirv}. Limits:
 * {@code computeInvocations computeGroupSize computeGroupCount computeSharedMemory storageBlockSize storageBindings
 * imageUnits subgroupSize subgroupStages subgroupOps feedbackSeparate feedbackInterleaved feedbackBuffers texelBuffer
 * geometryOutput textureSize}. A device adds {@code host queues subgroupSizeRange}.</p>
 */
public record CgGpuReport(String api, Map<String, String> facts) {

    private static final Logger LOG = LogManager.getLogger("CrystalGraphics");
    private static final Set<String> IDENTITY = Set.of("version", "profile", "renderer", "vendor", "driver");
    private static final String[] SUBGROUP_OPS =
            {"basic", "vote", "arithmetic", "ballot", "shuffle", "relative", "clustered", "quad"};

    public CgGpuReport {
        facts = Collections.unmodifiableMap(new LinkedHashMap<>(facts));
    }

    /** The current context's report: the driver's answers on GL, the device's on the tracked backend. */
    public static CgGpuReport current() {
        if (CgGL.backend() instanceof CgTrackedGLBackend tracked) {
            Map<String, String> facts = new LinkedHashMap<>();
            tracked.device().describe(facts::put);
            String api = facts.remove("api");
            return new CgGpuReport(api == null ? "device" : api, facts);
        }
        return readGL();
    }

    /** Logs {@link #current()} as {@code [crystalgraphics] gpu <line>}. A report that cannot be read is logged, not thrown. */
    public static void log() {
        try {
            LOG.info("[crystalgraphics] gpu {}", current().line());
        } catch (RuntimeException | LinkageError failed) {
            LOG.warn("[crystalgraphics] gpu report failed: {}", failed.toString());
        }
    }

    /** A fact's value, or {@code n/a} where it was not read. */
    public String fact(String key) {
        return facts.getOrDefault(key, "n/a");
    }

    /** {@code <api> <version> <profile> | <renderer> | <vendor> | <driver> | key=value ...}; whitespace in a value becomes {@code _}. */
    public String line() {
        StringJoiner pairs = new StringJoiner(" ");
        facts.forEach((key, value) -> {
            if (!IDENTITY.contains(key)) pairs.add(key + "=" + value.trim().replaceAll("\\s+", "_"));
        });
        String head = (api + " " + fact("version") + (facts.containsKey("profile") ? " " + fact("profile") : "")).trim();
        return head + " | " + fact("renderer") + " | " + fact("vendor") + " | " + fact("driver") + " | " + pairs;
    }

    /** The names of the bits set in {@code bits}, bit {@code i} named {@code names[i]}: {@code "vertex,compute"}. */
    public static String bitNames(int bits, String... names) {
        StringJoiner out = new StringJoiner(",");
        for (int i = 0; i < names.length; i++) if ((bits & (1 << i)) != 0) out.add(names[i]);
        return out.length() == 0 ? "none" : out.toString();
    }

    /** Subgroup operations, whose bits GL's {@code KHR_shader_subgroup} and Vulkan number alike. */
    public static String subgroupOps(int bits) {
        return bitNames(bits, SUBGROUP_OPS);
    }

    // ── GL ──────────────────────────────────────────────────────────────────────

    /** Core from {@code core} (major * 10 + minor, 0 for never), or through the first extension listed. */
    private record Feature(String key, int core, String... extensions) {
        String in(int version, Set<String> listed) {
            if (core > 0 && version >= core) return "core";
            for (String extension : extensions) if (listed.contains(extension)) return extension.substring(3);
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
            new Feature("parallelCompile", 0, "GL_KHR_parallel_shader_compile", "GL_ARB_parallel_shader_compile"),
            new Feature("programBinary", 41, "GL_ARB_get_program_binary"),
            new Feature("bufferStorage", 44, "GL_ARB_buffer_storage"),
            new Feature("spirv", 46, "GL_ARB_gl_spirv"));

    private static CgGpuReport readGL() {
        Map<String, String> f = new LinkedHashMap<>();
        int version = CgGL.glGetInteger(CgGL.GL_MAJOR_VERSION) * 10 + CgGL.glGetInteger(CgGL.GL_MINOR_VERSION);
        f.put("version", version / 10 + "." + version % 10);
        f.put("profile", (CgGL.glGetInteger(CgGL.GL_CONTEXT_PROFILE_MASK) & 1) != 0 ? "core" : "compatibility");
        f.put("renderer", CgGL.glGetString(CgGL.GL_RENDERER));
        f.put("vendor", CgGL.glGetString(CgGL.GL_VENDOR));
        f.put("driver", CgGL.glGetString(CgGL.GL_VERSION));
        f.put("glsl", CgGL.glGetString(CgGL.GL_SHADING_LANGUAGE_VERSION));

        Set<String> listed = new HashSet<>();
        for (int i = 0, n = CgGL.glGetInteger(CgGL.GL_NUM_EXTENSIONS); i < n; i++) {
            listed.add(CgGL.glGetStringi(CgGL.GL_EXTENSIONS, i));
        }
        for (Feature feature : FEATURES) f.put(feature.key(), feature.in(version, listed));

        if (has(f, "compute")) {
            f.put("computeInvocations", integer(CgGL.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS));
            f.put("computeGroupSize", dimensions(CgGL.GL_MAX_COMPUTE_WORK_GROUP_SIZE));
            f.put("computeGroupCount", dimensions(CgGL.GL_MAX_COMPUTE_WORK_GROUP_COUNT));
            f.put("computeSharedMemory", integer(CgGL.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE));
        }
        if (has(f, "storage")) {
            f.put("storageBlockSize", integer(CgGL.GL_MAX_SHADER_STORAGE_BLOCK_SIZE));
            f.put("storageBindings", integer(CgGL.GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS));
        }
        if (has(f, "images")) f.put("imageUnits", integer(CgGL.GL_MAX_IMAGE_UNITS));
        if (has(f, "subgroups")) {
            f.put("subgroupSize", integer(CgGL.GL_SUBGROUP_SIZE_KHR));
            f.put("subgroupStages", bitNames(CgGL.glGetInteger(CgGL.GL_SUBGROUP_SUPPORTED_STAGES_KHR),
                    "vertex", "fragment", "geometry", "tessControl", "tessEval", "compute"));
            f.put("subgroupOps", subgroupOps(CgGL.glGetInteger(CgGL.GL_SUBGROUP_SUPPORTED_FEATURES_KHR)));
        }
        f.put("feedbackSeparate", integer(CgGL.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_ATTRIBS) + "x"
                + integer(CgGL.GL_MAX_TRANSFORM_FEEDBACK_SEPARATE_COMPONENTS));
        f.put("feedbackInterleaved", integer(CgGL.GL_MAX_TRANSFORM_FEEDBACK_INTERLEAVED_COMPONENTS));
        if (has(f, "feedbackStreams")) f.put("feedbackBuffers", integer(CgGL.GL_MAX_TRANSFORM_FEEDBACK_BUFFERS));
        f.put("texelBuffer", integer(CgGL.GL_MAX_TEXTURE_BUFFER_SIZE));
        f.put("geometryOutput", integer(CgGL.GL_MAX_GEOMETRY_OUTPUT_VERTICES) + "/"
                + integer(CgGL.GL_MAX_GEOMETRY_TOTAL_OUTPUT_COMPONENTS));
        f.put("textureSize", integer(CgGL.GL_MAX_TEXTURE_SIZE));

        List<String> errors = CgGL.drainErrors();
        if (!errors.isEmpty()) f.put("queryErrors", String.join(",", errors));
        return new CgGpuReport("gl", f);
    }

    private static boolean has(Map<String, String> facts, String feature) {
        return !"no".equals(facts.get(feature));
    }

    private static String integer(int pname) {
        return Integer.toString(CgGL.glGetInteger(pname));
    }

    private static String dimensions(int pname) {
        return CgGL.glGetIntegeri(pname, 0) + "x" + CgGL.glGetIntegeri(pname, 1) + "x" + CgGL.glGetIntegeri(pname, 2);
    }
}
