package com.crystalgraphics.render.draw;

import com.crystalgraphics.api.texture.CgSampler;
import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler.Desc;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler.Filter;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler.MipFilter;
import com.crystalgraphics.platform.rhi.resource.CgGpuSampler.Wrap;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * The device sampler a recorded texture is read through: the {@link CgSampler} a draw named, or the texture's own
 * ({@link CgTexture#sampler()}) where it named none. One sampler per distinct description and device, kept for the
 * session, as Unreal's sampler state cache keeps them.
 *
 * <pre>{@code
 * CgGpuSampler s = CgDeviceSamplers.of(rhi, table.sampler(id, i), table.texture(id, i));
 * bindings.texture(slot, CgTextureView.whole(texture), s);
 * }</pre>
 *
 * <ul>
 *   <li>Found by the {@code CgSampler} object, so a texture answers the same one each time: a new one per call would
 *       make a description per draw.</li>
 * </ul>
 */
public final class CgDeviceSamplers {

    private static final Map<Desc, CgGpuSampler> MADE = new HashMap<>();
    private static CgRHI madeOn;

    private CgDeviceSamplers() {}

    /** {@code texture} read through {@code sampler}, or through its own where that is null. Render thread. */
    public static CgGpuSampler of(CgRHI rhi, @Nullable CgSampler sampler, CgTexture texture) {
        if (madeOn != rhi) {
            MADE.clear();
            BY_SOURCE[0].clear();
            BY_SOURCE[1].clear();
            madeOn = rhi;
        }
        CgSampler s = sampler != null ? sampler : texture.sampler();
        Object source = s != null ? s : NO_SAMPLER;
        Map<Object, CgGpuSampler> bySource = BY_SOURCE[texture.getLevels() > 1 ? 1 : 0];
        CgGpuSampler known = bySource.get(source);
        if (known != null) return known;
        Desc desc = s != null ? desc(s, texture.getLevels() > 1) : Desc.DEFAULT;
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
     * Each sampler made, by the {@code CgSampler} it was made from, for a single level (0) and for several (1): asked
     * for every texture a draw binds, so a known one is found without a description.
     */
    @SuppressWarnings("unchecked")
    private static final Map<Object, CgGpuSampler>[] BY_SOURCE = new Map[]{new IdentityHashMap<>(), new IdentityHashMap<>()};
    /** The key of a texture with no sampler of its own: an imported one, sampled as its owner left it. */
    private static final Object NO_SAMPLER = new Object();

    private static Desc singleLevel(Desc d) {
        return new Desc(d.min(), d.mag(), MipFilter.NONE, d.u(), d.v(), d.w(), d.compare(), d.minLod(), d.maxLod(),
                d.maxAnisotropy());
    }

    /** As {@code CgSamplers} makes a sampler object for it. */
    static Desc desc(CgSampler s, boolean mipmapped) {
        Filter f = s.filter() == CgSampler.Filter.NEAREST ? Filter.NEAREST : Filter.LINEAR;
        MipFilter mip = !mipmapped ? MipFilter.NONE : s.filter() == CgSampler.Filter.TRILINEAR ? MipFilter.LINEAR
                : MipFilter.NEAREST;
        return new Desc(f, f, mip, wrap(s.u()), wrap(s.v()), wrap(s.w()), s.compare(), -1000f, 1000f,
                Math.max(1, s.anisotropy()));
    }

    private static Wrap wrap(CgSampler.Address a) {
        return switch (a) {
            case WRAP -> Wrap.REPEAT;
            case MIRROR -> Wrap.MIRRORED_REPEAT;
            case CLAMP -> Wrap.CLAMP_TO_EDGE;
            case BORDER -> Wrap.CLAMP_TO_BORDER;
        };
    }
}
