package io.github.somehussar.crystalgraphics.gl.material;

import io.github.somehussar.crystalgraphics.api.buffer.CgBufferFormat;
import io.github.somehussar.crystalgraphics.api.shader.CgShader;
import io.github.somehussar.crystalgraphics.api.shader.CgShaderBindings;
import io.github.somehussar.crystalgraphics.api.texture.CgTexture;
import io.github.somehussar.crystalgraphics.gl.buffer.shader.CgUniformBuffer;
import io.github.somehussar.crystalgraphics.gl.buffer.staging.CgBufferWriter;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Partitioned view of a material's property list — separates UBO-eligible
 * (non-sampler) properties from sampler properties, provides typed dispatch
 * methods for both categories, and implements {@link CgShaderBindings} for
 * name-based property writes from {@code CgMaterial.applyProperties()}.
 *
 * <p>Built once per material (never reallocated). On recompile, {@link #rebuild}
 * repopulates all internal state in-place — zero extra object creation.</p>
 *
 * <p>{@code CgMaterial} stores one field:
 * {@code private CgMaterialProperties propStore = null} (null until first recompile).
 * </p>
 *
 * <p>Only property-level writes are accepted via the {@link CgShaderBindings} surface.
 * Matrix uniforms, raw buffers, UBO wiring, and other non-property operations
 * throw {@link UnsupportedOperationException} directing the caller to use
 * {@code shader.bindings()} instead.</p>
 */
public final class CgMaterialProperties implements CgShaderBindings {

    private static final Logger LOGGER = LogManager.getLogger("CgMaterialProperties");

    /** Sentinel for contexts that need a non-null empty instance (e.g. static defaults). */
    public static final CgMaterialProperties EMPTY = new CgMaterialProperties(Collections.emptyList());

     /** O(1) name lookup — rebuilt every {@link #rebuild} call alongside the lists. */
    private Map<String, CgMaterialProperty> propsByName;
    
    private List<CgMaterialProperty> all;
    private List<CgMaterialProperty> uboProps;
    private List<CgMaterialProperty> samplerProps;
   

    public CgMaterialProperties(List<CgMaterialProperty> all) {
        partition(all);
    }

    /**
     * Repopulates all internal state from a new property list without allocating
     * a new {@code CgMaterialProperties} object. Called by {@code CgMaterial.recompile()}
     * on every hot-reload after the first.
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

    /** Returns all properties — ordered as declared in the {@code Properties { }} block. */
    public List<CgMaterialProperty> all() { return all; }

    // ── UBO / sampler dispatch ────────────────────────────────────────────────

    /**
     * Builds the {@link CgBufferFormat} for {@code CgMaterialBlock} from the non-sampler
     * properties. Called by {@code CgMaterial.recompile()} when {@link #hasUboProps()} is true.
     */
    public CgBufferFormat buildUboFormat() {
        CgBufferFormat.Builder b = CgBufferFormat.builder("CgMaterialBlock", CgBufferFormat.MemoryLayout.STD140);
        for (CgMaterialProperty p : uboProps) p.addToFormatBuilder(b);
        return b.build();
    }

    /** Writes all non-sampler property values into the UBO writer as a single record. */
    public void writeUboProps(CgBufferWriter w) {
        w.reset().beginRecord();
        for (CgMaterialProperty p : uboProps) p.writeToUbo(w);
    }

    /** Binds all sampler properties to their texture units via shader bindings. */
    public void applySamplerProps(CgShaderBindings b) {
        for (CgMaterialProperty p : samplerProps) p.applyToSampler(b);
    }

    // ── CgShaderBindings — float / int scalars ────────────────────────────────

    @Override
    public CgShaderBindings set1f(String name, float value) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) {
            if (p.getType() == CgMaterialProperty.Type.INT) p.setInt((int) value);
            else p.set(value);
        }
        return this;
    }

    @Override
    public CgShaderBindings set1i(String name, int value) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) p.setInt(value);
        return this;
    }

    // ── CgShaderBindings — vectors ────────────────────────────────────────────

    @Override
    public CgShaderBindings vec2(String name, float x, float y) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) p.set(x, y);
        return this;
    }

    @Override
    public CgShaderBindings vec2(String name, Vector2f vec2) {
        return vec2(name, vec2.x, vec2.y);
    }

    @Override
    public CgShaderBindings vec3(String name, float x, float y, float z) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) {
            if (p.getType() == CgMaterialProperty.Type.VEC4) {
                LOGGER.warn("vec3() called on property '{}' which has type VEC4. " +
                        "Only x, y, z will be updated — w retains its current value. " +
                        "Use vec4() to set all components.", name);
            }
            p.set(x, y, z);
        }
        return this;
    }

    @Override
    public CgShaderBindings vec3(String name, Vector3f vec3) {
        return vec3(name, vec3.x, vec3.y, vec3.z);
    }

    @Override
    public CgShaderBindings vec4(String name, float x, float y, float z, float w) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null) p.set(x, y, z, w);
        return this;
    }

    @Override
    public CgShaderBindings vec4(String name, Vector4f vec4) {
        return vec4(name, vec4.x, vec4.y, vec4.z, vec4.w);
    }

    // ── CgShaderBindings — samplers ───────────────────────────────────────────

    @Override
    public CgShaderBindings sampler(String name, int unit, CgTexture texture) {
        CgMaterialProperty p = propsByName.get(name);
        if (p != null && p.getType().isSampler()) p.setTexture(unit, texture);
        return this;
    }

    @Override
    public CgShaderBindings sampler(String name, int unit, int glTextureId, int glTarget) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for sampler(name, unit, glTextureId, glTarget) — "
                + "CgMaterialProperties only accepts CgTexture-wrapped sampler writes");
    }

    // ── CgShaderBindings — colors ─────────────────────────────────────────────

    @Override
    public CgShaderBindings colorARGB(String name, int argb) {
        float a = ((argb >> 24) & 0xFF) / 255f;
        float r = ((argb >> 16) & 0xFF) / 255f;
        float g = ((argb >> 8)  & 0xFF) / 255f;
        float b = ((argb)       & 0xFF) / 255f;
        return vec4(name, r, g, b, a);
    }

    @Override
    public CgShaderBindings colorRGB(String name, int rgb, float alpha) {
        float r = ((rgb >> 16) & 0xFF) / 255f;
        float g = ((rgb >> 8)  & 0xFF) / 255f;
        float b = ((rgb)       & 0xFF) / 255f;
        return vec4(name, r, g, b, alpha);
    }

    // ── CgShaderBindings — unsupported operations ─────────────────────────────

    @Override
    public CgShaderBindings array(String name, int[] array) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for array(name, int[]) — CgMaterialProperties only handles property writes");
    }

    @Override
    public CgShaderBindings array(String name, float[] array) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for array(name, float[]) — CgMaterialProperties only handles property writes");
    }

    @Override
    public CgShaderBindings buffer(String name, IntBuffer buffer) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for buffer(name, IntBuffer) — CgMaterialProperties only handles property writes");
    }

    @Override
    public CgShaderBindings buffer(String name, FloatBuffer buffer) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for buffer(name, FloatBuffer) — CgMaterialProperties only handles property writes");
    }

    @Override
    public CgShaderBindings mat3(String name, FloatBuffer buffer) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for mat3 — CgMaterialProperties only handles property writes");
    }

    @Override
    public CgShaderBindings mat3(String name, Matrix3f matrix) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for mat3 — CgMaterialProperties only handles property writes");
    }

    @Override
    public CgShaderBindings mat4(String name, FloatBuffer buffer) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for mat4 — CgMaterialProperties only handles property writes");
    }

    @Override
    public CgShaderBindings mat4(String name, Matrix4f matrix) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for mat4 — CgMaterialProperties only handles property writes");
    }

    @Override
    public CgShaderBindings ubo(CgUniformBuffer buffer) {
        throw new UnsupportedOperationException(
                "Use shader.bindings() for ubo() — CgMaterialProperties only handles property writes");
    }

    @Override
    public void clear() {}

    @Override
    public void apply(CgShader shader) {
        throw new UnsupportedOperationException(
                "CgMaterialProperties does not apply to shader programs directly — "
                + "values are flushed via CgMaterial.bind() through the material UBO path");
    }
}
