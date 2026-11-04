package com.crystalgraphics.platform.gl.tracked.tracker;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.crystalgraphics.platform.rhi.binding.CgBindingLayout;
import com.crystalgraphics.platform.rhi.pipeline.CgPipelineLayout;
import com.crystalgraphics.platform.rhi.shader.CgShaderModule;

/**
 * A linked program on the tracked backend: its binding layout and its modules. The vertex stage comes twice, one
 * per clip-depth convention (spec §6): GL's range for our own passes, and zero-to-one for a pass into a host's
 * depth that uses it (Minecraft 26.2).
 */
public final class CgTrackedProgram {

    final String label;
    public final CgBindingLayout layout;
    /** {@link #layout} as its pipelines' one pushed set, made once so every pipeline of it shares the value. */
    public final CgPipelineLayout pipelineLayout;
    final CgShaderModule vertexGlDepth;
    final CgShaderModule vertexZeroToOne;
    final CgShaderModule fragment;
    /** A hash of its sources, the same in every run: what its pipelines are recorded by. 0: never recorded. */
    final long key;

    public CgTrackedProgram(String label, CgBindingLayout layout, CgShaderModule vertexGlDepth,
                            CgShaderModule vertexZeroToOne, CgShaderModule fragment) {
        this(label, layout, vertexGlDepth, vertexZeroToOne, fragment, 0L);
    }

    public CgTrackedProgram(String label, CgBindingLayout layout, CgShaderModule vertexGlDepth,
                            CgShaderModule vertexZeroToOne, CgShaderModule fragment, long key) {
        this.label = label;
        this.layout = layout;
        this.pipelineLayout = CgPipelineLayout.of(layout);
        this.vertexGlDepth = vertexGlDepth;
        this.vertexZeroToOne = vertexZeroToOne;
        this.fragment = fragment;
        this.key = key;
    }

    /** 64-bit FNV-1a over a program's sources and attribute bindings: its key across runs. Any thread. */
    public static long key(String vertex, String fragment, Map<String, Integer> attribs) {
        long h = 0xcbf29ce484222325L;
        h = fnv(h, vertex);
        h = fnv(h, "\u0000");
        h = fnv(h, fragment);
        for (Map.Entry<String, Integer> e : new TreeMap<>(attribs).entrySet()) {
            h = fnv(h, e.getKey() + "=" + e.getValue() + ";");
        }
        return h == 0 ? 1 : h;
    }

    private static long fnv(long h, String s) {
        for (int i = 0; i < s.length(); i++) {
            h ^= s.charAt(i);
            h *= 0x100000001b3L;
        }
        return h;
    }

    public String label() { return label; }

    public CgBindingLayout layout() { return layout; }

    /** Its three modules: what a link releases with its layout. */
    public List<CgShaderModule> modules() { return List.of(vertexGlDepth, vertexZeroToOne, fragment); }

    CgShaderModule vertex(boolean zeroToOne) {
        return zeroToOne ? vertexZeroToOne : vertexGlDepth;
    }
}
