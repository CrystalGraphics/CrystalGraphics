package com.crystalgraphics.vfx.effect.air;

import com.crystalgraphics.settings.CgQuality;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxLayer;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxParam;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import org.joml.Matrix4f;

import java.util.List;

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
 *   <li>Its look draws {@link #SLOT_HAZE} on a sphere of {@link #RADIUS} ({@code shaders/vfx/air/haze.shader}); it draws
 *       nothing at the Low quality tier.</li>
 * </ul>
 */
public final class CgVfxHeatHaze extends CgVfxEffect {

    public static final CgVfxSchema SCHEMA = new CgVfxSchema();
    /** The haze's radius in blocks. */
    public static final CgVfxParam RADIUS = SCHEMA.scalar("radius", 2f);
    /** How strongly it bends, 1 the shader's own strength. */
    public static final CgVfxParam INTENSITY = SCHEMA.scalar("intensity", 1f);
    /** The slot its layers draw in, on a sphere of its radius. */
    public static final String SLOT_HAZE = "haze";
    /** Seconds it takes to fade in and to fade out. */
    public static final float FADE = 0.4f;

    private static final CgVfxLook STANDARD = CgVfxLook.builder(SCHEMA)
            .layer(CgVfxLayer.builder("crystalgraphics:shaders/vfx/air/haze.shader").slot(SLOT_HAZE)
                    .order(CgVfxLayer.ORDER_DISTORTION).from(CgQuality.MEDIUM).build())
            .build();

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
        List<CgVfxLayer> layers = look().layers();
        for (int i = 0; i < layers.size(); i++) {
            CgVfxLayer layer = layers.get(i);
            if (SLOT_HAZE.equals(layer.slot())) frame.mesh(this, layer, 0f, 0f, 0f, placed, radius, radius, intensity, 0f);
        }
    }
}
