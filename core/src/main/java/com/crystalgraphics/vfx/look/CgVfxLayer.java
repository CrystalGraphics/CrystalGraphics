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
 *         .priority(CgVfxLayer.PRIORITY_SURFACE)
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
     * World-stage priorities the vfx layers take, one per kind of draw so each batches: a share of the field every world
     * consumer draws from (0 to 15), kept in this one table. Transparent draws of one priority sort by distance alone, so
     * two tube layers sharing one interleave chunk by chunk and never instance.
     */
    public static final int PRIORITY_LIGHT = 2, PRIORITY_VOLUME = 3, PRIORITY_SURFACE = 4, PRIORITY_BANDS = 5, PRIORITY_CORE = 6;
    /** Alpha-blended layers, before every additive one: they hide the scene behind them, and the energy's light falls over them. */
    public static final int PRIORITY_SMOKE = 1;
    /** The slot a layer draws in unless given another: an effect's main body. */
    public static final String SLOT_BODY = "body";

    final String shader;
    final String slot;
    final float radius;
    final CgVfxParam colorA, colorB;
    final float parameter;
    final int priority;
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
        this.priority = b.priority;
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

    public int priority() {
        return priority;
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
        private int priority = PRIORITY_SURFACE;
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

        public Builder priority(int priority) {
            if (priority < 0 || priority > 15) throw new IllegalArgumentException("priority " + priority + " is outside 0..15");
            this.priority = priority;
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
