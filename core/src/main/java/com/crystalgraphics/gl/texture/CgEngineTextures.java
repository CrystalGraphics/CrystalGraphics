package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgTexture;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The engine's textures by name: what a sampler property's default names, as Unity's {@code "white"} and
 * {@code "black"} do. A material whose sampler was never set samples the texture its default names.
 *
 * <pre>{@code
 * Properties {
 *     _MainTex ("Albedo", sampler2D) = "white"      // 1x1 white until the material sets one
 *     _Detail  ("Detail", sampler2D) = "normal"     // a flat normal
 * }
 * }</pre>
 *
 * <p>Built in: {@code white}, {@code black}, {@code transparent}, and {@code normal} or {@code bump}. An engine
 * subsystem adds its own with {@link #register}:</p>
 *
 * <pre>{@code
 * CgEngineTextures.register("cg_noise", () -> noiseVolume);   // asked each bind; null until it exists
 * }</pre>
 *
 * <ul>
 *   <li>An unknown name, or one whose texture does not exist yet, resolves to null: the sampler binds nothing.</li>
 *   <li>The texture's type must match the sampler's ({@code sampler3D} a 3D texture); nothing checks it.</li>
 * </ul>
 */
public final class CgEngineTextures {

    private static final Map<String, Supplier<CgTexture>> NAMED = new ConcurrentHashMap<>();

    static {
        register("white", () -> CgFallbackTextures.WHITE_1x1);
        register("black", () -> CgFallbackTextures.BLACK_1x1);
        register("transparent", () -> CgFallbackTextures.TRANSPARENT);
        register("normal", () -> CgFallbackTextures.FLAT_NORMAL);
        register("bump", () -> CgFallbackTextures.FLAT_NORMAL);
    }

    private CgEngineTextures() {
    }

    /** Names {@code texture}, replacing any texture of that name; the supplier is asked at each resolve. */
    public static void register(String name, Supplier<CgTexture> texture) {
        NAMED.put(name, texture);
    }

    /** The texture {@code name} names now, or null for none. */
    public static CgTexture resolve(String name) {
        Supplier<CgTexture> texture = supplier(name);
        return texture == null ? null : texture.get();
    }

    /** What answers for {@code name}, to keep and ask each bind; null when nothing is registered under it. */
    public static Supplier<CgTexture> supplier(String name) {
        return name == null ? null : NAMED.get(name);
    }
}
