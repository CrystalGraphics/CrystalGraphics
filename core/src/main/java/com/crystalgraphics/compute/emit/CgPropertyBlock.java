package com.crystalgraphics.compute.emit;

import com.crystalgraphics.api.buffer.CgBufferField;
import com.crystalgraphics.api.buffer.CgBufferFormat;
import com.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import com.crystalgraphics.gl.buffer.staging.CgStagingBuffer;
import com.crystalgraphics.gl.material.CgMaterialProperties;
import com.crystalgraphics.gl.material.CgMaterialProperty;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * How a {@code .compute}'s {@code Properties} sit in the block its kernels read, {@code CgKernelBlock}: each value's
 * offset in floats, the defaults, and the samplers in unit order. GL-free, so a frame graph packs a dispatch's values
 * when it is recorded.
 *
 * <pre>{@code
 * CgPropertyBlock block = CgPropertyBlock.of(source.properties());
 * float[] values = block.defaults();
 * values[block.offset("_Drag", 1)] = 0.2f;
 * }</pre>
 */
public final class CgPropertyBlock {

    @Nullable
    private final CgBufferFormat format;
    private final float[] defaults;
    private final List<String> samplers;

    private CgPropertyBlock(@Nullable CgBufferFormat format, float[] defaults, List<String> samplers) {
        this.format = format;
        this.defaults = defaults;
        this.samplers = samplers;
    }

    public static CgPropertyBlock of(List<CgMaterialProperty> properties) {
        CgMaterialProperties all = new CgMaterialProperties(properties);
        List<String> samplers = new ArrayList<>();
        for (CgMaterialProperty p : all.all()) if (p.getType().isSampler()) samplers.add(p.getName());
        if (!all.hasUboProps()) return new CgPropertyBlock(null, new float[0], Collections.unmodifiableList(samplers));
        CgBufferFormat format = all.buildUboFormat();
        CgBufferWriter writer = new CgBufferWriter(new CgStagingBuffer(format.getFloatCount()), format);
        all.writeUboProps(writer);
        float[] defaults = Arrays.copyOf(writer.rawData(), format.getFloatCount());
        return new CgPropertyBlock(format, defaults, Collections.unmodifiableList(samplers));
    }

    /** Whether there are values to bind: samplers alone need no block. */
    public boolean hasValues() {
        return format != null;
    }

    /** The block's layout, std140; null without values. */
    @Nullable
    public CgBufferFormat format() {
        return format;
    }

    /** The block's size in floats. */
    public int floats() {
        return defaults.length;
    }

    /** A fresh copy of the defaults, as declared in {@code Properties}. */
    public float[] defaults() {
        return defaults.clone();
    }

    /**
     * Where value {@code name} starts, in floats, for a write of {@code components}: 1 for a float, int or bool, 2 for a
     * vec2, 4 for a vec4 or colour.
     *
     * @throws IllegalArgumentException for a name that is no value property, or a write of the wrong width
     */
    /** Where value property {@code name} starts, whatever its width: a CPU body reading one component of it. */
    public int offset(String name) {
        CgBufferField field = field(name);
        if (field == null) throw new IllegalArgumentException("no value property '" + name + "'");
        return field.getFloatOffset();
    }

    public int offset(String name, int components) {
        CgBufferField field = field(name);
        if (field == null) throw new IllegalArgumentException("no value property '" + name + "'");
        int floats = field.getType().getFloatComponents();
        int width = floats > 0 ? floats : field.getType().getDataBytes() / 4;
        if (width != components) {
            throw new IllegalArgumentException("'" + name + "' is a " + field.getType().getGlslName() + ": " + width
                    + " component(s), set with " + components);
        }
        return field.getFloatOffset();
    }

    /** Whether value {@code name} is an int or a bool, written as its bits. */
    public boolean isInteger(String name) {
        CgBufferField field = field(name);
        return field != null && field.getType().getFloatComponents() == 0;
    }

    @Nullable
    private CgBufferField field(String name) {
        if (format == null) return null;
        for (int i = 0; i < format.getFieldCount(); i++) if (format.getField(i).getName().equals(name)) return format.getField(i);
        return null;
    }

    /** The sampler properties, each bound at its index as a texture unit. */
    public List<String> samplers() {
        return samplers;
    }

    /** The unit sampler {@code name} is read from, or -1. */
    public int samplerUnit(String name) {
        return samplers.indexOf(name);
    }
}
