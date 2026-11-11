package com.crystalgraphics.gpu.resource;

import com.crystalgraphics.platform.rhi.CgDeviceNames;
import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.gpu.CgUploadLease;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.gl.format.GLPixels;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureRegion;

import javax.annotation.Nullable;
import java.nio.ByteBuffer;
import java.util.EnumSet;
import java.util.Set;

/**
 * A texture's storage on the installed device: made by {@link CgRHI#createTexture}, written and mipmapped through its
 * command list, each in a device section of its own. What every texture class holds, as Unreal's textures hold an RHI
 * texture; the engine's int texture ids are the names {@link CgDeviceNames} gives them.
 *
 * <pre>{@code
 * CgGpuTexture image = CgDeviceTextures.create("atlas", CgGpuTexture.Kind.D2, spec, w, h, 1, levels);
 * CgDeviceTextures.write(image, CgTextureRegion.of2D(0, 0, 0, w, h), pixels, GL_RGBA, GL_UNSIGNED_BYTE);
 * CgDeviceTextures.write(image, region, lease, 0, lease.size());   // a lease: from its unpack buffer, no copy
 * CgDeviceTextures.mipmaps(image);                                 // a spec that generates its chain
 * }</pre>
 *
 * <ul>
 *   <li>Pixels arrive as GL's {@code (format, type)}, tightly packed: converted to the image's texels on the CPU where
 *       they differ (RGB into RGBA, floats into halves), else written as they are.</li>
 *   <li>Never inside a render pass: an upload lands before the pass that samples it.</li>
 *   <li>Render thread; {@link #installed} first.</li>
 * </ul>
 */
public final class CgDeviceTextures {

    /** Uploads arrive tightly packed: rows padded to nothing. */
    private static final GLPixels.Store TIGHT = new GLPixels.Store();

    static {
        TIGHT.alignment = 1;
    }

    private CgDeviceTextures() {}

    /** Whether textures are made on a device now. */
    public static boolean installed() {
        return CgRHI.installed();
    }

    /**
     * A texture of {@code spec}'s format: sampled, copied both ways, an attachment where the format can be one and a
     * storage image where the device makes it one. {@code depthOrLayers}: a 3D texture's depth, an array's layers, a
     * cube's 6, else 1.
     */
    public static CgGpuTexture create(String label, CgGpuTexture.Kind kind, CgTextureSpec spec, int width, int height,
                                      int depthOrLayers, int levels) {
        return create(label, kind, GLFormats.of(spec.getGlInternalFormat()), width, height, depthOrLayers, levels);
    }

    /** {@link #create(String, CgGpuTexture.Kind, CgTextureSpec, int, int, int, int)} of a format a file decides. */
    public static CgGpuTexture create(String label, CgGpuTexture.Kind kind, CgFormat format, int width, int height,
                                      int depthOrLayers, int levels) {
        return CgRHI.get().createTexture(new CgGpuTexture.Desc(label, kind, format, width, height, depthOrLayers, levels,
                1, usage(format)));
    }

    /** Whether {@code image} is of {@code format}, {@code width x height x depthOrLayers}, {@code levels} deep. */
    public static boolean fits(@Nullable CgGpuTexture image, CgFormat format, int width, int height, int depthOrLayers,
                               int levels) {
        if (image == null) return false;
        CgGpuTexture.Desc d = image.desc();
        return d.format() == format && d.width() == width && d.height() == height && d.depthOrLayers() == depthOrLayers
                && d.mips() == levels;
    }

    private static Set<CgGpuTexture.Usage> usage(CgFormat format) {
        CgRHI rhi = CgRHI.get();
        Set<CgGpuTexture.Usage> usage = EnumSet.of(CgGpuTexture.Usage.SAMPLED, CgGpuTexture.Usage.COPY_SRC,
                CgGpuTexture.Usage.COPY_DST);
        if (rhi.supports(format, CgGpuTexture.Usage.ATTACHMENT)) usage.add(CgGpuTexture.Usage.ATTACHMENT);
        if (rhi.supports(format, CgGpuTexture.Usage.STORAGE)) usage.add(CgGpuTexture.Usage.STORAGE);
        return usage;
    }

    /** {@code pixels}' remaining bytes, GL's {@code (format, type)} tightly packed, into {@code region}. */
    public static void write(CgGpuTexture image, CgTextureRegion region, ByteBuffer pixels, int format, int type) {
        ByteBuffer texels = texels(image, region, pixels, format, type);
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().writeTexture(image, region, texels);
        } finally {
            CgDeviceSection.end();
        }
    }

    /**
     * {@code length} bytes of {@code lease} from byte {@code from}, in its format and type, into {@code region}: copied
     * on the GPU from its unpack buffer where it has one and needs no conversion, else written from its memory.
     */
    public static void write(CgGpuTexture image, CgTextureRegion region, CgUploadLease lease, int from, int length) {
        CgGpuBuffer buffer = lease.unpackBuffer();
        CgFormat f = image.desc().format();
        if (buffer != null && !f.compressed() && GLPixels.sameLayout(lease.format(), lease.type(), f)) {
            CgDeviceSection.begin();
            try {
                CgRHI.get().encoder().copyBufferToTexture(buffer, lease.unpackOffset() + from, image, region);
            } finally {
                CgDeviceSection.end();
            }
            return;
        }
        write(image, region, lease.range(from, length), lease.format(), lease.type());
    }

    /** Every level past the first made from the one above, as {@code glGenerateMipmap}. */
    public static void mipmaps(CgGpuTexture image) {
        if (image.desc().mips() <= 1) return;
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().generateMipmaps(image);
        } finally {
            CgDeviceSection.end();
        }
    }

    /** {@code image} released through the device, its name dropped first. Null passes. */
    public static void release(@Nullable CgGpuTexture image) {
        if (image == null) return;
        CgDeviceNames.forget(image);
        CgRHI.get().release(image);
    }

    /** {@code pixels} as {@code image}'s texels: as they are where GL's pair is its format, else converted. */
    private static ByteBuffer texels(CgGpuTexture image, CgTextureRegion region, ByteBuffer pixels, int format, int type) {
        CgFormat f = image.desc().format();
        if (f.compressed() || GLPixels.sameLayout(format, type, f)) return pixels;
        return GLPixels.unpack(pixels, format, type, region.width(), region.height(), region.depth(), TIGHT, f);
    }
}
