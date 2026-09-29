package com.crystalgraphics.platform.device;

/**
 * A range of a texture's mips and layers: what an attachment renders to, or what a sampler reads. A value; a
 * device keeps the native view for each distinct one.
 *
 * <pre>{@code
 * CgTextureView all   = CgTextureView.whole(texture);            // sampling
 * CgTextureView layer = CgTextureView.attachment(array, 0, 3);    // render into mip 0 of layer 3
 * }</pre>
 */
public record CgTextureView(CgGpuTexture texture, int baseMip, int mips, int baseLayer, int layers) {

    public static CgTextureView whole(CgGpuTexture texture) {
        CgGpuTexture.Desc d = texture.desc();
        int layers = d.kind() == CgGpuTexture.Kind.D3 ? 1 : d.depthOrLayers();
        return new CgTextureView(texture, 0, d.mips(), 0, layers);
    }

    public static CgTextureView attachment(CgGpuTexture texture, int mip, int layer) {
        return new CgTextureView(texture, mip, 1, layer, 1);
    }

    /** The width of {@link #baseMip}. */
    public int width() { return Math.max(1, texture.desc().width() >> baseMip); }

    /** The height of {@link #baseMip}. */
    public int height() { return Math.max(1, texture.desc().height() >> baseMip); }
}
