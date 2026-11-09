package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.rhi.CgRHI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * What this GPU gives the engine: the API, the device, every feature a compute or draw tier is chosen from, and the
 * limits that bound it, as the installed device describes them ({@code CgRHI.describe}). Read once per device and
 * logged as one line, so every client's log carries its GPU's answers; {@code prodSmoke} gathers them into one table.
 *
 * <pre>{@code
 * CgGpuReport gpu = CgGpuReport.current();   // render thread, a device installed
 * gpu.fact("compute");                        // "core", the extension or device feature that gives it, or "no"
 * gpu.fact("computeInvocations");             // "1024"; "n/a" where the feature is absent
 * gpu.line();                                 // "gl 4.6 core | NVIDIA GeForce ... | compute=core storage=core ..."
 * }</pre>
 *
 * <ul>
 *   <li>The GL device asks its driver, Vulkan its physical device; the keys are the same, so one table compares both.</li>
 *   <li>A limit is read only where its feature is present.</li>
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

    /** The installed device's report. */
    public static CgGpuReport current() {
        Map<String, String> facts = new LinkedHashMap<>();
        CgRHI.get().describe(facts::put);
        String api = facts.remove("api");
        return new CgGpuReport(api == null ? "device" : api, facts);
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


}
