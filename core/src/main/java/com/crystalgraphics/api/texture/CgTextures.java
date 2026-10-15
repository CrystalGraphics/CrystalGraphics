package com.crystalgraphics.api.texture;

import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTexture2DArray;
import com.crystalgraphics.gl.texture.CgTexture3D;
import com.crystalgraphics.gl.texture.CgTextureCubemap;

import java.util.Locale;

/**
 * Textures from files, one call each: a 2D image, a stack of frames, a volume, a cubemap. Each is cached by its paths
 * and spec and loaded at its first use, so a constant in common code costs a server nothing.
 *
 * <pre>{@code
 * static final CgTexture2D ICON = CgTextures.of("mymod:textures/gui/icon.png");                          // linear, clamped
 * static final CgTexture2D STRIP = CgTextures.of("mymod:textures/vfx/strip.png", CgTextureSpec.RGBA8_LINEAR_REPEAT);
 * static final CgTexture2DArray FLAMES = CgTextures.frames("mymod:textures/vfx/flame_%d.png", 3);       // flame_0..2
 * static final CgTexture2DArray PAINTINGS = CgTextures.array(CgTextureSpec.RGBA8_LINEAR,
 *         "mymod:textures/vfx/wide.png", "mymod:textures/vfx/thin.png");
 * static final CgTexture3D LUT = CgTextures.volume("mymod:textures/lut/lut_%d.png", 16);                 // lut_0..15
 * static final CgTextureCubemap SKY = CgTextures.cubemap(CgTextureSpec.RGBA8_LINEAR,
 *         "mymod:sky/px.png", "mymod:sky/nx.png", "mymod:sky/py.png", "mymod:sky/ny.png", "mymod:sky/pz.png", "mymod:sky/nz.png");
 * }</pre>
 *
 * <ul>
 *   <li>One image sampled two ways is two textures: the spec is part of the key.</li>
 *   <li>A file that will not load is logged and drawn as a stand-in; {@link CgTexture#isMissing()} says so.</li>
 *   <li>An array's images of different sizes are each centred on a layer the largest one's size.</li>
 *   <li>Textures made without files ({@code createEmpty}, {@code createFromPixels}) are the classes' own factories.</li>
 * </ul>
 */
public final class CgTextures {

    private CgTextures() {}

    /**
     * A 2D image, linear and clamped.
     *
     * <pre>{@code
     * static final CgTexture2D ICON = CgTextures.of("mymod:textures/gui/icon.png");
     *
     * layer.texture("_Icon", ICON);   // sampler2D: texture(_Icon, uv)
     * }</pre>
     */
    public static CgTexture2D of(String path) {
        return CgTexture2D.create(path, CgTextureSpec.RGBA8_LINEAR);
    }

    /**
     * A 2D image sampled as {@code spec}.
     *
     * <pre>{@code
     * static final CgTexture2D STRIP = CgTextures.of("mymod:textures/vfx/strip.png", CgTextureSpec.RGBA8_LINEAR_REPEAT);
     * static final CgTexture2D PIXELS = CgTextures.of("mymod:textures/gui/font.png", CgTextureSpec.RGBA8_NEAREST);
     * }</pre>
     */
    public static CgTexture2D of(String path, CgTextureSpec spec) {
        return CgTexture2D.create(path, spec);
    }

    /**
     * {@code pattern} formatted with 0 to {@code count - 1}, a layer each, linear and clamped: a flipbook.
     *
     * <pre>{@code
     * // flame_0.png, flame_1.png, flame_2.png -> layers 0, 1, 2 of one sampler2DArray
     * static final CgTexture2DArray FLAMES = CgTextures.frames("mymod:textures/vfx/flame_%d.png", 3);
     *
     * layer.texture("_Flames", FLAMES);   // a shader picks one: texture(_Flames, vec3(uv, frame))
     * }</pre>
     *
     * <p>{@code pattern} is a {@link String#format} pattern taking one int: {@code %02d} names {@code flame_00.png}.</p>
     */
    public static CgTexture2DArray frames(String pattern, int count) {
        return frames(pattern, count, CgTextureSpec.RGBA8_LINEAR);
    }

