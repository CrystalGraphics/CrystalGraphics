package com.crystalgraphics.render.scene.renderer.feature.light;

import com.crystalgraphics.gpu.resource.CgFrameRing;

import java.util.Arrays;

/**
 * This frame's lights from the CPU, as the scene renderer lists them: Unreal's simple lights gathered for the frame
 * (particles', a demo's), each a point that may be a spot or a tube. A scene's light components write into it each
 * frame, every {@code CgDynamicLights} source collects into it, and the engine's VFX adds its effects' lights.
 *
 * <pre>{@code
 * scene.dynamicLights((view, out) -> {
 *     for (Ember e : embers) out.point(e.x, e.y, e.z, 2f, 0xFFFF8030, 6f).source(0.05f).castsShadow(false);
 * });
 * }</pre>
 *
 * <pre>{@code
 * out.point(x, y + 6, z, 12f, 0xFFFFF0D0, 300f).spot(0f, -1f, 0f, 20f, 35f);   // full inside 20 degrees, none past 35
 * out.point(midX, midY, midZ, 24f, 0xFF60A0FF, 40f).tube(dirX, dirY, dirZ, 30f); // from a line 30 blocks long
 * }</pre>
 *
 * <ul>
 *   <li>A light lives one frame: a source collects its lights again each frame they shine.</li>
 *   <li>{@code point}'s colour is sRGB, as authored colours are; {@code pointLinear}'s is linear, premultiplied by its
 *       intensity.</li>
 *   <li>Each write answers a cursor on the light it wrote; the cursor is the collector's own, good until the next
 *       write.</li>
 *   <li>Exponent 0, the default, is inverse-squared falloff in blocks faded to nothing at the radius; any other is
 *       Unreal's {@code (1 - d²/r²)^exponent}.</li>
 *   <li>Render thread, before the frame's first world firing.</li>
 * </ul>
 */
public final class CgLightCollector {

    private static final float[] SRGB = new float[256];

    static {
        for (int i = 0; i < 256; i++) {
            float c = i / 255f;
            SRGB[i] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
        }
    }

    private double[] at = new double[3 * 64];
    private float[] light = new float[CgSceneLights.FLOATS * 64];
    private int count;
    private long frame = -1;
    private final Light cursor = new Light();

    CgLightCollector() {
    }

    /** {@code srgb} 0 to 255 as linear light. */
    public static float linear(int srgb) {
        return SRGB[srgb & 0xFF];
    }

    /**
     * A light at absolute {@code (x, y, z)} reaching {@code radius} blocks, its sRGB colour {@code argb} (alpha scales
     * it) times {@code intensity}: a point, inverse-squared, casting shadows, until its cursor says otherwise.
     */
    public Light point(double x, double y, double z, float radius, int argb, float intensity) {
        float scale = (argb >>> 24) / 255f * intensity;
        return pointLinear(x, y, z, radius, SRGB[argb >> 16 & 0xFF] * scale, SRGB[argb >> 8 & 0xFF] * scale,
                SRGB[argb & 0xFF] * scale);
    }

    /** {@link #point} of a linear colour, already times its intensity. */
    public Light pointLinear(double x, double y, double z, float radius, float r, float g, float b) {
        long now = CgFrameRing.frame();
        if (now != frame) {
            frame = now;
            count = 0;
        }
        if (count * 3 == at.length) {
            at = Arrays.copyOf(at, at.length * 2);
            light = Arrays.copyOf(light, light.length * 2);
        }
        int p = count * 3, l = count * CgSceneLights.FLOATS;
        at[p] = x;
        at[p + 1] = y;
        at[p + 2] = z;
        light[l + 3] = radius;
        light[l + 4] = r;
        light[l + 5] = g;
        light[l + 6] = b;
        light[l + 7] = 0f;
        light[l + 8] = light[l + 9] = light[l + 10] = 0f;
        light[l + 11] = -2f;
        light[l + 12] = light[l + 13] = light[l + 14] = light[l + 15] = 0f;
        // What Minecraft lit the scene round it by, which the apply divides out to find the albedo
        int lit = CgSceneLight.at(x, y, z);
        light[l + 16] = CgSceneLight.block(lit);
        light[l + 17] = CgSceneLight.sky(lit);
        light[l + 18] = 0f;
        light[l + 19] = 1f;
        light[l + 20] = 1f;
        light[l + 21] = light[l + 23] = 0f;
        light[l + 22] = 1f;
        count++;
        cursor.at = l;
        return cursor;
    }

    /** Lights written this ring frame. */
    int count() {
        return CgFrameRing.frame() == frame ? count : 0;
    }

    /** The frame's stamp it was last written in, reset where a new frame's first write finds it. */
    long frame() {
        return frame;
    }

    /** Opens this ring frame for writing now, though nothing is written yet. */
    void open() {
        long now = CgFrameRing.frame();
        if (now != frame) {
            frame = now;
            count = 0;
        }
    }

    double[] positions() {
        return at;
    }

    float[] rows() {
        return light;
    }

    /** The light just written, until the next write: its shape and how it is lit. */
    public final class Light {
        private int at;

        private Light() {
        }

        /** Limited to a cone along {@code (dx, dy, dz)}: full inside {@code inner} degrees, none past {@code outer}. */
        public Light spot(float dx, float dy, float dz, float innerDegrees, float outerDegrees) {
            float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (length == 0f) throw new IllegalArgumentException("a spot points somewhere: (0, 0, 0)");
            double inner = Math.toRadians(Math.max(0f, Math.min(89f, innerDegrees)));
            double outer = Math.max(inner + 0.001, Math.min(Math.toRadians(outerDegrees), Math.toRadians(89) + 0.001));
            double cosOuter = Math.cos(outer);
            light[at + 8] = dx / length;
            light[at + 9] = dy / length;
            light[at + 10] = dz / length;
            light[at + 11] = (float) cosOuter;
            light[at + 15] = (float) (1.0 / (Math.cos(inner) - cosOuter));
            return this;
        }

        /** Coming from a line {@code length} blocks long along {@code (dx, dy, dz)}, centred on it: Unreal's SourceLength. */
        public Light tube(float dx, float dy, float dz, float length) {
            float norm = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (norm == 0f) throw new IllegalArgumentException("a tube runs along something: (0, 0, 0)");
            float s = Math.max(length, 0f) / norm;
            light[at + 12] = dx * s;
            light[at + 13] = dy * s;
            light[at + 14] = dz * s;
            return this;
        }

        /** Its source's radius, Unreal's SourceRadius: a light that touches surfaces gives one, or it only grazes them. */
        public Light source(float radius) {
            light[at + 18] = Math.max(radius, 0f);
            return this;
        }

        /** Unreal's {@code LightFalloffExponent}; 0, the default, inverse-squared. */
        public Light exponent(float exponent) {
            light[at + 7] = Math.max(exponent, 0f);
            return this;
        }

        /** Whether blocks, entities and our meshes withhold it (Unreal's bCastShadows): on unless told. */
        public Light castsShadow(boolean on) {
            light[at + 19] = on ? 1f : 0f;
            return this;
        }

        /** Whether MegaLights samples it rather than shading it exactly; on unless told. */
        public Light sampled(boolean on) {
            light[at + 22] = on ? 1f : 0f;
            return this;
        }

        /** How strongly it lights the air (Unreal's VolumetricScatteringIntensity): 1 unless told, 0 for none. */
        public Light volumetric(float intensity) {
            light[at + 20] = Math.max(intensity, 0f);
            return this;
        }
    }
}
