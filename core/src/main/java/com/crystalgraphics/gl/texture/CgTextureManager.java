package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.mc.CgAssetReloader;
import com.crystalgraphics.mc.CgReloadable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Every texture made from files, cached by the files and the spec it samples them with: what each type's
 * {@code create(...)} answers from. One image sampled two ways is two textures.
 *
 * <pre>{@code
 * CgTexture2D icon = CgTexture2D.create("mymod:textures/gui/icon.png", CgTextureSpec.RGBA8_NEAREST);   // through here
 * CgTexture2D same = CgTextureManager.get().getOrCreate("mymod:textures/gui/icon.png", CgTextureSpec.RGBA8_NEAREST);
 * }</pre>
 *
 * <ul>
 *   <li>A key is the paths joined by {@code \0}. Each cached texture is found again by those paths when one of them
 *       changes ({@link CgAssetReloader}), and reloaded in place: references stay valid.</li>
 *   <li>A texture it makes loads at its first use, so naming one on a server costs nothing; one whose files will not
 *       load samples a stand-in ({@link CgTexture#isMissing()}).</li>
 *   <li>{@link #freeAll()} is the context's teardown; {@link #reloadAll()} a resource reload.</li>
 * </ul>
 */
public final class CgTextureManager {

    private static final Logger LOGGER = Logger.getLogger(CgTextureManager.class.getName());

    private static final CgTextureManager INSTANCE = new CgTextureManager();

    /** Joins multi-path keys with a character that cannot appear in asset paths. */
    static final String PATH_SEPARATOR = "\0";

    /** By key, then by spec. */
    private final Map<String, Map<CgTextureSpec, CgTexture>> cache = new HashMap<>();

    /** The shared stand-in: never in {@link #cache}. */
    private CgTexture2D fallback;

    private CgTextureManager() {}

    public static CgTextureManager get() {
        return INSTANCE;
    }

    /** The texture cached for {@code key} and {@code spec}, or null if there is none or it was deleted. */
    public synchronized CgTexture get(String key, CgTextureSpec spec) {
        Map<CgTextureSpec, CgTexture> bySpec = cache.get(key);
        CgTexture t = bySpec != null ? bySpec.get(spec) : null;
        return t != null && !t.isDeleted() ? t : null;
    }

    /**
     * The texture cached for {@code key} and {@code spec}, made by {@code maker} the first time: once, and kept. A
     * maker returning null or throwing is logged and answers the {@link #getFallback() fallback}, uncached.
     */
    public synchronized CgTexture getOrCreate(String key, CgTextureSpec spec, Supplier<CgTexture> maker) {
        CgTexture cached = get(key, spec);
        if (cached != null) return cached;
        CgTexture texture;
        try {
            texture = maker.get();
        } catch (RuntimeException e) {
            LOGGER.log(Level.WARNING, "[CgTextureManager] Failed to create texture: " + key, e);
            return getFallback();
        }
        if (texture == null) {
            LOGGER.log(Level.WARNING, "[CgTextureManager] Maker returned null for: {0}", key);
            return getFallback();
        }
        cache.computeIfAbsent(key, k -> new HashMap<>()).put(Objects.requireNonNull(spec, "spec"), texture);
        if (texture instanceof CgReloadable) {
            CgAssetReloader.track((CgReloadable) texture, Arrays.asList(key.split(PATH_SEPARATOR)));
        }
        return texture;
    }

    /** {@code path} sampled as {@code spec}, loaded at its first use; the {@link #getFallback() fallback} for no path. */
    public CgTexture2D getOrCreate(String path, CgTextureSpec spec) {
        if (path == null || path.isEmpty()) return getFallback();
        CgTexture result = getOrCreate(path, spec, () -> CgTexture2D.unloaded(path, spec));
        return result instanceof CgTexture2D ? (CgTexture2D) result : getFallback();
    }

    /** The shared stand-in for a texture that is not there: {@link CgTexture2D#missing()}, made at its first ask. */
    public synchronized CgTexture2D getFallback() {
        if (fallback == null || fallback.isDeleted()) fallback = CgTexture2D.missing();
        return fallback;
    }

    /** Reloads every cached texture in place: a resource reload. Each keeps what it had where a file will not load. */
    public synchronized void reloadAll() {
        for (Map<CgTextureSpec, CgTexture> bySpec : cache.values()) {
            for (CgTexture texture : bySpec.values()) texture.reload();
        }
    }

    /** Deletes every cached texture and the fallback: the context's teardown. */
    public synchronized void freeAll() {
        for (Map<CgTextureSpec, CgTexture> bySpec : cache.values()) {
            for (CgTexture texture : bySpec.values()) {
                if (texture instanceof CgReloadable) CgAssetReloader.untrack((CgReloadable) texture);
                texture.delete();
            }
        }
        cache.clear();
        if (fallback != null) fallback.delete();
        fallback = null;
    }
}