    /**
     * {@code pattern} formatted with 0 to {@code count - 1}, a layer each, sampled as {@code spec}.
     *
     * <pre>{@code
     * static final CgTexture2DArray TILES = CgTextures.frames("mymod:textures/tiles/%d.png", 16, CgTextureSpec.RGBA8_NEAREST);
     * }</pre>
     */
    public static CgTexture2DArray frames(String pattern, int count, CgTextureSpec spec) {
        return CgTexture2DArray.create(spec, numbered(pattern, count));
    }

    /**
     * A layer for each of {@code paths}, in order: files that are not numbered. Smaller images are centred on a layer
     * the largest one's size.
     *
     * <pre>{@code
     * static final CgTexture2DArray PAINTINGS = CgTextures.array(CgTextureSpec.RGBA8_LINEAR,
     *         "mymod:textures/vfx/dash_wide.png", "mymod:textures/vfx/dash_thin.png", "mymod:textures/vfx/dash_curl.png");
     *
     * layer.texture("_Dashes", PAINTINGS);   // sampler2DArray: texture(_Dashes, vec3(uv, 2.0)) reads dash_curl
     * }</pre>
     */
    public static CgTexture2DArray array(CgTextureSpec spec, String... paths) {
        return CgTexture2DArray.create(spec, paths);
    }

    /**
     * A slice for each of {@code paths}, in order, all one size: a 3D LUT, a volume of noise. Unlike an array, it filters
     * between slices.
     *
     * <pre>{@code
     * static final CgTexture3D LUT = CgTextures.volume(CgTextureSpec.RGBA8_LINEAR,
     *         "mymod:textures/lut/0.png", "mymod:textures/lut/1.png", "mymod:textures/lut/2.png", "mymod:textures/lut/3.png");
     *
     * layer.texture("_Lut", LUT);   // sampler3D: texture(_Lut, colour.rgb), w blended between slices
     * }</pre>
     *
     * <p>A slice of another size, or one that will not load, leaves a clear 1x1x1 volume: {@link CgTexture#isMissing()}.</p>
     */
    public static CgTexture3D volume(CgTextureSpec spec, String... paths) {
        return CgTexture3D.create(spec, paths);
    }

    /**
     * {@code pattern} formatted with 0 to {@code count - 1}, a slice each, linear and clamped.
     *
     * <pre>{@code
     * // lut_0.png .. lut_15.png -> slices 0 to 15, bottom to top
     * static final CgTexture3D LUT = CgTextures.volume("mymod:textures/lut/lut_%d.png", 16);
     * }</pre>
     */
    public static CgTexture3D volume(String pattern, int count) {
        return volume(pattern, count, CgTextureSpec.RGBA8_LINEAR);
    }

    /**
     * {@code pattern} formatted with 0 to {@code count - 1}, a slice each, sampled as {@code spec}.
     *
     * <pre>{@code
     * static final CgTexture3D SMOKE = CgTextures.volume("mymod:textures/smoke/%02d.png", 32, CgTextureSpec.RGBA8_LINEAR_REPEAT);
     * }</pre>
     */
    public static CgTexture3D volume(String pattern, int count, CgTextureSpec spec) {
        return CgTexture3D.create(spec, numbered(pattern, count));
    }

    /**
     * Six square faces of one size, in GL's order: +X, -X, +Y, -Y, +Z, -Z.
     *
     * <pre>{@code
     * static final CgTextureCubemap SKY = CgTextures.cubemap(CgTextureSpec.RGBA8_LINEAR,
     *         "mymod:textures/sky/px.png", "mymod:textures/sky/nx.png",
     *         "mymod:textures/sky/py.png", "mymod:textures/sky/ny.png",
     *         "mymod:textures/sky/pz.png", "mymod:textures/sky/nz.png");
     *
     * layer.texture("_Sky", SKY);   // samplerCube: texture(_Sky, direction)
     * }</pre>
     *
     * <p>A face that will not load, or is not square and the first's size, leaves a clear 1x1 cube:
     * {@link CgTexture#isMissing()}.</p>
     */
    public static CgTextureCubemap cubemap(CgTextureSpec spec, String posX, String negX, String posY, String negY,
                                           String posZ, String negZ) {
        return CgTextureCubemap.create(spec, posX, negX, posY, negY, posZ, negZ);
    }

    private static String[] numbered(String pattern, int count) {
        if (count < 1) throw new IllegalArgumentException(pattern + " numbered " + count + " times");
        String[] paths = new String[count];
        for (int i = 0; i < count; i++) paths[i] = String.format(Locale.ROOT, pattern, i);
        return paths;
    }
}
