package com.crystalgraphics.material;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.buffer.CgBufferLayout;
import com.crystalgraphics.api.material.CgMaterialValues;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.gpu.resource.CgFallbackTextures;
import com.crystalgraphics.gpu.staging.CgBufferWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.crystalgraphics.render.draw.CgMaterialBindings;

/**
 * Partitioned view of a material's property list — separates UBO-eligible
 * (non-sampler) properties from sampler properties, provides typed dispatch
 * methods for both categories, and is the {@link CgMaterialValues} a material's proxy writes into.
 *
 * <p>Built once per material (never reallocated). On recompile, {@link #rebuild}
 * repopulates all internal state in-place — zero extra object creation.</p>
 *
 * <p>{@code CgMaterial} stores one field:
 * {@code private CgMaterialProperties propStore = null} (null until first recompile).
 * </p>
 *
 */
public final class CgMaterialProperties implements CgMaterialValues {

    private static final Logger LOGGER = LogManager.getLogger("CgMaterialProperties");

    /** Sentinel for contexts that need a non-null empty instance (e.g. static defaults). */
    public static final CgMaterialProperties EMPTY = new CgMaterialProperties(Collections.emptyList());

     /** O(1) name lookup — rebuilt every {@link #rebuild} call alongside the lists. */
    private Map<String, CgMaterialProperty> propsByName;
    
    private List<CgMaterialProperty> all;
    private List<CgMaterialProperty> uboProps;
    private List<CgMaterialProperty> samplerProps;

    /** Set when a sampler property's texture unit changes. Consumed by the proxy's writes. */
    private boolean samplerUnitChanged = false;

    /**
     * Set when a block-backed property's value actually moves -- not on a write of what is already there, and never
     * by a sampler, whose value is bound per draw rather than packed. Consumed by the proxy's writes, after which its
     * next capture repacks the block.
     */
    private boolean blockChanged = false;
   

    public CgMaterialProperties(List<CgMaterialProperty> all) {
        partition(all);
    }

    /**
     * Repopulates all internal state from a new property list without allocating
     * a new {@code CgMaterialProperties} object: a new parse of its shader, keeping the values set.
     */
    public void rebuild(List<CgMaterialProperty> newAll) {
        partition(newAll);
    }

    private void partition(List<CgMaterialProperty> source) {
        List<CgMaterialProperty> allCopy  = new ArrayList<>(source);
        List<CgMaterialProperty> ubo      = new ArrayList<>();
        List<CgMaterialProperty> sampler  = new ArrayList<>();
        Map<String, CgMaterialProperty> map = new HashMap<>();
        for (CgMaterialProperty p : allCopy) {
            if (p.getType().isSampler()) sampler.add(p); else ubo.add(p);
            map.put(p.getName(), p);
        }
        this.all         = Collections.unmodifiableList(allCopy);
        this.uboProps    = Collections.unmodifiableList(ubo);
        this.samplerProps = Collections.unmodifiableList(sampler);
        this.propsByName  = map;
    }

    // ── Partition queries ─────────────────────────────────────────────────────

    public boolean hasUboProps()     { return !uboProps.isEmpty(); }
    public boolean hasSamplerProps() { return !samplerProps.isEmpty(); }

    /** How many samplers the shader declares: the units they take, from 0. */
    public int samplerCount() { return samplerProps.size(); }

    /** Returns all properties — ordered as declared in the {@code Properties { }} block. */
    public List<CgMaterialProperty> all() { return all; }

    // ── UBO / sampler dispatch ────────────────────────────────────────────────

    /**
     * Builds the {@link CgBufferLayout} for {@code CgMaterialBlock} from the non-sampler
     * properties, where {@link #hasUboProps()}: the capture's packer and the shader map's block.
     */
    public CgBufferLayout buildUboFormat() {
        CgBufferLayout.Builder b = CgBufferLayout.builder("CgMaterialBlock", CgBufferLayout.MemoryLayout.STD140);
        for (CgMaterialProperty p : uboProps) p.addToFormatBuilder(b);
        return b.build();
    }

