package com.crystalgraphics.gl.texture;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.gl.CgGL;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The GL sampler objects behind {@link CgSampler}s, and which is bound on each texture unit: one object per distinct
 * sampler (and whether its texture has mips), made at first use and kept for the session.
 *
 * <pre>{@code
 * CgSamplers.forget();                         // an execution starts: the host may have bound anything
 * texture.bind(unit);
 * CgSamplers.bind(unit, sampler, texture);     // null: the texture's own sampling
 * ...
 * CgSamplers.unbindOwn();                      // it ends: the host's textures sample as their own again
 * }</pre>
 *
 * <ul>
 *   <li>Render thread only. {@link #releaseAll()} deletes them at context teardown.</li>
 *   <li>A unit bound with no sampler is unbound, so one draw's sampler never reaches the next draw's texture.</li>
 *   <li>Mipmap filters are used only on a texture with mips: on one without, GL's non-mipmap filters, never an
 *       incomplete texture.</li>
 * </ul>
 */
public final class CgSamplers {

    /** Units tracked: a unit at or past it is bound every time. */
    static final int UNITS = 32;
    /** What each unit has bound as far as we know; -1 for unknown. */
    private static final int[] BOUND = new int[UNITS];
    private static final Map<CgSampler, int[]> NAMES = new HashMap<>();
    private static float maxAnisotropy = -1f;

    static {
        Arrays.fill(BOUND, -1);
    }

    private CgSamplers() {}

    /** Binds {@code sampler}'s object to {@code unit} for {@code texture}, or none for {@code null}. */
    public static void bind(int unit, @Nullable CgSampler sampler, CgTexture texture) {
        int name = sampler == null ? 0 : name(sampler, texture.getLevels() > 1);
        if (unit < UNITS) {
            if (BOUND[unit] == name) return;
            BOUND[unit] = name;
        }
        CgGL.glBindSampler(unit, name);
    }

    /** What is bound is unknown from here: the host had control, or something unbound behind us. */
    public static void forget() {
        Arrays.fill(BOUND, -1);
    }

    /** Unbinds every sampler bound here, so what draws next samples its textures as their own. */
    public static void unbindOwn() {
        for (int unit = 0; unit < UNITS; unit++) {
            if (BOUND[unit] > 0) {
                CgGL.glBindSampler(unit, 0);
                BOUND[unit] = 0;
            }
        }
    }

    /** Deletes every sampler object: the context's teardown. */
    public static void releaseAll() {
        for (int[] names : NAMES.values()) {
            for (int name : names) if (name != 0) CgGL.glDeleteSamplers(name);
        }
        NAMES.clear();
        forget();
        maxAnisotropy = -1f;
    }

    private static int name(CgSampler sampler, boolean mipmapped) {
        int[] names = NAMES.computeIfAbsent(sampler, s -> new int[2]);
        int at = mipmapped ? 1 : 0;
        if (names[at] == 0) names[at] = make(sampler, mipmapped);
        return names[at];
    }

    private static int make(CgSampler sampler, boolean mipmapped) {
        int name = CgGL.glGenSamplers();
        int min, mag;
        switch (sampler.filter()) {
            case NEAREST:
                min = mipmapped ? CgGL.GL_NEAREST_MIPMAP_NEAREST : CgGL.GL_NEAREST;
                mag = CgGL.GL_NEAREST;
                break;
            case BILINEAR:
                min = mipmapped ? CgGL.GL_LINEAR_MIPMAP_NEAREST : CgGL.GL_LINEAR;
                mag = CgGL.GL_LINEAR;
                break;
            default:
                min = mipmapped ? CgGL.GL_LINEAR_MIPMAP_LINEAR : CgGL.GL_LINEAR;
                mag = CgGL.GL_LINEAR;
        }
        CgGL.glSamplerParameteri(name, CgGL.GL_TEXTURE_MIN_FILTER, min);
        CgGL.glSamplerParameteri(name, CgGL.GL_TEXTURE_MAG_FILTER, mag);
        CgGL.glSamplerParameteri(name, CgGL.GL_TEXTURE_WRAP_S, wrap(sampler.u()));
        CgGL.glSamplerParameteri(name, CgGL.GL_TEXTURE_WRAP_T, wrap(sampler.v()));
        CgGL.glSamplerParameteri(name, CgGL.GL_TEXTURE_WRAP_R, wrap(sampler.w()));
        if (sampler.anisotropy() > 1) {
            float most = maxAnisotropy();
            if (most > 1f) CgGL.glSamplerParameterf(name, CgGL.GL_TEXTURE_MAX_ANISOTROPY, Math.min(sampler.anisotropy(), most));
        }
        return name;
    }

    /** The context's most anisotropy, 1 where it has none: asked once. */
    private static float maxAnisotropy() {
        if (maxAnisotropy < 0f) {
            float most = CgGL.glGetFloat(CgGL.GL_MAX_TEXTURE_MAX_ANISOTROPY);
            // GL_INVALID_ENUM where neither GL 4.6 nor EXT_texture_filter_anisotropic answers it.
            maxAnisotropy = CgGL.glGetError() == CgGL.GL_NO_ERROR && most >= 1f ? most : 1f;
        }
        return maxAnisotropy;
    }

    private static int wrap(CgSampler.Address address) {
        switch (address) {
            case WRAP: return CgGL.GL_REPEAT;
            case MIRROR: return CgGL.GL_MIRRORED_REPEAT;
            default: return CgGL.GL_CLAMP_TO_EDGE;
        }
    }
}
