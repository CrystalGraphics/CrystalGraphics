package com.crystalgraphics.vfx.look;

import com.crystalgraphics.api.shader.CgShaderBindings;
import com.crystalgraphics.settings.CgQuality;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * One draw of a {@link CgVfxLook}: a {@code .shader}, how wide it is drawn, the two colour parameters it reads, and
 * material properties of its own.
 *
 * <pre>{@code
 * CgVfxLayer shell = CgVfxLayer.builder("crystalgraphics:shaders/vfx/beam/body_shell.shader")
 *         .slot(CgVfxLayer.SLOT_BODY)                         // the part of the effect it draws
 *         .radius(1.0f)                                      // times the effect's own radius
 *         .colors(CgEnergyWave.SHELL, CgEnergyWave.SHELL_HOT) // CG_OBJECT_CUSTOM2, CG_OBJECT_CUSTOM3
 *         .order(CgVfxLayer.ORDER_SURFACE)
 *         .properties(b -> b.set1f("_Flow", 22f))
 *         .build();
 * }</pre>
 *
 * <ul>
 *   <li>Colours are read from the playing effect's values each draw, so two effects with different palettes still
 *       batch. Material properties are per layer: a different value needs a different layer.</li>
 *   <li>A tube layer's shader reads {@code fx_tube.glsl}'s contract: {@code _FxPath}, and {@code CG_OBJECT_CUSTOM0..3}
 *       as {@code CgVfxTube} writes them. A mesh layer's reads {@code CgVfxFrame.mesh}'s.</li>
 *   <li>A layer {@link Builder#from} a quality tier is not drawn below it; the frame skips it, so an effect never asks.</li>
 * </ul>
 */
public final class CgVfxLayer {

    /**
     * Where a layer draws within its effect (the world renderer's {@code order} within the effect's group, Niagara's
     * emitter sort order hint), one per kind of draw so each batches: 0 to 15, higher later. Every effect sorts as one
     * group at its origin in {@code CgSortLayer.EFFECTS}, so these order nothing outside it. Layers of one order sort
     * by distance alone, so two tube layers sharing one interleave chunk by chunk and never instance.
     *
     * <p>Layers that bend the scene ({@link #ORDER_DISTORTION}) write the world renderer's Distortion pass, applied once
     * after the transparent pass: it bends smoke, light and glow volumes, while {@link #ORDER_SURFACE},
     * {@link #ORDER_BANDS} and {@link #ORDER_CORE} draw after the apply ({@code Draw.afterDistortion}) and are never bent,
     * so a bright body is never smeared into the air round it (as a fire in Unreal draws after its distortion).</p>
     */
    public static final int ORDER_SMOKE = 1, ORDER_LIGHT = 2, ORDER_VOLUME = 3;
    /**
     * Layers that bend the scene behind them (heat haze, a shock front), through a Distortion pass: every haze of the
     * frame adds into one offset target, applied once. A haze also leaves its own hot body unbent.
     */
    public static final int ORDER_DISTORTION = 4;
    /** Sharp emissive layers, drawn after the distortion apply: never bent. */
    public static final int ORDER_SURFACE = 5, ORDER_BANDS = 6, ORDER_CORE = 7;
    /** The slot a layer draws in unless given another: an effect's main body. */
    public static final String SLOT_BODY = "body";

    final String shader;
    final String slot;
    final float radius;
    final CgVfxParam colorA, colorB;
    final float parameter;
    final int order;
    final boolean volume;
    final CgQuality from;
    final Consumer<CgShaderBindings> properties;

    private CgVfxLayer(Builder b) {
        this.shader = b.shader;
        this.slot = b.slot;
        this.radius = b.radius;
        this.colorA = b.colorA;
        this.colorB = b.colorB;
        this.parameter = b.parameter;
        this.order = b.order;
        this.volume = b.volume;
        this.from = b.from;
        this.properties = b.properties;
    }

    public static Builder builder(String shader) {
        return new Builder(shader);
    }

    public String shader() {
        return shader;
    }

    /** Which part of its effect it draws: an effect names its slots ({@link #SLOT_BODY}, a head, a muzzle). */
    public String slot() {
        return slot;
    }

    public float radius() {
        return radius;
    }

    /** The colour parameters its shader reads as {@code CG_OBJECT_CUSTOM2} and {@code CG_OBJECT_CUSTOM3}; either may be null. */
    public CgVfxParam colorA() {
        return colorA;
    }

    public CgVfxParam colorB() {
        return colorB;
    }

    /** The free number its shader reads as {@code CG_OBJECT_CUSTOM0.w}. */
    public float parameter() {
        return parameter;
    }

    /** Where it draws within its effect: an {@code ORDER_} constant. */
    public int order() {
        return order;
    }

    /** Whether each chunk of a tube draws on a sphere around it ({@link Builder#volume()}). */
    public boolean isVolume() {
        return volume;
    }

    /** The lowest quality tier that draws it. */
    public CgQuality from() {
        return from;
    }

    /** Material properties set once on its material, or null. */
    public Consumer<CgShaderBindings> properties() {
        return properties;
    }

    public static final class Builder {

        private final String shader;
        private String slot = SLOT_BODY;
        private float radius = 1f;
        private CgVfxParam colorA, colorB;
        private float parameter;
        private int order = ORDER_SURFACE;
        private boolean volume;
        private CgQuality from = CgQuality.LOW;
        private Consumer<CgShaderBindings> properties;

        private Builder(String shader) {
            this.shader = Objects.requireNonNull(shader, "shader");
        }

        /** Which part of the effect it draws, as the effect names them; {@link #SLOT_BODY} unless set. */
        public Builder slot(String slot) {
            this.slot = Objects.requireNonNull(slot, "slot");
            return this;
        }

        public Builder radius(float radius) {
            this.radius = radius;
            return this;
        }

        /** The colour parameters the shader reads as {@code CG_OBJECT_CUSTOM2} and {@code CG_OBJECT_CUSTOM3}; either may be null. */
        public Builder colors(CgVfxParam a, CgVfxParam b) {
            this.colorA = a;
            this.colorB = b;
            return this;
        }

        /** A free number the shader reads as {@code CG_OBJECT_CUSTOM0.w}. */
        public Builder parameter(float parameter) {
            this.parameter = parameter;
            return this;
        }

        /** Where it draws within its effect, 0 to 15: an {@code ORDER_} constant. */
        public Builder order(int order) {
            if (order < 0 || order > 15) throw new IllegalArgumentException("order " + order + " is outside 0..15");
            this.order = order;
            return this;
        }

        /**
         * Draws each chunk of a tube layer on a sphere around it rather than along it, for a shader that sums the light
         * of the chunk's own rings ({@code vfx/beam/body_glow.shader}): a sphere's far wall covers each pixel once, which a
         * bent tube's does not, so the chunks add up to the whole with nothing counted twice. The radius is then how far
         * the light reaches, in the rings' radii.
         */
        public Builder volume() {
            this.volume = true;
            return this;
        }

        /** Drawn only at {@code tier} and above: distortion, bloom and other detail a lower tier drops. */
        public Builder from(CgQuality tier) {
            this.from = Objects.requireNonNull(tier, "tier");
            return this;
        }

        /** Set once on the layer's material, after the shader's own defaults. */
        public Builder properties(Consumer<CgShaderBindings> properties) {
            this.properties = properties;
            return this;
        }

        public CgVfxLayer build() {
            return new CgVfxLayer(this);
        }
    }
}
