package com.crystalgraphics.api.material;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgMipmapConfig;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.gl.texture.CgTextureCubemap;
import com.crystalgraphics.util.io.CgOpenExr;
import com.crystalgraphics.util.io.CgRadianceHdr;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.api.texture.CgTextures;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.gl.material.CgMaterialProperty;
import com.crystalgraphics.gl.material.CgMaterialShaderRegistry;
import com.crystalgraphics.platform.gl.CgGL;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
     *       {@code AddressX}/{@code AddressY}, a binding's block's form; without one, trilinear (with mips) and
     *       clamped. A binding's own {@code sampler} replaces it.</li>
     * </ul>
     *
     * <pre>{@code
     * for (CgMaterialDefinition.Texture t : definition.textures().values()) CgMaterialRegistry.texture(t.texture()).getWidth();
     * }</pre>
     *
     * @throws CgAssetException naming both, for a {@code .png} and a {@code .hdr} at one path, {@code colour: srgb} on a
     *                          {@code .hdr}, or {@code compression: hdr} or {@code hdr_compressed} on a {@code .png};
     *                          naming the file, for a {@code sampler} that is not a block or names anisotropy, which only
     *                          a binding's sampler carries
     */
    public static CgTexture texture(String path) {
        Supplier<? extends CgTexture> provided = PROVIDED.get(path);
        if (provided != null) return provided.get();
        return TEXTURES.computeIfAbsent(path, CgMaterialRegistry::fromFile);
    }

    /**
     * A texture asset's settings, Unreal's {@code UTexture} properties a load reads. {@code compression} picks no format
     * yet: every image loads uncompressed, a {@code .hdr} as RGBA16F, which {@code hdr_compressed}'s BC6H falls back to
     * where a device has none ({@code Texture.cpp:1102-1104}); a cooked {@code .cgtex} carries its own blocks.
     */
    private record Settings(@Nullable String colour, boolean mips, @Nullable String compression, boolean cube,
                            @Nullable CgSampler sampler) {
    }

    private static CgTexture fromFile(String path) {
        Settings s = settings(path);
        boolean hdr = isFloat(path);
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
        CgTextureSpec spec = spec(path, s);
        return s.cube() ? CgTextureCubemap.createStacked(path, spec) : CgTextures.of(path, spec);
    }

    /** What {@link #texture} makes {@code path} with, as its settings say. */
    static CgTextureSpec textureSpec(String path) {
        return spec(path, settings(path));
    }

    /** A .hdr and a .exr are float data, linear: an .exr's signed halves (a vertex animation's offsets) as they are. */
    private static boolean isFloat(String path) {
        return CgRadianceHdr.is(path) || CgOpenExr.is(path);
    }

    private static CgTextureSpec spec(String path, Settings s) {
        boolean hdr = isFloat(path), srgb = !hdr && !"linear".equals(s.colour());
        var b = (hdr ? CgTextureType.RGBA16F : srgb ? CgTextureType.SRGB8_ALPHA8 : CgTextureType.RGBA8).toTextureSpec().toBuilder();
        CgSampler sampler = s.sampler();
        if (sampler == null) return b.mipmaps(s.mips() ? CgMipmapConfig.TRILINEAR : CgMipmapConfig.NONE).build();
        boolean nearest = sampler.filter() == CgSampler.Filter.NEAREST;
        int mag = nearest ? CgGL.GL_NEAREST : CgGL.GL_LINEAR;
        if (s.mips()) {
            int min = nearest ? CgGL.GL_NEAREST_MIPMAP_NEAREST : sampler.filter() == CgSampler.Filter.BILINEAR
                    ? CgGL.GL_LINEAR_MIPMAP_NEAREST : CgGL.GL_LINEAR_MIPMAP_LINEAR;
            b.mipmaps(CgMipmapConfig.enabled(min, mag));
        } else {
            b.mipmaps(CgMipmapConfig.NONE).minFilter(mag);
        }
        return b.magFilter(mag).wrapS(wrap(sampler.u())).wrapT(wrap(sampler.v())).wrapR(wrap(sampler.w())).build();
    }

    private static int wrap(CgSampler.Address address) {
        return switch (address) {
            case WRAP -> CgGL.GL_REPEAT;
            case MIRROR -> CgGL.GL_MIRRORED_REPEAT;
            case CLAMP -> CgGL.GL_CLAMP_TO_EDGE;
        };
    }

    private static boolean exists(String path) {
        try (InputStream in = CgIO.openStream(path)) {
            return in != null;
        } catch (IOException e) {
            return true;
        }
    }

    /** Every texture made in code, by the id a material's binding names it by. */
    private static final Map<String, Supplier<? extends CgTexture>> PROVIDED = new ConcurrentHashMap<>();

    /**
     * Names a texture made in code, so a material binds it as it binds an image file: a render target, a lookup table,
     * a procedural sheet. Unreal's renderer texture parameters set to a texture object rather than a file. Any kind
     * the sampler declares: 2D, an array, a volume.
     *
     * <pre>{@code
     * CgMaterialRegistry.provide("mymod:textures/fx/fire_ramp", FIRE::texture);    // made at its first bind
     * CgMaterialDefinition.of("mymod:materials/fire", SHADER).texture("_Ramp", "mymod:textures/fx/fire_ramp")
     * }</pre>
     *
     * <ul>
     *   <li>Provide it before the first material naming it draws; a binding of an id nothing provides is read as a file.</li>
     *   <li>One supplier an id: providing another under it throws.</li>
     * </ul>
     */
    public static void provide(String id, Supplier<? extends CgTexture> texture) {
        Supplier<? extends CgTexture> had = PROVIDED.putIfAbsent(id, texture);
        if (had != null && had != texture) throw new IllegalStateException(id + " is provided already");
    }

    private static boolean provided(String id) {
        return PROVIDED.containsKey(id);
    }

    /** {@code image}'s settings file, or every default where it has none. */
    private static Settings settings(String image) {
        int dot = image.lastIndexOf('.');
        if (dot < 0) return new Settings(null, true, null, false, null);
        try (InputStream in = CgIO.openStream(image.substring(0, dot) + ".yaml")) {
            if (in == null) return new Settings(null, true, null, false, null);
            Object tree = CgAssetYaml.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<?, ?> m = tree instanceof Map<?, ?> map ? map : Map.of();
            Object colour = m.get("colour"), compression = m.get("compression"), block = m.get("sampler");
            if (block != null && !(block instanceof Map)) {
                throw new CgAssetException(image + "'s sampler is " + block + ", not a block: { filter: ..., wrap: ... }");
            }
            @SuppressWarnings("unchecked")
            CgSampler sampler = sampler((Map<Object, Object>) block);
            if (sampler != null && sampler.anisotropy() > 1) {
                throw new CgAssetException(image + "'s sampler names anisotropy, which only a binding's sampler carries");
            }
            return new Settings(colour == null ? null : colour.toString(), !Boolean.FALSE.equals(m.get("mips")),
                    compression == null ? null : compression.toString(), Boolean.TRUE.equals(m.get("cube")), sampler);
        } catch (IOException e) {
            throw new UncheckedIOException(image + "'s settings do not read", e);
        }
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
            String path = t.getValue().texture();
            if (p == null || !p.getType().isSampler()) {
                throw new IllegalStateException(d.id() + " binds " + path + " to " + t.getKey() + ", a "
                        + (p == null ? "missing sampler" : p.getType().getPropertyTypeName()));
            }
            if (provided(path)) continue;
            boolean cube = texture(path) instanceof CgTextureCubemap;
            CgMaterialProperty.Type wants = cube ? CgMaterialProperty.Type.SAMPLER_CUBE : CgMaterialProperty.Type.SAMPLER2D;
            if (p.getType() != wants) {
                throw new CgAssetException(d.id() + " binds " + path + ", a " + (cube ? "cube" : "2D") + " texture, to "
                        + t.getKey() + ", a " + p.getType().getPropertyTypeName() + ": a cube takes a texture with cube: true,"
                        + " a sampler2D one without; any other kind is provided (CgMaterialRegistry.provide)");
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
                CgTexture texture = texture(t.getValue().texture());
                if (sampler == null) b.sampler(t.getKey(), unit++, texture);
                else b.sampler(t.getKey(), unit++, texture, sampler);
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
