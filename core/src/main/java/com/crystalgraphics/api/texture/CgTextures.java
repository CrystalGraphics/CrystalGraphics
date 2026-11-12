package com.crystalgraphics.api.texture;

import com.crystalgraphics.gpu.resource.CgTexture2D;
import com.crystalgraphics.gpu.resource.CgTexture2DArray;
import com.crystalgraphics.gpu.resource.CgTexture3D;
import com.crystalgraphics.gpu.resource.CgTextureCubemap;
import com.crystalgraphics.gpu.resource.CgTextureSettings;
import com.crystalgraphics.gpu.resource.CgTextureDesc;

import java.util.Locale;

/**
 * Textures, one call each: a 2D image ({@code of}), a stack of frames ({@code frames}), a volume ({@code volume}) or a
 * cube ({@code cube}). From files, shared by their paths and loaded at their first use, so a constant in common code
 * costs a server nothing; or made empty from a description, a texture of your own.
 *
 * <pre>{@code
 * static final CgTexture2D ICON = CgTextures.of("mymod:textures/gui/icon.png");
 * static final CgTexture2DArray FLAMES = CgTextures.frames("mymod:textures/vfx/flame_%d.png", 3);       // flame_0..2
 * static final CgTexture3D LUT = CgTextures.volume("mymod:textures/lut/lut_%d.png", 16);                 // lut_0..15
 * static final CgTexture2D RAMP = CgTextures.of("mymod:textures/fx/ramp.hdr");                          // float colour
 * static final CgTextureCubemap SKY = CgTextures.cube("mymod:textures/sky.png");                        // six faces stacked
 * }</pre>
 *
 * <p>A file loads as the settings file beside it says ({@link CgTextureSettings}): {@code strip.png}'s
 * {@code strip.yaml}.</p>
 *
 * <pre>{@code
 * # mymod/textures/vfx/strip.yaml
 * sampler: { filter: bilinear, wrap: repeat }
 * mips: false
 * }</pre>
 *
 * <pre>{@code
 * CgTexture2D lut = CgTextures.of("mymod.lut", CgTextureDesc.of(256, 256, CgFormat.RGBA8_UNORM));
 * lut.write(0, 0, 0, 256, 256, CgFormat.RGBA8_UNORM, pixels);   // any thread
 * lut.release();                                               // a made texture is its maker's to release
 *
 * CgTexture2DArray glyphs = CgTextures.frames("mymod.glyphs", CgTextureDesc.array(1024, 1024, 4, CgFormat.R8_UNORM));
 * CgTexture3D field = CgTextures.volume("mymod.field", CgTextureDesc.volume(32, 32, 32, CgFormat.RGBA16_FLOAT));
 * CgTextureCubemap sky = CgTextures.cube("mymod.sky", CgTextureDesc.cube(512, CgFormat.RGBA16_FLOAT));
 * }</pre>
 *
 * <ul>
 *   <li>Without a settings file, an image loads plain: linear 8-bit colour (float for {@code .hdr} and {@code .exr}),
 *       one level, bilinear and clamped. A cooked {@code .cgtex} brings its own format and levels.</li>
 *   <li>A file's texture is shared by every caller of its path: sample it another way by naming a sampler where you
 *       bind it, never on the texture.</li>
 *   <li>A file that will not load is logged and drawn as a stand-in; {@code isMissing()} says so.</li>
 *   <li>An array's images of different sizes are each centred on a layer the largest one's size.</li>
 * </ul>
 */
public final class CgTextures {

    private CgTextures() {}

