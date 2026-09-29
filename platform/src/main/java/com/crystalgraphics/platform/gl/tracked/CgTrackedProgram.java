package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.CgBindingLayout;
import com.crystalgraphics.platform.device.CgShaderModule;

/**
 * A linked program on the tracked backend: its binding layout and its modules. The vertex stage comes twice, one
 * per clip-depth convention (spec §6): GL's range for our own passes, and zero-to-one for a pass into a host's
 * depth that uses it (Minecraft 26.2).
 */
public final class CgTrackedProgram {

    final String label;
    final CgBindingLayout layout;
    final CgShaderModule vertexGlDepth;
    final CgShaderModule vertexZeroToOne;
    final CgShaderModule fragment;

    public CgTrackedProgram(String label, CgBindingLayout layout, CgShaderModule vertexGlDepth,
                            CgShaderModule vertexZeroToOne, CgShaderModule fragment) {
        this.label = label;
        this.layout = layout;
        this.vertexGlDepth = vertexGlDepth;
        this.vertexZeroToOne = vertexZeroToOne;
        this.fragment = fragment;
    }

    public String label() { return label; }

    public CgBindingLayout layout() { return layout; }

    CgShaderModule vertex(boolean zeroToOne) {
        return zeroToOne ? vertexZeroToOne : vertexGlDepth;
    }
}
