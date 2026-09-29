package com.crystalgraphics.platform.device.resource;

import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.format.CgCompare;

/**
 * How a texture is sampled. Cheap to ask for twice: the tracked backend caches one per distinct {@link Desc}.
 */
public interface CgGpuSampler extends CgDeviceObject {

    enum Filter { NEAREST, LINEAR }

    /** {@code NONE} samples the base level only, as GL does for a non-mipmap minification filter. */
    enum MipFilter { NONE, NEAREST, LINEAR }

    enum Wrap { REPEAT, MIRRORED_REPEAT, CLAMP_TO_EDGE, CLAMP_TO_BORDER }

    /** @param compare {@code null} for an ordinary sampler, else the shadow comparison */
    record Desc(Filter min, Filter mag, MipFilter mip, Wrap u, Wrap v, Wrap w, CgCompare compare,
                float minLod, float maxLod, float maxAnisotropy) {

        /** GL's defaults for a texture nobody configured. */
        public static final Desc GL_DEFAULT = new Desc(Filter.NEAREST, Filter.LINEAR, MipFilter.LINEAR,
                Wrap.REPEAT, Wrap.REPEAT, Wrap.REPEAT, null, -1000f, 1000f, 1f);
    }

    Desc desc();
}
