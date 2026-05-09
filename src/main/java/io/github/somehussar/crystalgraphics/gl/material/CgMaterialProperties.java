package io.github.somehussar.crystalgraphics.gl.material;

import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderBindings;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Partitioned view of a material's property list — separates UBO-eligible
 * (non-sampler) properties from sampler properties and provides typed dispatch
 * methods for both categories.
 *
 * <p>Built once per recompile from the parsed property list; immutable after
 * construction. {@code CgMaterial} stores one field:
 * {@code private CgMaterialProperties propStore = CgMaterialProperties.EMPTY}.</p>
 */
final class CgMaterialProperties {

    /** Sentinel for materials with no properties. */
    static final CgMaterialProperties EMPTY = new CgMaterialProperties(Collections.emptyList());

    private final List<CgMaterialProperty> all;
    private final List<CgMaterialProperty> uboProps;
    private final List<CgMaterialProperty> samplerProps;

    CgMaterialProperties(List<CgMaterialProperty> all) {
        this.all = Collections.unmodifiableList(new ArrayList<>(all));
        List<CgMaterialProperty> ubo     = new ArrayList<>();
        List<CgMaterialProperty> sampler = new ArrayList<>();
        for (CgMaterialProperty p : all) {
            if (p.getType().isSampler()) sampler.add(p); else ubo.add(p);
        }
        this.uboProps     = Collections.unmodifiableList(ubo);
        this.samplerProps = Collections.unmodifiableList(sampler);
    }

    boolean hasUboProps()     { return !uboProps.isEmpty(); }
    boolean hasSamplerProps() { return !samplerProps.isEmpty(); }

    /** Returns all properties — used by adapters/callers for name-based lookup. */
    List<CgMaterialProperty> all() { return all; }

    /**
     * Builds the {@link CgBufferFormat} for {@code CgMaterialBlock} from the non-sampler
     * properties. Called by {@code CgMaterial.recompile()} when {@link #hasUboProps()} is true.
     */
    CgBufferFormat buildUboFormat() {
        CgBufferFormat.Builder b = CgBufferFormat.builder("CgMaterialBlock", CgBufferFormat.MemoryLayout.STD140);
        for (CgMaterialProperty p : uboProps) p.addToFormatBuilder(b);
        return b.build();
    }

    /** Writes all non-sampler property values into the UBO writer as a single record. */
    void writeUboProps(CgBufferWriter w) {
        w.reset().beginRecord();
        for (CgMaterialProperty p : uboProps) p.writeToUbo(w);
    }

    /** Binds all sampler properties to their texture units via shader bindings. */
    void applySamplerProps(CgShaderBindings b) {
        for (CgMaterialProperty p : samplerProps) p.applyToSampler(b);
    }
}
