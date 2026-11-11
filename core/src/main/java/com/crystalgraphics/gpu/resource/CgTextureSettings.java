package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.tree.CgAssetException;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.util.io.CgIO;
import com.crystalgraphics.util.io.CgOpenExr;
import com.crystalgraphics.util.io.CgRadianceHdr;

import javax.annotation.Nullable;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A texture asset's settings: the file beside an image ({@code noise.png}'s {@code noise.yaml}) saying how it loads,
 * Unreal's {@code UTexture} properties. Every texture from a file reads it; a file without one loads plain.
 *
 * <pre>{@code
 * # mymod/textures/fx/noise.yaml
 * colour: linear                          # data, not colour: masks, normals (sRGB otherwise)
 * mips: false                             # one level (a chain otherwise)
 * sampler: { filter: nearest, wrap: clamp }   # its sampling where a binding names none
 * cube: true                              # six faces stacked top to bottom, w x 6w
 * }</pre>
 *
 * <ul>
 *   <li>With a settings file, a key left out takes Unreal's default: sRGB, mips, trilinear and repeated.</li>
 *   <li>Without one, a file loads plain: linear 8-bit colour (float for {@code .hdr} and {@code .exr}), one level,
 *       bilinear and clamped. A cooked {@code .cgtex} brings its own format and levels either way.</li>
 *   <li>{@code compression} picks no format yet: every image loads uncompressed, a {@code .hdr} as RGBA16F.</li>
 * </ul>
 *
 * @param colour  {@code srgb} or {@code linear}; null for the default
 * @param sampler its own sampling; null for the default
 */
public record CgTextureSettings(@Nullable String colour, boolean mips, @Nullable String compression, boolean cube,
                                @Nullable CgSampler sampler, boolean asset) {

    /** A file without settings. */
    public static final CgTextureSettings PLAIN = new CgTextureSettings("linear", false, null, false, CgSampler.BILINEAR_CLAMP, false);
    /** A settings file with nothing in it: Unreal's defaults. */
    public static final CgTextureSettings ASSET = new CgTextureSettings(null, true, null, false, null, true);

    /**
     * {@code image}'s settings: its file's, or {@link #PLAIN} where it has none.
     *
     * @throws CgAssetException naming the file, for a {@code sampler} that is not a block or names anisotropy, which
     *                          only a binding's sampler carries
     */
    public static CgTextureSettings of(String image) {
        int dot = image.lastIndexOf('.');
        if (dot < 0) return PLAIN;
        try (InputStream in = CgIO.openStream(image.substring(0, dot) + ".yaml")) {
            if (in == null) return PLAIN;
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
            return new CgTextureSettings(colour == null ? null : colour.toString(), !Boolean.FALSE.equals(m.get("mips")),
                    compression == null ? null : compression.toString(), Boolean.TRUE.equals(m.get("cube")), sampler, true);
        } catch (IOException e) {
            throw new UncheckedIOException(image + "'s settings do not read", e);
        }
    }

    /**
     * These, with a chain of levels or without: for a texture loaded as code says rather than its file.
     *
     * <pre>{@code
     * CgTexture2D logo = CgTexture2D.create(path, CgTextureSettings.PLAIN.withMips(true).withSampler(CgSampler.TRILINEAR_CLAMP));
     * }</pre>
     */
    public CgTextureSettings withMips(boolean mips) {
        return new CgTextureSettings(colour, mips, compression, cube, sampler, asset);
    }

    /** These, sampled by {@code sampler} where a binding names none. */
    public CgTextureSettings withSampler(CgSampler sampler) {
        return new CgTextureSettings(colour, mips, compression, cube, sampler, asset);
    }

    /** The format {@code image} loads as: float for a float image, else sRGB or linear 8-bit as the settings say. */
    public CgFormat format(String image) {
        if (isFloat(image)) return CgFormat.RGBA16_FLOAT;
        return "linear".equals(colour) || !asset && colour == null ? CgFormat.RGBA8_UNORM : CgFormat.RGBA8_SRGB;
    }

    /** Its sampling where a binding names none: its own, else trilinear with mips or bilinear without, repeated. */
    public CgSampler defaultSampler() {
        if (sampler != null) return sampler;
        return mips ? CgSampler.TRILINEAR_WRAP : CgSampler.BILINEAR_WRAP;
    }

    /** A .hdr and a .exr are float data, linear: an .exr's signed halves (a vertex animation's offsets) as they are. */
    public static boolean isFloat(String path) {
        return CgRadianceHdr.is(path) || CgOpenExr.is(path);
    }

    /**
     * A {@code sampler} block, a settings file's or a material binding's: {@code { filter: bilinear, wrap: [repeat,
     * clamp], anisotropy: 8 }}; null for none. Trilinear and repeated where a key is left out.
     */
    @Nullable
    public static CgSampler sampler(@Nullable Map<Object, Object> block) {
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
        } else {
            b.address(wrap == null ? CgSampler.Address.WRAP : address(wrap));   // Unreal's TA_Wrap when unsaid
        }
        if (block.get("anisotropy") instanceof Number) b.anisotropy(((Number) block.get("anisotropy")).intValue());
        return b.build();
    }

    private static CgSampler.Address address(Object wrap) {
        String w = String.valueOf(wrap);
        return w.equals("repeat") ? CgSampler.Address.WRAP : CgSampler.Address.valueOf(w.toUpperCase(Locale.ROOT));
    }
}
