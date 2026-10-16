package com.crystalgraphics.vfx.fluid;

import com.crystalgraphics.api.texture.CgTextureSpec;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.gl.texture.CgTexture2D;
import com.crystalgraphics.noise.CgNoiseBake;
import com.crystalgraphics.platform.gl.CgGL;

import java.util.Arrays;

/**
 * What a fluid looks like at each temperature: a colour and an opacity, NVIDIA Flow's colour map (BSD-3-Clause,
 * {@code flow/include/nvflowext/NvFlowExt.h} {@code NvFlowRayMarchColormapParams}, built as
 * {@code RayMarchUpdateColormapCS.hlsl} builds it). The fluid layer looks it up by temperature over its
 * {@code _ColormapMin} to {@code _ColormapMax}; its colour is the light the cell sends toward the eye where fully lit, past
 * 1 where it glows, and its alpha scales the smoke's opacity.
 *
 * <pre>{@code
 * layer.texture("_Colormap", CgVfxColormap.FIRE::texture);         // Flow's fire, made at the layer's first draw
 * CgVfxKit kit = CgVfxFluid.kit("mymod:fluid/fire", CgVfxColormap.FIRE.withColorScale(1f));   // dimmer
 *
 * static final CgVfxColormap EMBERS = CgVfxColormap.builder()      // a map of your own: points by temperature
 *         .at(0f, 0.02f, 0.02f, 0.02f, 0.5f)                         // linear rgb, then opacity
 *         .at(0.5f, 1.0f, 0.2f, 0.02f, 0.8f)
 *         .at(1f, 0xFFFFC060, 20f, 0.7f)                             // an 0xAARRGGBB colour times 20, opacity 0.7
 *         .colorScale(2.5f).build();
 * }</pre>
 *
 * <ul>
 *   <li>Between two points it is linear; past the last it holds Flow's white, before the first its black.</li>
 *   <li>{@link #texture()} makes its texture once, at its first call, on any thread; a server never calls it.</li>
 *   <li>{@link #resolution} texels across, Flow's 32 unless given.</li>
 * </ul>
 */
public final class CgVfxColormap {

    /** Flow's {@code NvFlowRayMarchColormapParams_default_xPoints}. */
    private static final float[] FLOW_X = {0f, 0.05f, 0.15f, 0.6f, 0.85f, 1f};
    private static final CgTextureSpec SPEC = CgTextureSpec.builder().type(CgTextureType.RGBA16F)
            .minFilter(CgGL.GL_LINEAR).magFilter(CgGL.GL_LINEAR)
            .wrapS(CgGL.GL_CLAMP_TO_EDGE).wrapT(CgGL.GL_CLAMP_TO_EDGE).build();

    /** Flow's {@code rgbaPoints_smoke} at its default colour scale, 2.5: grey smoke, its opacity rising off zero. */
    public static final CgVfxColormap SMOKE = flow(new float[][]{
            {0.9f, 0.9f, 0.9f, 0.004902f}, {0.9f, 0.9f, 0.9f, 0.904902f}, {0.9f, 0.9f, 0.9f, 0.904902f},
            {0.9f, 0.9f, 0.9f, 0.904902f}, {0.9f, 0.9f, 0.9f, 0.904902f}, {0.9f, 0.9f, 0.9f, 0.904902f}});
    /**
     * Flow's {@code rgbaPoints_fire} at its default colour scale: dark soot while cool, then orange, then white-hot far
     * past 1, which blooms.
     */
    public static final CgVfxColormap FIRE = flow(new float[][]{
            {0.015400f, 0.017700f, 0.015400f, 0.004902f}, {0.035750f, 0.035750f, 0.035750f, 0.504902f},
            {0.035750f, 0.035750f, 0.035750f, 0.504902f}, {1f, 0.1594125f, 0.0135315f, 0.8f},
            {13.534992f, 2.986956f, 0.125991f, 0.8f}, {78.08f, 39.04f, 6.1f, 0.7f}});

