package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgTexture;
import java.util.IdentityHashMap;
import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgCompare;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler.Desc;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler.Filter;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler.MipFilter;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler.Wrap;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The device sampler a recorded texture is read through: the {@link CgSampler} a draw named, or the texture's own
 * sampling (its spec) where it named none. One sampler per distinct description and device, kept for the session,
 * as Unreal's sampler state cache keeps them.
 *
 * <pre>{@code
 * CgGpuSampler s = CgDeviceSamplers.of(rhi, table.sampler(id, i), table.texture(id, i));
 * bindings.texture(slot, CgTextureView.whole(texture), s);
 * }</pre>
 */
public final class CgDeviceSamplers {

    private static final Map<Desc, CgGpuSampler> MADE = new HashMap<>();
    private static CgRHI madeOn;

    /** The GL values a spec holds, which this maps from. */
    private static final int GL_NEAREST = 0x2600, GL_NEAREST_MIPMAP_NEAREST = 0x2700, GL_LINEAR_MIPMAP_NEAREST = 0x2701,
            GL_NEAREST_MIPMAP_LINEAR = 0x2702, GL_LINEAR_MIPMAP_LINEAR = 0x2703, GL_REPEAT = 0x2901,
            GL_CLAMP_TO_BORDER = 0x812D, GL_MIRRORED_REPEAT = 0x8370, GL_NONE = 0;

    private CgDeviceSamplers() {}

    /** {@code texture} read through {@code sampler}, or through its own sampling where that is null. Render thread. */
    public static CgGpuSampler of(CgRHI rhi, @Nullable CgSampler sampler, CgTexture texture) {
        if (madeOn != rhi) {
            MADE.clear();
            BY_SOURCE[0].clear();
            BY_SOURCE[1].clear();
            madeOn = rhi;
        }
        Object source = sampler != null ? sampler : texture.spec() != null ? texture.spec() : NO_SPEC;
        Map<Object, CgGpuSampler> bySource = BY_SOURCE[texture.getLevels() > 1 ? 1 : 0];
        CgGpuSampler known = bySource.get(source);
        if (known != null) return known;
        Desc desc = sampler != null ? desc(sampler, texture.getLevels() > 1) : own(texture);
        // No mip filter over a single level: GL takes the texture as incomplete then, and it reads (0, 0, 0, 1)
        if (desc.mip() != MipFilter.NONE && texture.getLevels() <= 1) desc = singleLevel(desc);
        CgGpuSampler made = MADE.get(desc);
        if (made == null) {
            made = rhi.createSampler(desc);
            MADE.put(desc, made);
        }
        bySource.put(source, made);
        return made;
    }

    /**
     * Each sampler made, by the immutable {@code CgSampler} or {@code CgTextureSpec} it was made from, for a single
     * level (0) and for several (1): asked for every texture a draw binds, so a known one is found without a description.
     */
    @SuppressWarnings("unchecked")
    private static final Map<Object, CgGpuSampler>[] BY_SOURCE = new Map[]{new IdentityHashMap<>(), new IdentityHashMap<>()};
    /** The key of a texture with no spec of its own. */
    private static final Object NO_SPEC = new Object();

    private static Desc singleLevel(Desc d) {
        return new Desc(d.min(), d.mag(), MipFilter.NONE, d.u(), d.v(), d.w(), d.compare(), d.minLod(), d.maxLod(),
                d.maxAnisotropy());
    }

    /** As {@code CgSamplers} makes a sampler object for it. */
    static Desc desc(CgSampler s, boolean mipmapped) {
        Filter f = s.filter() == CgSampler.Filter.NEAREST ? Filter.NEAREST : Filter.LINEAR;
        MipFilter mip = !mipmapped ? MipFilter.NONE : s.filter() == CgSampler.Filter.TRILINEAR ? MipFilter.LINEAR
                : MipFilter.NEAREST;
        return new Desc(f, f, mip, wrap(s.u()), wrap(s.v()), wrap(s.w()), null, -1000f, 1000f,
                Math.max(1, s.anisotropy()));
    }

    /** A texture's own sampling, as its spec set it (a graph texture's, its storage's); GL's defaults for none. */
    static Desc own(CgTexture texture) {
        CgTextureSpec spec = texture.spec();
        if (spec == null) return Desc.DEFAULT;
        boolean mipmapped = spec.getMipmaps() != null && spec.getMipmaps().isEnabled();
        int min = mipmapped ? spec.getMipmaps().getMinFilter() : spec.getMinFilter();
        int mag = mipmapped ? spec.getMipmaps().getMagFilter() : spec.getMagFilter();
        // A chain of levels a pass made samples across them, as CgTexture2D's sampleChain sets it.
        if (!mipmapped && texture.getLevels() > 1) {
            min = min == GL_NEAREST ? GL_NEAREST_MIPMAP_NEAREST : GL_LINEAR_MIPMAP_LINEAR;
        }
        MipFilter mip = switch (min) {
            case GL_NEAREST_MIPMAP_NEAREST, GL_LINEAR_MIPMAP_NEAREST -> MipFilter.NEAREST;
            case GL_NEAREST_MIPMAP_LINEAR, GL_LINEAR_MIPMAP_LINEAR -> MipFilter.LINEAR;
            default -> MipFilter.NONE;
        };
        Filter minFilter = min == GL_NEAREST || min == GL_NEAREST_MIPMAP_NEAREST || min == GL_NEAREST_MIPMAP_LINEAR
                ? Filter.NEAREST : Filter.LINEAR;
        CgCompare compare = spec.getCompareMode() == GL_NONE ? null : CgDeviceStates.compare(spec.getCompareFunc());
        return new Desc(minFilter, mag == GL_NEAREST ? Filter.NEAREST : Filter.LINEAR, mip, wrap(spec.getWrapS()),
                wrap(spec.getWrapT()), wrap(spec.getWrapR()), compare, -1000f, 1000f, 1f);
    }

    private static Wrap wrap(CgSampler.Address a) {
        return switch (a) {
            case WRAP -> Wrap.REPEAT;
            case MIRROR -> Wrap.MIRRORED_REPEAT;
            case CLAMP -> Wrap.CLAMP_TO_EDGE;
        };
    }

    private static Wrap wrap(int gl) {
        return switch (gl) {
            case GL_REPEAT -> Wrap.REPEAT;
            case GL_MIRRORED_REPEAT -> Wrap.MIRRORED_REPEAT;
            case GL_CLAMP_TO_BORDER -> Wrap.CLAMP_TO_BORDER;
            default -> Wrap.CLAMP_TO_EDGE;
        };
    }
}
