package com.crystalgraphics.vfx.effect.air;

import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.look.CgVfxSlot;
import org.joml.Matrix4f;

/**
 * Heat haze over a point, for as long as it plays: the air above something hot shimmering, with nothing else drawn. For
 * a fire, a lava pool, a cooling crater, and for looking at the haze on its own.
 *
 * <pre>{@code
 * CgVfxHeatHaze haze = vfx.play(new CgVfxHeatHaze(CgVfxHeatHaze.standard(), x, y, z));
 * haze.set(CgVfxHeatHaze.RADIUS, 3f);     // blocks
 * haze.stop();                             // fades out, then ends
 * }</pre>
 *
 * <ul>
 *   <li>It fades in and out over {@link #FADE} seconds, so starting or stopping it never pops.</li>
 *   <li>Its look draws {@link #HAZE} on a sphere of {@link #RADIUS} ({@code shaders/vfx/air/haze.shader}); it draws
 *       nothing at the Low quality tier.</li>
 * </ul>
 */
public final class CgVfxHeatHaze extends CgVfxEffect<CgVfxHeatHaze> {

    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:heat_haze");
    /** The haze's radius. */
    public static final CgVfxParam.Scalar RADIUS = SCHEMA.length("radius", 2f);
    /** How strongly it bends, 1 the shader's own strength. */
    public static final CgVfxParam.Scalar INTENSITY = SCHEMA.scalar("intensity", 1f);
    /**
     * The haze: a sphere of its radius, scaled by it ({@code CUSTOM1} the radius twice, the intensity faded in and out,
     * and 0).
     */
    public static final CgVfxSlot HAZE = CgVfxSlot.mesh("crystalgraphics:heat_haze/haze");
    /** Seconds it takes to fade in and to fade out. */
    public static final float FADE = 0.4f;

    /** The haze shader on {@link #HAZE}. */
    public static final CgVfxKit KIT = CgVfxKit.of("crystalgraphics:heat_haze")
            .layer(CgVfxLayer.of("crystalgraphics:shaders/vfx/air/haze.shader").on(HAZE)
                    .role(CgVfxLayer.Role.DISTORTION).from(CgQuality.MEDIUM).build())
            .build();

    private static final CgVfxLook STANDARD = CgVfxLook.builder(SCHEMA).with(KIT).build();

    private final Matrix4f placed = new Matrix4f();
    private float stopAge = Float.NaN;

    public CgVfxHeatHaze(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
    }

    /** One haze layer on a sphere. */
    public static CgVfxLook standard() {
        return STANDARD;
    }

    @Override
    public void stop() {
        if (state() == State.PLAYING) stopAge = age;
        super.stop();
    }

    @Override
    protected void tick(float dt) {
        if (!Float.isNaN(stopAge) && age > stopAge + FADE) die();
    }

    @Override
    protected void submit(CgVfxFrame frame) {
        float fade = Math.min(age / FADE, 1f);
        if (!Float.isNaN(stopAge)) fade *= Math.max(0f, 1f - (age - stopAge) / FADE);
        float radius = get(RADIUS), intensity = get(INTENSITY) * fade;
        if (intensity <= 0f) return;
        placed.identity().scale(radius);
        frame.mesh(this, HAZE, 0f, 0f, 0f, placed, radius, radius, intensity, 0f);
    }
}