    /**
     * A 2D image from a file, shared by every caller of its path and loaded at its first use.
     *
     * <pre>{@code
     * static final CgTexture2D ICON = CgTextures.of("mymod:textures/gui/icon.png");
     *
     * material.texture("_Icon", ICON);   // sampler2D: texture(_Icon, uv)
     * }</pre>
     *
     * <p>The file decides what it is; nothing is passed for it:</p>
     *
     * <table>
     *   <tr><th>File</th><th>Format</th><th>Levels</th></tr>
     *   <tr><td>{@code .png} and other 8-bit images</td><td>without settings {@code RGBA8_UNORM}; with them
     *       {@code RGBA8_SRGB} unless {@code colour: linear}</td><td>without settings one; with them a full chain unless
     *       {@code mips: false}</td></tr>
     *   <tr><td>{@code .hdr}, {@code .exr}</td><td>{@code RGBA16_FLOAT}, linear</td><td>as for an 8-bit image</td></tr>
     *   <tr><td>{@code .cgtex} (cooked)</td><td>BC7, sRGB or linear as it was cooked; decoded to RGBA8 where the
     *       device has no BC7</td><td>the file's own</td></tr>
     * </table>
     *
     * <ul>
     *   <li>Its size is the image's, known once it loads: {@code getWidth()} on any thread decodes it there.</li>
     *   <li>Its settings are the file beside it ({@code icon.png}'s {@code icon.yaml}, {@link CgTextureSettings});
     *       without one it loads linear, one level, bilinear and clamped.</li>
     *   <li>For an empty texture of your own, pass a name and a description instead:
     *       {@link #of(String, CgTextureDesc)}. Its first argument is a name, never a path.</li>
     * </ul>
     */
    public static CgTexture2D of(String path) {
        return CgTexture2D.create(path);
    }

    /**
     * A 2D texture of your own, empty until written: its maker's to release. Any thread.
     *
     * <pre>{@code
     * CgTexture2D lut = CgTextures.of("mymod.lut", CgTextureDesc.of(256, 256, CgFormat.RGBA8_UNORM));
     * lut.write(0, 0, 0, 256, 256, CgFormat.RGBA8_UNORM, pixels);
     * }</pre>
     *
     * <pre>{@code
     * CgTexture2D chain = CgTextures.of("mymod.chain", CgTextureDesc.of(w, h, CgFormat.RGBA16_FLOAT).withMips(5));
     * CgTexture2D pixels = CgTextures.of("mymod.pixels", CgTextureDesc.of(64, 64, CgFormat.RGBA8_UNORM))
     *         .sampler(CgSampler.NEAREST_CLAMP);
     * }</pre>
     *
     * <ul>
     *   <li>Sampled bilinear and clamped, trilinear across several levels, nearest for an integer format;
     *       {@code .sampler(...)} for another. Its caller fills every level.</li>
     *   <li>{@code name} is what the device and the logs call it.</li>
     * </ul>
     *
     * @throws IllegalArgumentException for a description that is not one 2D texture
     */
    public static CgTexture2D of(String name, CgTextureDesc desc) {
        return CgTexture2D.create(name, desc);
    }

    /**
     * {@code pattern} formatted with 0 to {@code count - 1}, a layer each: a flipbook. The first frame's settings file
     * says how all of them load.
     *
     * <pre>{@code
     * // flame_0.png, flame_1.png, flame_2.png -> layers 0, 1, 2 of one sampler2DArray
     * static final CgTexture2DArray FLAMES = CgTextures.frames("mymod:textures/vfx/flame_%d.png", 3);
     *
     * material.texture("_Flames", FLAMES);   // a shader picks one: texture(_Flames, vec3(uv, frame))
     * }</pre>
     *
     * <p>{@code pattern} is a {@link String#format} pattern taking one int: {@code %02d} names {@code flame_00.png}.</p>
     */
    public static CgTexture2DArray frames(String pattern, int count) {
        return CgTexture2DArray.create(numbered(pattern, count));
    }

    /**
     * A layer for each of {@code paths}, in order: frames that are not numbered. Smaller images are centred on a layer
     * the largest one's size.
     *
     * <pre>{@code
     * static final CgTexture2DArray DASHES = CgTextures.frames(
     *         "mymod:textures/vfx/dash_wide.png", "mymod:textures/vfx/dash_thin.png", "mymod:textures/vfx/dash_curl.png");
     *
     * material.texture("_Dashes", DASHES);   // sampler2DArray: texture(_Dashes, vec3(uv, 2.0)) reads dash_curl
     * }</pre>
     */
    public static CgTexture2DArray frames(String... paths) {
        return CgTexture2DArray.create(paths);
    }

    /**
     * An array of your own, filled layer region by layer region and deepened as it fills: its maker's to release.
     *
     * <pre>{@code
     * CgTexture2DArray glyphs = CgTextures.frames("mymod.glyphs", CgTextureDesc.array(1024, 1024, 4, CgFormat.R8_UNORM));
     * glyphs.uploadLayerRegion(2, x, y, w, h, GL_RED, GL_UNSIGNED_BYTE, glyph);
     * glyphs.growLayers(8);
     * }</pre>
     *
     * @throws IllegalArgumentException for a description that is not an array of one level
     */
    public static CgTexture2DArray frames(String name, CgTextureDesc desc) {
        return CgTexture2DArray.create(name, desc);
    }

