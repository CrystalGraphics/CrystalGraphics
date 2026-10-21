package com.crystalgraphics.api.material;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Singleton load/reload/delete lifecycle manager for {@link CgMaterial} instances.
 *
 * <p>Materials are keyed by resource path and cached — calling {@link #getOrCreate}
 * twice for the same path returns the same instance. The singleton is accessible
 * via {@link #get()}.</p>
 *
 * <p>Hot-reload: {@link #reloadAll()} delegates to {@link CgMaterialShaderRegistry#reloadAll()},
 * marking all shader assets dirty. Materials detect the revision change on the next
 * {@code bind()} call. Wire this to your reload hook (F3+T / resource pack reload).</p>
 *
 * <p>Teardown: {@link #deleteAll()} frees per-instance property UBOs first, then cascades to
 * {@link CgMaterialShaderRegistry#deleteAll()} to release GL shader programs.</p>
 */
public final class CgMaterialRegistry {

    // ── Singleton ─────────────────────────────────────────────────────────────

    private static final CgMaterialRegistry INSTANCE = new CgMaterialRegistry();

    /** Returns the singleton registry. */
    public static CgMaterialRegistry get() {
        return INSTANCE;
    }

    // ── Instance ──────────────────────────────────────────────────────────────

    private final Map<String, CgMaterial> materials = new LinkedHashMap<>();
    /** Each definition's materials, by the draw keyword they carry ("" for none): one generation each. */
    private final Map<CgMaterialDefinition, Map<String, CgMaterial>> defined = new IdentityHashMap<>();
    private boolean deleted;

    private CgMaterialRegistry() {}

    /**
     * Returns the material for {@code resourcePath}, loading it if not already cached.
     *
     * @param resourcePath e.g. {@code "mymod:shaders/terrain.shader"}
     * @return the material, never {@code null}
     */
    public CgMaterial getOrCreate(String resourcePath) {
        checkNotDeleted();
        CgMaterial existing = materials.get(resourcePath);
        if (existing != null) return existing;
        CgMaterial mat = CgMaterial.create(resourcePath);
        materials.put(resourcePath, mat);
        return mat;
    }

    /**
     * Returns the material for {@code key.name()}, loading it if not already cached.
     *
     * @param key typed material key
     * @return the material, never {@code null}
     */
    public CgMaterial getOrCreate(CgMaterialKey key) {
        return getOrCreate(key.name());
    }

    /**
     * The material {@code definition} draws as, made at its first ask: its archetype under its state, its keywords and
     * {@code drawKeyword} where the archetype declares it, its constants and textures. Render thread.
     *
     * <pre>{@code
     * CgMaterial glow = CgMaterialRegistry.get().getOrCreate(definition, "FX_ON_MESH");   // a mesh slot's draw
     * CgMaterial.of(definition);                                                          // with no draw keyword
     * }</pre>
     *
     * <ul>
     *   <li>One per definition and keyword: two definitions sharing a permutation share programs, never constants.</li>
     *   <li>A new generation of a material is a new definition: {@link #release} the old one once nothing draws it.</li>
     *   <li>A texture is read from its binding's path as a 2D image; a sampler of another kind fails, naming it.</li>
     * </ul>
     *
     * @throws IllegalStateException naming the definition and what no material state or binding carries yet
     */
    public CgMaterial getOrCreate(CgMaterialDefinition definition, @Nullable String drawKeyword) {
        checkNotDeleted();
        Map<String, CgMaterial> byKeyword = defined.computeIfAbsent(definition, d -> new HashMap<>());
        String key = drawKeyword == null ? "" : drawKeyword;
        CgMaterial had = byKeyword.get(key);
        if (had != null) return had;
        CgMaterial made = make(definition, drawKeyword);
        byKeyword.put(key, made);
        return made;
    }

    /** Deletes {@code definition}'s materials: an old generation, once its last draw has executed. */
    public void release(CgMaterialDefinition definition) {
        Map<String, CgMaterial> byKeyword = defined.remove(definition);
        if (byKeyword != null) for (CgMaterial m : byKeyword.values()) m.delete();
    }

    static CgMaterial make(CgMaterialDefinition d, @Nullable String drawKeyword) {
        if (Boolean.FALSE.equals(d.draw().depthTest())) {
            throw new IllegalStateException(d.id() + " turns depth_test off, which no material state carries yet");
        }
        CgMaterial m = CgMaterial.newInstance(d.permutation().shader(), d.state());
        for (String k : d.permutation().keywords()) m.enableKeyword(k);
        if (drawKeyword != null && m.declaredKeywords().contains(drawKeyword)) m.enableKeyword(drawKeyword);
        Map<String, CgMaterialProperty> samplers = new HashMap<>();
        for (CgMaterialProperty p : m.properties()) if (p.getType().isSampler()) samplers.put(p.getName(), p);
        for (Map.Entry<String, CgMaterialDefinition.Texture> t : d.textures().entrySet()) {
            CgMaterialProperty p = samplers.get(t.getKey());
            if (p == null || p.getType() != CgMaterialProperty.Type.SAMPLER2D) {
                throw new IllegalStateException(d.id() + " binds " + t.getValue().texture() + " to " + t.getKey()
                        + ", a " + (p == null ? "missing sampler" : p.getType().getPropertyTypeName())
                        + ": a material's texture is a 2D image");
            }
        }
        m.applyProperties(b -> {
            for (Map.Entry<String, float[]> c : d.constants().entrySet()) {
                float[] v = c.getValue();
                if (v.length == 1) b.set1f(c.getKey(), v[0]);
                else if (v.length == 2) b.vec2(c.getKey(), v[0], v[1]);
                else if (v.length == 4) b.vec4(c.getKey(), v[0], v[1], v[2], v[3]);
                else b.array(c.getKey(), v);
            }
            int unit = 0;
            for (Map.Entry<String, CgMaterialDefinition.Texture> t : d.textures().entrySet()) {
                CgSampler sampler = sampler(t.getValue().sampler());
                if (sampler == null) b.sampler(t.getKey(), unit++, CgTextures.of(t.getValue().texture()));
                else b.sampler(t.getKey(), unit++, CgTextures.of(t.getValue().texture()), sampler);
            }
        });
        return m;
    }

    /** A binding's {@code sampler} block; null for none, the texture's own. */
    @Nullable
    private static CgSampler sampler(@Nullable Map<Object, Object> block) {
        if (block == null) return null;
        CgSampler.Builder b = CgSampler.builder();
        Object filter = block.get("filter");
        b.filter(filter == null ? CgSampler.Filter.TRILINEAR
                : CgSampler.Filter.valueOf(String.valueOf(filter).toUpperCase(Locale.ROOT)));
        Object wrap = block.get("wrap");
        if (wrap instanceof List) {
            List<?> axes = (List<?>) wrap;
            CgSampler.Address u = address(axes.get(0)), v = axes.size() > 1 ? address(axes.get(1)) : u;
            if (axes.size() > 2) b.address(u, v, address(axes.get(2)));
            else b.address(u, v);
        } else if (wrap != null) {
            b.address(address(wrap));
        }
        if (block.get("anisotropy") instanceof Number) b.anisotropy(((Number) block.get("anisotropy")).intValue());
        return b.build();
    }

    private static CgSampler.Address address(Object wrap) {
        String w = String.valueOf(wrap);
        return w.equals("repeat") ? CgSampler.Address.WRAP : CgSampler.Address.valueOf(w.toUpperCase(Locale.ROOT));
    }

    /**
     * Marks all shader assets dirty so they recompile on the next {@code bind()}.
     * Delegates to {@link CgMaterialShaderRegistry#reloadAll()} — materials detect the
     * revision change on next {@code bind()} without a per-material loop here.
     * Wire this to your reload hook (F3+T / resource pack reload).
     */
    public void reloadAll() {
        for (CgMaterial mat : materials.values())
            mat.markDirty();
    }

    /**
     * Deletes all tracked material instances (freeing per-instance property UBOs), then
     * cascades to {@link CgMaterialShaderRegistry#deleteAll()} to release GL shader programs.
     * Idempotent. Must be called before {@code CgGraphicsLifecycle.destroyContext()}.
     *
     * <p>Material instances must be deleted before shader assets so that property UBOs
     * are freed while the GL context is still valid.</p>
     */
    public void deleteAll() {
        if (!deleted) {
            deleted = true;
            for (CgMaterial mat : materials.values())
                mat.delete();          // frees per-instance matPropsUbo
            materials.clear();
            for (Map<String, CgMaterial> byKeyword : defined.values()) for (CgMaterial mat : byKeyword.values()) mat.delete();
            defined.clear();
        }
    }

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("CgMaterialRegistry has been deleted");
    }
}
