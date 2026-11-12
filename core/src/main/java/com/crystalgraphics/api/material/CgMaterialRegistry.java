package com.crystalgraphics.api.material;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gpu.resource.CgTexture2DArray;
import com.crystalgraphics.gpu.resource.CgTexture3D;
import com.crystalgraphics.gpu.resource.CgTextureCubemap;
import com.crystalgraphics.gpu.resource.CgTextureSettings;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.material.CgMaterialProperty;
import com.crystalgraphics.material.CgMaterialShaderRegistry;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.material.CgMaterialProxy;
import com.crystalgraphics.material.CgMaterialShader;

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

    /** Every material of a shader asset, by the state it draws under. */
    private final Map<CgMaterialShader, Map<CgMaterialState, CgMaterial>> materials = new LinkedHashMap<>();
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
        return of(CgMaterialShaderRegistry.get().getOrCreate(resourcePath), CgMaterialState.SHADER);
    }

    /** The material of {@code asset} drawn under {@code state}, made at its first ask. */
    synchronized CgMaterial of(CgMaterialShader asset, CgMaterialState state) {
        checkNotDeleted();
        Map<CgMaterialState, CgMaterial> byState = materials.computeIfAbsent(asset, a -> new HashMap<>(2));
        CgMaterial had = byState.get(state);
        if (had != null) return had;
        CgMaterial made = new CgMaterial(new CgMaterialProxy(asset, state));
        byState.put(state, made);
        return made;
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

    /** Every texture a definition's binding names, one a path: what a preparer warms is what the material samples. */
    private static final Map<String, CgTexture> TEXTURES = new ConcurrentHashMap<>();

    /**
     * The texture a material definition's binding names, made at its first ask and shared: warm it ahead of a first
     * draw, as a system's preparer does, and the draw loads nothing. Made as its settings file says, the {@code texture}
     * asset beside the image ({@code noise.png}'s {@code noise.yaml}, {@code looks.md} § Texture):
     *
     * <ul>
     *   <li>a {@code .png} sRGB unless {@code colour: linear} (data: masks, normals), as Unreal's {@code SRGB} defaults
     *       on; a {@code .hdr} linear half floats;</li>
     *   <li>with mips unless {@code mips: false}, as Unreal's texture group gives them;</li>
     *   <li>{@code cube: true}: the image is a cube's six faces stacked top to bottom, {@code w x 6w}, in Unreal's slice
     *       order ({@link CgTextureCubemap#createStacked}).</li>
     *   <li>{@code sampler: { filter: bilinear, wrap: clamp }}: its own filter and wrap, as Unreal's {@code Filter} and
     *       {@code AddressX}/{@code AddressY}, a binding's block's form; without one, or without its {@code wrap},
     *       trilinear (with mips) and repeated, as Unreal's {@code TA_Wrap}. A binding's own {@code sampler} replaces it.</li>
     * </ul>
     *
     * <pre>{@code
     * for (CgMaterialDefinition.Texture t : definition.textures().values()) t.resolve().getWidth();   // or one made in code
     * }</pre>
     *
     * @throws CgAssetException naming both, for a {@code .png} and a {@code .hdr} at one path, {@code colour: srgb} on a
     *                          {@code .hdr}, or {@code compression: hdr} or {@code hdr_compressed} on a {@code .png};
     *                          naming the file, for a {@code sampler} that is not a block or names anisotropy, which only
     *                          a binding's sampler carries
     */
    public static CgTexture texture(String path) {
        return TEXTURES.computeIfAbsent(path, CgMaterialRegistry::fromFile);
    }

    private static CgTexture fromFile(String path) {
        CgTextureSettings s = textureSettings(path);
        boolean hdr = CgTextureSettings.isFloat(path);
        int dot = path.lastIndexOf('.');
        if (hdr || path.endsWith(".png")) {
            for (String ext : new String[] {".png", ".hdr", ".exr"}) {
                String other = path.substring(0, dot) + ext;
                if (!other.equals(path) && exists(other)) {
                    throw new CgAssetException(path + " and " + other + " are two images at one path: keep one");
                }
            }
        }
        if (hdr && "srgb".equals(s.colour())) {
            throw new CgAssetException(path + " is linear float data, but its settings say colour: srgb");
        }
        if (!hdr && ("hdr".equals(s.compression()) || "hdr_compressed".equals(s.compression()))) {
            throw new CgAssetException(path + " says compression: " + s.compression() + ", which is a float image's, not an 8-bit one's");
        }
        return s.cube() ? CgTextureCubemap.createStacked(path, s) : CgTexture2D.create(path, s);
    }

    /** What {@link #texture} makes {@code path} with: its settings file's, or a texture asset's defaults where it has none. */
    static CgTextureSettings textureSettings(String path) {
        CgTextureSettings s = CgTextureSettings.of(path);
        return s.asset() ? s : CgTextureSettings.ASSET;
    }

    private static boolean exists(String path) {
        try (InputStream in = CgIO.openStream(path)) {
            return in != null;
        } catch (IOException e) {
            return true;
        }
    }

    /** Deletes {@code definition}'s materials: an old generation, once its last draw has executed. */
    public void release(CgMaterialDefinition definition) {
        Map<String, CgMaterial> byKeyword = defined.remove(definition);
        if (byKeyword != null) for (CgMaterial m : byKeyword.values()) CgMaterialProxy.of(m).delete();
    }

    /** The sampler type a texture of {@code texture}'s kind binds to. */
    private static CgMaterialProperty.Type samplerFor(CgTexture texture) {
        if (texture instanceof CgTextureCubemap) return CgMaterialProperty.Type.SAMPLER_CUBE;
        if (texture instanceof CgTexture2DArray) return CgMaterialProperty.Type.SAMPLER2D_ARRAY;
        if (texture instanceof CgTexture3D) return CgMaterialProperty.Type.SAMPLER3D;
        return CgMaterialProperty.Type.SAMPLER2D;
    }

    private static CgMaterial make(CgMaterialDefinition d, @Nullable String drawKeyword) {
        CgMaterialProxy proxy = new CgMaterialProxy(
                CgMaterialShaderRegistry.get().getOrCreate(d.permutation().shader(), d.state()), d.state());
        for (String k : d.permutation().keywords()) proxy.enableKeyword(k);
        if (drawKeyword != null && proxy.declaredKeywords().contains(drawKeyword)) proxy.enableKeyword(drawKeyword);
        CgMaterial m = new CgMaterial(proxy);
        Map<String, CgMaterialProperty> samplers = new HashMap<>();
        for (CgMaterialProperty p : m.properties()) if (p.getType().isSampler()) samplers.put(p.getName(), p);
        for (Map.Entry<String, CgMaterialDefinition.Texture> t : d.textures().entrySet()) {
            CgMaterialProperty p = samplers.get(t.getKey());
            String what = t.getValue().describe();
            if (p == null || !p.getType().isSampler()) {
                throw new IllegalStateException(d.id() + " binds " + what + " to " + t.getKey() + ", a "
                        + (p == null ? "missing sampler" : p.getType().getPropertyTypeName()));
            }
            CgMaterialProperty.Type wants = samplerFor(t.getValue().resolve());
            if (p.getType() != wants) {
                throw new CgAssetException(d.id() + " binds " + what + ", a " + wants.getPropertyTypeName() + "'s texture, to "
                        + t.getKey() + ", a " + p.getType().getPropertyTypeName() + ": a file is 2D, or a cube with cube: true;"
                        + " an array or a volume is made in code and bound as the object");
            }
        }
        proxy.applyProperties(b -> {
            for (Map.Entry<String, float[]> c : d.constants().entrySet()) {
                float[] v = c.getValue();
                if (v.length == 1) b.set1f(c.getKey(), v[0]);
                else if (v.length == 2) b.vec2(c.getKey(), v[0], v[1]);
                else if (v.length == 4) b.vec4(c.getKey(), v[0], v[1], v[2], v[3]);
                else b.array(c.getKey(), v);
            }
            int unit = 0;
            for (Map.Entry<String, CgMaterialDefinition.Texture> t : d.textures().entrySet()) {
                CgSampler sampler = CgTextureSettings.sampler(t.getValue().sampler());
                CgTexture texture = t.getValue().resolve();
                if (sampler == null) b.sampler(t.getKey(), unit++, texture);
                else b.sampler(t.getKey(), unit++, texture, sampler);
            }
        });
        return m;
    }

    /**
     * Marks every material's shader asset dirty, so each recompiles at its next use and every material and instance
     * made from it sees the new parse. Wire this to your reload hook (F3+T / resource pack reload).
     */
    public synchronized void reloadAll() {
        for (CgMaterialShader asset : materials.keySet()) asset.markDirty();
    }

    /**
     * Deletes all tracked material instances (freeing per-instance property UBOs), then
     * cascades to {@link CgMaterialShaderRegistry#deleteAll()} to release GL shader programs.
     * Idempotent. Must be called before {@code CgEngineLifecycle.shutdown()}.
     *
     * <p>Material instances must be deleted before shader assets so that property UBOs
     * are freed while the GL context is still valid.</p>
     */
    public void deleteAll() {
        if (!deleted) {
            deleted = true;
            for (Map<CgMaterialState, CgMaterial> byState : materials.values()) {
                for (CgMaterial mat : byState.values()) CgMaterialProxy.of(mat).delete();
            }
            materials.clear();
            for (Map<String, CgMaterial> byKeyword : defined.values()) {
                for (CgMaterial mat : byKeyword.values()) CgMaterialProxy.of(mat).delete();
            }
            defined.clear();
        }
    }

    private void checkNotDeleted() {
        if (deleted) throw new IllegalStateException("CgMaterialRegistry has been deleted");
    }
}