    /**
     * {@code pattern} formatted with 0 to {@code count - 1}, a slice each: a 3D LUT, a volume of noise. Unlike an array,
     * it filters between slices.
     *
     * <pre>{@code
     * // lut_0.png .. lut_15.png -> slices 0 to 15, bottom to top
     * static final CgTexture3D LUT = CgTextures.volume("mymod:textures/lut/lut_%d.png", 16);
     *
     * material.texture("_Lut", LUT);   // sampler3D: texture(_Lut, colour.rgb), w blended between slices
     * }</pre>
     *
     * <p>A slice of another size, or one that will not load, leaves a clear 1x1x1 volume: {@code isMissing()}.</p>
     */
    public static CgTexture3D volume(String pattern, int count) {
        return CgTexture3D.create(numbered(pattern, count));
    }

    /** A slice for each of {@code paths}, in order, all one size. */
    public static CgTexture3D volume(String... paths) {
        return CgTexture3D.create(paths);
    }

    /**
     * A volume of your own, which a kernel, a bake or an upload fills: its maker's to release.
     *
     * <pre>{@code
     * CgTexture3D noise = CgTextures.volume("mymod.noise", CgTextureDesc.volume(64, 64, 64, CgFormat.R8_UNORM).withMips())
     *         .sampler(CgSampler.TRILINEAR_WRAP);
     * noise.uploadRegion(0, 0, 0, 0, 64, 64, 64, texels, GL_RED, GL_UNSIGNED_BYTE);
     * noise.generateMipmaps();
     * }</pre>
     *
     * @throws IllegalArgumentException for a description that is not a volume
     */
    public static CgTexture3D volume(String name, CgTextureDesc desc) {
        return CgTexture3D.create(name, desc);
    }

    /**
     * Six square faces stacked top to bottom in one image, {@code w x 6w}: +X, -X, +Y, -Y, +Z, -Z, each face of the cube
     * unchanged. What a {@code texture} asset with {@code cube: true} is.
     *
     * <pre>{@code
     * static final CgTextureCubemap SKY = CgTextures.cube("mymod:textures/sky.png");
     *
     * material.texture("_Sky", SKY);   // samplerCube: texture(_Sky, direction)
     * }</pre>
     *
     * <p>An image of another shape throws at its first use.</p>
     */
    public static CgTextureCubemap cube(String path) {
        return CgTextureCubemap.createStacked(path);
    }

    /**
     * Six square faces of one size, a file each, in GL's order: +X, -X, +Y, -Y, +Z, -Z.
     *
     * <pre>{@code
     * static final CgTextureCubemap SKY = CgTextures.cube(
     *         "mymod:textures/sky/px.png", "mymod:textures/sky/nx.png",
     *         "mymod:textures/sky/py.png", "mymod:textures/sky/ny.png",
     *         "mymod:textures/sky/pz.png", "mymod:textures/sky/nz.png");
     * }</pre>
     *
     * <p>A face that will not load, or is not square and the first's size, leaves a clear 1x1 cube:
     * {@code isMissing()}.</p>
     */
    public static CgTextureCubemap cube(String posX, String negX, String posY, String negY, String posZ, String negZ) {
        return CgTextureCubemap.create(posX, negX, posY, negY, posZ, negZ);
    }

    /**
     * A cube of your own, its faces written by {@code uploadFace}: its maker's to release.
     *
     * <pre>{@code
     * CgTextureCubemap sky = CgTextures.cube("mymod.sky", CgTextureDesc.cube(512, CgFormat.RGBA16_FLOAT));
     * }</pre>
     *
     * @throws IllegalArgumentException for a description that is not a cube of one level
     */
    public static CgTextureCubemap cube(String name, CgTextureDesc desc) {
        return CgTextureCubemap.create(name, desc);
    }

    private static String[] numbered(String pattern, int count) {
        if (count < 1) throw new IllegalArgumentException(pattern + " numbered " + count + " times");
        String[] paths = new String[count];
        for (int i = 0; i < count; i++) paths[i] = String.format(Locale.ROOT, pattern, i);
        return paths;
    }
}
