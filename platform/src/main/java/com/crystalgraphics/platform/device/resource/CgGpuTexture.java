package com.crystalgraphics.platform.device.resource;

import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.format.CgFormat;

import java.util.EnumSet;
import java.util.Set;

/**
 * A device image of any kind: 2D, 2D array, 3D or cube. Its size, format and mip count are fixed; a GL texture
 * that is respecified gets a new one.
 *
 * <pre>{@code
 * CgGpuTexture atlas = device.createTexture(new CgGpuTexture.Desc("atlas", CgGpuTexture.Kind.D2_ARRAY,
 *         CgFormat.R8_UNORM, 1024, 1024, 4, 1, 1, CgGpuTexture.Usage.SAMPLED_UPLOADED));
 * }</pre>
 */
public interface CgGpuTexture extends CgDeviceObject {

    enum Kind { D2, D2_ARRAY, D3, CUBE }

    enum Usage {
        SAMPLED, ATTACHMENT, STORAGE, COPY_SRC, COPY_DST;

        public static final Set<Usage> SAMPLED_UPLOADED = EnumSet.of(SAMPLED, COPY_SRC, COPY_DST);
        public static final Set<Usage> ALL = EnumSet.allOf(Usage.class);
    }

    /**
     * @param depthOrLayers depth for {@link Kind#D3}, layers for {@link Kind#D2_ARRAY}, 6 for a cube, else 1
     * @param samples       1, or the sample count of a multisampled attachment
     */
    record Desc(String label, Kind kind, CgFormat format, int width, int height, int depthOrLayers,
                int mips, int samples, Set<Usage> usage) {}

    Desc desc();

    @Override default String label() { return desc().label(); }
}
