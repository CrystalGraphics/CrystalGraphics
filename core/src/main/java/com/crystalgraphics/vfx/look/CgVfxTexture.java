package com.crystalgraphics.vfx.look;

import com.crystalgraphics.api.texture.CgMipmapConfig;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.gl.texture.CgTexture2DArray;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.util.io.CgTextureIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A texture a layer samples, named by its resource path and its sampler: declared in common code, where no texture can
 * be made, and loaded on the render thread the first time a material samples it.
 *
 * <pre>{@code
 * static final CgVfxTexture SLEEVE = CgVfxTexture.of("dragonforge:textures/vfx/wave_sleeve.png").repeat();
 * static final CgVfxTexture DASHES = CgVfxTexture.frames("rpgcore:textures/vfx/dash_%d.png", 9);   // a 2D array
 *
 * CgVfxLayer.of(SHADER).texture("_Strip", SLEEVE).texture("_Dashes", DASHES)...
 * }</pre>
 *
 * <ul>
 *   <li>{@link #frames} makes one {@code sampler2DArray} of numbered images, each centred on a layer the largest one's
 *       size: a flipbook, or a set of paintings to pick from, past a material's sampler limit.</li>
 *   <li>Immutable: {@code repeat()} and the like answer a new descriptor. Two equal descriptors share one texture.</li>
 *   <li>An image that will not load is logged once and sampled as the fallback checkerboard.</li>
 *   <li>A texture made at run time (a generated flipbook) is {@link #of(CgTexture)}: the caller owns it.</li>
 *   <li>One image, one sampling: a single image is the texture manager's, cached by its path, so a second descriptor
 *       of it sampled otherwise ({@code repeat()} where the first was not) draws with the first's.</li>
 * </ul>
 */
public final class CgVfxTexture {

    private static final Logger LOGGER = LogManager.getLogger("CgVfx");

    /** Every texture made, by its descriptor: equal descriptors share one. */
    private static final Map<CgVfxTexture, CgTexture> MADE = new ConcurrentHashMap<>();

    private final String[] paths;
    private final boolean array, repeat, nearest, mipmaps;
    /** A texture the caller made, or null: then {@link #paths} names what to load. */
    private final CgTexture made;

    private CgVfxTexture(String[] paths, boolean array, boolean repeat, boolean nearest, boolean mipmaps) {
        this.made = null;
        this.paths = paths;
        this.array = array;
        this.repeat = repeat;
        this.nearest = nearest;
        this.mipmaps = mipmaps;
    }

    /** One image, sampled {@code sampler2D}: linear, clamped. */
    public static CgVfxTexture of(String path) {
        return new CgVfxTexture(new String[] {Objects.requireNonNull(path, "path")}, false, false, false, false);
    }

    private CgVfxTexture(CgTexture made) {
        this.made = made;
        this.paths = new String[0];
        this.array = made instanceof CgTexture2DArray;
        this.repeat = this.nearest = this.mipmaps = false;
    }

    /**
     * A texture the caller made and owns, sampled as it was made: one generated at run time. Freeing it stays the
     * caller's; {@code repeat()} and the like do not apply.
     *
     * <pre>{@code
     * CgTexture sheet = makeFlipbook();
     * layer.texture("_Sheet", CgVfxTexture.of(sheet));
     * }</pre>
     */
    public static CgVfxTexture of(CgTexture texture) {
        return new CgVfxTexture(Objects.requireNonNull(texture, "texture"));
    }

    /** Images {@code pattern} formatted with 0 to {@code count - 1}, as one {@code sampler2DArray}. */
    public static CgVfxTexture frames(String pattern, int count) {
        if (count < 1) throw new IllegalArgumentException("frames of " + pattern + ": count " + count);
        String[] paths = new String[count];
        for (int i = 0; i < count; i++) paths[i] = String.format(Locale.ROOT, pattern, i);
        return new CgVfxTexture(paths, true, false, false, false);
    }

    /** These images, in order, as one {@code sampler2DArray}. */
    public static CgVfxTexture frames(String... paths) {
        if (paths.length == 0) throw new IllegalArgumentException("frames of no images");
        return new CgVfxTexture(paths.clone(), true, false, false, false);
    }

    /** Wraps around: a strip running round a beam. */
    public CgVfxTexture repeat() {
        if (made != null) return this;
        return new CgVfxTexture(paths, array, true, nearest, mipmaps);
    }

    /** Nearest-texel sampling: pixel art. */
    public CgVfxTexture nearest() {
        if (made != null) return this;
        return new CgVfxTexture(paths, array, repeat, true, mipmaps);
    }

    /** With mipmaps: a texture seen small and far. */
    public CgVfxTexture mipmapped() {
        if (made != null) return this;
        return new CgVfxTexture(paths, array, repeat, nearest, true);
    }

    /** Whether a shader samples it as {@code sampler2DArray}. */
    public boolean isArray() {
        return array;
    }

    /** The texture, made the first time it is asked for. Render thread. */
    public CgTexture texture() {
        if (made != null) return made;
        CgTexture texture = MADE.get(this);
        if (texture == null) {
            texture = array ? makeArray() : CgTexture2D.create(paths[0], spec());
            MADE.put(this, texture);
        }
        return texture;
    }

    private CgTextureSpec spec() {
        int filter = nearest ? CgGL.GL_NEAREST : CgGL.GL_LINEAR, wrap = repeat ? CgGL.GL_REPEAT : CgGL.GL_CLAMP_TO_EDGE;
        return CgTextureSpec.builder().type(CgTextureType.RGBA8)
                .minFilter(mipmaps ? (nearest ? CgGL.GL_NEAREST_MIPMAP_LINEAR : CgGL.GL_LINEAR_MIPMAP_LINEAR) : filter)
                .magFilter(filter).wrapS(wrap).wrapT(wrap)
                .mipmaps(mipmaps ? CgMipmapConfig.TRILINEAR : CgMipmapConfig.NONE).build();
    }

    private CgTexture makeArray() {
        CgTextureIO.CgImageData[] images = new CgTextureIO.CgImageData[paths.length];
        int width = 1, height = 1;
        for (int i = 0; i < paths.length; i++) {
            CgTextureIO.CgImageData image = CgTextureIO.load(paths[i]);
            if (image == null || image.channels() != 4) {
                LOGGER.error("Frame {} is missing or not RGBA: its layer stays empty", paths[i]);
                continue;
            }
            images[i] = image;
            width = Math.max(width, image.width());
            height = Math.max(height, image.height());
        }
        CgTexture2DArray texture = CgTexture2DArray.allocateEmpty(width, height, paths.length, spec());
        ByteBuffer clear = ByteBuffer.allocateDirect(width * height * 4);
        for (int i = 0; i < images.length; i++) {
            texture.uploadLayerRegion(i, 0, 0, width, height, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, clear.duplicate());
            CgTextureIO.CgImageData image = images[i];
            if (image == null) continue;
            texture.uploadLayerRegion(i, (width - image.width()) / 2, (height - image.height()) / 2, image.width(),
                    image.height(), CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, image.pixels());
        }
        return texture;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CgVfxTexture)) return false;
        CgVfxTexture t = (CgVfxTexture) o;
        if (made != null || t.made != null) return made == t.made;
        return array == t.array && repeat == t.repeat && nearest == t.nearest && mipmaps == t.mipmaps
                && Arrays.equals(paths, t.paths);
    }

    @Override
    public int hashCode() {
        if (made != null) return System.identityHashCode(made);
        return Arrays.hashCode(paths) * 31 + (array ? 8 : 0) + (repeat ? 4 : 0) + (nearest ? 2 : 0) + (mipmaps ? 1 : 0);
    }

    @Override
    public String toString() {
        return made != null ? "texture " + made : (array ? "frames" : "texture") + Arrays.toString(paths);
    }
}