    /** Writes all non-sampler property values into the UBO writer as a single record. */
    public void writeUboProps(CgBufferWriter w) {
        w.reset().beginRecord();
        // Indexed, here and in bindSamplerTextures: both run per bind, and an unmodifiable list's iterator is two
        // objects a call that the JIT was not eliminating.
        for (int i = 0; i < uboProps.size(); i++) uboProps.get(i).writeToUbo(w);
    }

    /**
     * Adds each sampler's texture to a snapshot, at the unit its program reads it from: its index among the declared
     * samplers. A sampler with no texture set takes the engine texture its default names, else white of its kind (a
     * {@code sampler3D} a white volume, a {@code usampler2D} the integer 0), as an Unreal texture parameter always has its default.
     */
    public void captureSamplers(CgMaterialBindings.Builder bindings) {
        for (int i = 0; i < samplerProps.size(); i++) {
            CgTexture texture = samplerProps.get(i).boundTexture();
            if (texture == null) {
                CgMaterialProperty.Type type = samplerProps.get(i).getType();
                texture = type == CgMaterialProperty.Type.SAMPLER3D ? CgFallbackTextures.WHITE_VOLUME
                        : type == CgMaterialProperty.Type.USAMPLER2D ? CgFallbackTextures.ZERO_UINT : CgFallbackTextures.WHITE_1x1;
            }
            if (texture != null) bindings.texture(i, texture, samplerProps.get(i).getSampler());
        }
    }

    /**
     * Returns {@code true} and resets the flag if any sampler property's texture unit changed
     * since the last call. Consumed by the proxy's writes.
     */
    public boolean consumeSamplerUnitChanged() {
        boolean v = samplerUnitChanged;
        samplerUnitChanged = false;
        return v;
    }

    /**
     * Returns {@code true} and resets the flag if a block-backed property's value changed since the last call.
     *
     * <pre>{@code
     * consumer.accept(props);
     * if (props.consumeBlockChanged()) blockDirty = true;   // repack on the next bind
     * }</pre>
     */
    public boolean consumeBlockChanged() {
        boolean v = blockChanged;
        blockChanged = false;
        return v;
    }

    // ── CgMaterialValues ──────────────────────────────────────────────────────

    @Override
    public CgMaterialValues set1f(String name, float value) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) {
            if (p.getType() == CgMaterialProperty.Type.INT) blockChanged |= p.setInt((int) value);
            else blockChanged |= p.set(value);
        }
        return this;
    }

    @Override
    public CgMaterialValues set1i(String name, int value) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) blockChanged |= p.setInt(value);
        return this;
    }

    @Override
    public CgMaterialValues vec2(String name, float x, float y) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) blockChanged |= p.set(x, y);
        return this;
    }

    @Override
    public CgMaterialValues vec3(String name, float x, float y, float z) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) {
            if (p.getType() == CgMaterialProperty.Type.VEC4) {
                LOGGER.warn("vec3() called on property '{}' which has type VEC4. " +
                        "Only x, y, z will be updated — w retains its current value. " +
                        "Use vec4() to set all components.", name);
            }
            blockChanged |= p.set(x, y, z);
        }
        return this;
    }

    @Override
    public CgMaterialValues vec4(String name, float x, float y, float z, float w) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) blockChanged |= p.set(x, y, z, w);
        return this;
    }

    @Override
    public CgMaterialValues sampler(String name, int unit, CgTexture texture, CgSampler sampler) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null && p.getType().isSampler()) {
            if (p.getSamplerUnit() != unit) samplerUnitChanged = true;
            p.setTexture(unit, texture, sampler);
        }
        return this;
    }

    /** An array property's elements ({@code color[n]}, {@code vec4[n]}), four floats an element. */
    @Override
    public CgMaterialValues array(String name, float[] array) {
        CgMaterialProperty p = propsByName.get(name);
        if (p == null) return this;
        if (!p.isArray()) throw new IllegalArgumentException("'" + name + "' is not an array property");
        blockChanged |= p.setArray(array);
        return this;
    }

    /** The property named {@code name}, or null: what a binder checks a name and its type against. */
    public CgMaterialProperty property(String name) {
        return propsByName.get(name);
    }
}
