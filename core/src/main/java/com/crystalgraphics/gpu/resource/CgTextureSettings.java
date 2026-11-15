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
 * <p>A settings file with a {@code render_target} block and no image is a render target (Unreal's
 * {@code UTextureRenderTarget2D}), named by its path without an extension; {@code CgRenderTarget.of} makes it.</p>
 *
 * <pre>{@code
 * # mymod/textures/fx/mirror.yaml: the texture mymod:textures/fx/mirror
 * render_target: { size: [512, 512], format: rgba16f, clear: [0, 0, 0, 1] }
 * mips: true                              # its chain made after each draw (none otherwise)
 * }</pre>
 *
 * <ul>
 *   <li>With a settings file, a key left out takes Unreal's default: sRGB, mips, trilinear and repeated.</li>
 *   <li>Without one, a file loads plain: linear 8-bit colour (float for {@code .hdr} and {@code .exr}), one level,
 *       bilinear and clamped. A cooked {@code .cgtex} brings its own format and levels either way.</li>
 *   <li>{@code compression} picks no format yet: every image loads uncompressed, a {@code .hdr} as RGBA16F.</li>
 * </ul>
 *
 * @param colour       {@code srgb} or {@code linear}; null for the default
 * @param sampler      its own sampling; null for the default
 * @param renderTarget its {@code render_target} block; null for an image
 */
public record CgTextureSettings(@Nullable String colour, boolean mips, @Nullable String compression, boolean cube,
                                @Nullable CgSampler sampler, boolean asset, @Nullable RenderTarget renderTarget) {

    /** A file without settings. */
    public static final CgTextureSettings PLAIN = new CgTextureSettings("linear", false, null, false, CgSampler.BILINEAR_CLAMP, false, null);
    /** A settings file with nothing in it: Unreal's defaults. */
    public static final CgTextureSettings ASSET = new CgTextureSettings(null, true, null, false, null, true, null);

    /**
     * A {@code render_target} block, as Unreal's {@code UTextureRenderTarget2D} holds it: its size, its format by name
     * ({@code rgba16f}, Unreal's {@code RTF_RGBA16f}) and its clear colour, Unreal's green where unsaid.
     */
    public record RenderTarget(int width, int height, String format, float r, float g, float b, float a) {
    }

    /**
     * {@code image}'s settings: its file's, or {@link #PLAIN} where it has none.
     *
     * @throws CgAssetException naming the file, for a {@code sampler} that is not a block or names anisotropy, which
     *                          only a binding's sampler carries
     */
    public static CgTextureSettings of(String image) {
        // An image's settings sit beside it as name.yaml; a render target, which has no image, is named by that file
        int dot = image.lastIndexOf('.'), end = Math.max(image.lastIndexOf('/'), image.lastIndexOf(':'));
        String file = dot > end ? image.substring(0, dot) + ".yaml" : image + ".yaml";
        try (InputStream in = CgIO.openStream(file)) {
            if (in == null) return PLAIN;
            Object tree = CgAssetYaml.read(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            Map<?, ?> m = tree instanceof Map<?, ?> map ? map : Map.of();
            RenderTarget target = m.get("render_target") instanceof Map<?, ?> rt ? renderTarget(image, rt) : null;
            Object colour = m.get("colour"), compression = m.get("compression"), block = m.get("sampler");
            if (block != null && !(block instanceof Map)) {
                throw new CgAssetException(image + "'s sampler is " + block + ", not a block: { filter: ..., wrap: ... }");
            }
            @SuppressWarnings("unchecked")
            CgSampler sampler = sampler((Map<Object, Object>) block);
            if (sampler != null && sampler.anisotropy() > 1) {
                throw new CgAssetException(image + "'s sampler names anisotropy, which only a binding's sampler carries");
            }
            // A render target's chain is made only when asked, as Unreal's bAutoGenerateMips; an image's unless refused
            boolean mips = target != null ? Boolean.TRUE.equals(m.get("mips")) : !Boolean.FALSE.equals(m.get("mips"));
            return new CgTextureSettings(colour == null ? null : colour.toString(), mips,
                    compression == null ? null : compression.toString(), Boolean.TRUE.equals(m.get("cube")), sampler, true, target);
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
        return new CgTextureSettings(colour, mips, compression, cube, sampler, asset, renderTarget);
    }

    /** These, sampled by {@code sampler} where a binding names none. */
    public CgTextureSettings withSampler(CgSampler sampler) {
        return new CgTextureSettings(colour, mips, compression, cube, sampler, asset, renderTarget);
    }

    private static RenderTarget renderTarget(String image, Map<?, ?> block) {
        if (!(block.get("size") instanceof List<?> size) || size.size() != 2 || !(size.get(0) instanceof Number w)
                || !(size.get(1) instanceof Number h) || w.intValue() <= 0 || h.intValue() <= 0) {
            throw new CgAssetException(image + "'s render_target names no size: size: [width, height]");
        }
        String format = block.get("format") instanceof String f ? f : "rgba16f";
        float[] clear = {0f, 1f, 0f, 1f};
        if (block.get("clear") instanceof List<?> c) {
            if (c.size() != 4) throw new CgAssetException(image + "'s render_target clear is " + c + ", not [r, g, b, a]");
            for (int i = 0; i < 4; i++) clear[i] = ((Number) c.get(i)).floatValue();
        }
        return new RenderTarget(w.intValue(), h.intValue(), format, clear[0], clear[1], clear[2], clear[3]);
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