    private final float[] x;
    private final float[][] rgba;
    private final float colorScale;
    private final int resolution;
    private CgTexture2D texture;

    private CgVfxColormap(float[] x, float[][] rgba, float colorScale, int resolution) {
        this.x = x;
        this.rgba = rgba;
        this.colorScale = colorScale;
        this.resolution = resolution;
    }

    private static CgVfxColormap flow(float[][] rgba) {
        Builder b = builder();
        for (int i = 0; i < FLOW_X.length; i++) b.at(FLOW_X[i], rgba[i][0], rgba[i][1], rgba[i][2], rgba[i][3]);
        return b.colorScale(2.5f).build();
    }

    /** This map with its colours times {@code scale} instead: Flow's editor draws its fire at 1. */
    public CgVfxColormap withColorScale(float scale) {
        return new CgVfxColormap(x, rgba, scale, resolution);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Its colour and opacity at {@code u}, 0 to 1, into {@code out}: what texel {@code u} holds. */
    public void sample(float u, float[] out) {
        int low = -1, high = -1;
        for (int i = 0; i < x.length; i++) {
            if (x[i] <= u && (low < 0 || x[i] >= x[low])) low = i;
            if (x[i] >= u && (high < 0 || x[i] <= x[high])) high = i;
        }
        float lowX = low < 0 ? 0f : x[low], highX = high < 0 ? 0f : x[high];
        float t = highX == lowX ? 0f : (u - lowX) / (highX - lowX);
        for (int c = 0; c < 4; c++) {
            float a = low < 0 ? 0f : rgba[low][c], b = high < 0 ? 1f : rgba[high][c];
            out[c] = (1f - t) * a + t * b;
            if (c < 3) out[c] *= colorScale;
        }
    }

    /** Its texture, {@link #resolution} texels by 1, RGBA16F, clamped: made at the first call. */
    public synchronized CgTexture2D texture() {
        if (texture != null) return texture;
        float[] texels = new float[resolution * 4], texel = new float[4];
        for (int i = 0; i < resolution; i++) {
            sample((i + 0.5f) / resolution, texel);
            System.arraycopy(texel, 0, texels, i * 4, 4);
        }
        texture = CgTexture2D.createFromPixels(resolution, 1, CgNoiseBake.toHalf(texels), SPEC);
        return texture;
    }

    public int resolution() {
        return resolution;
    }

    /** Points by temperature, 0 to 1 over the layer's range. */
    public static final class Builder {
        private float[] x = new float[0];
        private float[][] rgba = new float[0][];
        private float colorScale = 1f;
        private int resolution = 32;

        private Builder() {
        }

        /** A point: its colour linear, past 1 where it glows, and its opacity. */
        public Builder at(float u, float r, float g, float b, float opacity) {
            int n = x.length;
            x = Arrays.copyOf(x, n + 1);
            rgba = Arrays.copyOf(rgba, n + 1);
            x[n] = u;
            rgba[n] = new float[]{r, g, b, opacity};
            return this;
        }

        /** A point: an {@code 0xAARRGGBB} colour as shaders author colour (sRGB), times {@code intensity}, and its opacity. */
        public Builder at(float u, int argb, float intensity, float opacity) {
            return at(u, linear(argb >> 16 & 0xFF) * intensity, linear(argb >> 8 & 0xFF) * intensity,
                    linear(argb & 0xFF) * intensity, opacity);
        }

        /** Every point's colour times this: Flow's {@code colorScale}. */
        public Builder colorScale(float scale) {
            colorScale = scale;
            return this;
        }

        public Builder resolution(int texels) {
            resolution = Math.max(2, texels);
            return this;
        }

        public CgVfxColormap build() {
            if (x.length == 0) throw new IllegalStateException("a colour map needs a point");
            return new CgVfxColormap(x.clone(), rgba.clone(), colorScale, resolution);
        }

        private static float linear(int channel) {
            float c = channel / 255f;
            return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
    }
}
