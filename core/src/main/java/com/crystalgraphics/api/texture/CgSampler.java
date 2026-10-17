package com.crystalgraphics.api.texture;

import java.util.Objects;

/**
 * How a binding samples its texture, in place of the texture's own filter and wrap: Unreal's shared sampler source.
 * One image loaded once is sampled clamped by one draw and repeated by another, from one upload.
 *
 * <pre>{@code
 * static final CgTexture2D NOISE = CgTextures.of("mymod:textures/fx/noise.png");
 *
 * material.applyProperties(b -> b.sampler("_Mask", 0, NOISE, CgSampler.BILINEAR_CLAMP));    // a soft edge, clamped
 * layer.texture("_Scroll", NOISE, CgSampler.BILINEAR_WRAP);                                 // the same image, tiled
 * }</pre>
 *
 * <p>Your own, axis by axis:</p>
 *
 * <pre>{@code
 * CgSampler strip = CgSampler.builder().filter(CgSampler.Filter.TRILINEAR)
 *         .address(CgSampler.Address.WRAP, CgSampler.Address.CLAMP)   // tiled along u, clamped across v
 *         .anisotropy(8).build();
 * }</pre>
 *
 * <ul>
 *   <li>A binding with no sampler samples as the texture's {@link CgTextureSpec} says, as before: Unreal's "from the
 *       texture asset".</li>
 *   <li>On a texture with no mips, {@link Filter#BILINEAR} and {@link Filter#TRILINEAR} sample its base level: a
 *       sampler never makes a texture incomplete.</li>
 *   <li>Anisotropy is clamped to what the device has; 1 is none.</li>
 *   <li>A value: two equal samplers are one GL sampler object, made once for the session.</li>
 * </ul>
 */
public final class CgSampler {

    /** How texels are filtered: Unreal's {@code TextureFilter}. */
    public enum Filter {
        /** The nearest texel of the nearest level: pixel art. */
        NEAREST,
        /** Four texels blended, in the nearest level. */
        BILINEAR,
        /** Four texels blended in each of the two nearest levels, and the levels blended. */
        TRILINEAR
    }

    /** What a coordinate outside 0 to 1 reads: Unreal's {@code TextureAddress}. */
    public enum Address {
        /** The texture repeats. */
        WRAP,
        /** The edge texel stretches on. */
        CLAMP,
        /** The texture repeats, every other copy flipped. */
        MIRROR
    }

    public static final CgSampler NEAREST_WRAP = builder().filter(Filter.NEAREST).address(Address.WRAP).build();
    public static final CgSampler NEAREST_CLAMP = builder().filter(Filter.NEAREST).address(Address.CLAMP).build();
    public static final CgSampler BILINEAR_WRAP = builder().filter(Filter.BILINEAR).address(Address.WRAP).build();
    public static final CgSampler BILINEAR_CLAMP = builder().filter(Filter.BILINEAR).address(Address.CLAMP).build();
    public static final CgSampler TRILINEAR_WRAP = builder().filter(Filter.TRILINEAR).address(Address.WRAP).build();
    public static final CgSampler TRILINEAR_CLAMP = builder().filter(Filter.TRILINEAR).address(Address.CLAMP).build();
    public static final CgSampler BILINEAR_MIRROR = builder().filter(Filter.BILINEAR).address(Address.MIRROR).build();

    private final Filter filter;
    private final Address u, v, w;
    private final int anisotropy;

    private CgSampler(Filter filter, Address u, Address v, Address w, int anisotropy) {
        this.filter = filter;
        this.u = u;
        this.v = v;
        this.w = w;
        this.anisotropy = anisotropy;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Filter filter() {
        return filter;
    }

    /** What a coordinate outside 0 to 1 reads along u (s). */
    public Address u() {
        return u;
    }

    /** Along v (t). */
    public Address v() {
        return v;
    }

    /** Along w (r): a volume's depth, an array's is never wrapped. */
    public Address w() {
        return w;
    }

    /** The most texels sampled along a slanted footprint: 1 for none, clamped to the device's own most. */
    public int anisotropy() {
        return anisotropy;
    }

    /** A copy to change: another address, more anisotropy. */
    public Builder derive() {
        return builder().filter(filter).address(u, v, w).anisotropy(anisotropy);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof CgSampler)) return false;
        CgSampler s = (CgSampler) o;
        return filter == s.filter && u == s.u && v == s.v && w == s.w && anisotropy == s.anisotropy;
    }

    @Override
    public int hashCode() {
        return Objects.hash(filter, u, v, w, anisotropy);
    }

    @Override
    public String toString() {
        return "CgSampler{" + filter + " " + u + "/" + v + "/" + w + (anisotropy > 1 ? " x" + anisotropy : "") + "}";
    }

    /** Builds a {@link CgSampler}: bilinear and clamped on every axis unless set. */
    public static final class Builder {
        private Filter filter = Filter.BILINEAR;
        private Address u = Address.CLAMP, v = Address.CLAMP, w = Address.CLAMP;
        private int anisotropy = 1;

        private Builder() {}

        public Builder filter(Filter filter) {
            this.filter = Objects.requireNonNull(filter, "filter");
            return this;
        }

        /** The same address on every axis. */
        public Builder address(Address all) {
            return address(all, all, all);
        }

        /** Along u and v; w takes v's. */
        public Builder address(Address u, Address v) {
            return address(u, v, v);
        }

        public Builder address(Address u, Address v, Address w) {
            this.u = Objects.requireNonNull(u, "u");
            this.v = Objects.requireNonNull(v, "v");
            this.w = Objects.requireNonNull(w, "w");
            return this;
        }

        /** Up to {@code n} texels along a slanted footprint, 1 to 16; clamped to the device's own most when drawn. */
        public Builder anisotropy(int n) {
            if (n < 1 || n > 16) throw new IllegalArgumentException("anisotropy " + n + ", 1 to 16");
            this.anisotropy = n;
            return this;
        }

        public CgSampler build() {
            return new CgSampler(filter, u, v, w, anisotropy);
        }
    }
}
