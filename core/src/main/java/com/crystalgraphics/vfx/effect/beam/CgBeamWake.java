package com.crystalgraphics.vfx.effect.beam;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.shape.primitive.CgRing;
import com.crystalgraphics.shape.primitive.CgShell;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxElement;
import com.crystalgraphics.vfx.look.CgVfxKit;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;
import com.crystalgraphics.vfx.particle.CgVfxModule;
import com.crystalgraphics.vfx.particle.spawn.CgVfxEmitterState;
import com.crystalgraphics.vfx.path.CgVfxPath;

/**
 * Dust a beam tears off the ground where it runs low, from its release until it stops: thrown out flat from under it,
 * braked by the air, rolling into a low skirt along its track and settling. Its rate is per block of the body within
 * {@link #REACH} of its radius over the ground, and its source moves each tick to a spot under that stretch.
 *
 * <pre>{@code
 * private final CgBeamWake wake = new CgBeamWake(this, CgEnergyWave.WAKE);
 * wake.tick(dt, body.path(), firing);     // every tick; its dust is the system's to draw
 * }</pre>
 *
 * <ul>
 *   <li>It starts only where the effect has ground ({@code ground(y)}, or the world's), and never on a server.</li>
 *   <li>Its look needs a kit holding {@link #EMITTER} and a layer on it; {@code CgEnergyWave.WAKE} is one.</li>
 * </ul>
 */
public final class CgBeamWake extends CgVfxElement {

    public static final CgVfxEmitter EMITTER = CgVfxEmitter.builder("crystalgraphics:wave/wake")
            .state(CgVfxEmitterState.ONCE)
            .renderer(CgVfxEmitter.Renderer.QUADS)
            .optional().capacity(1200).rate(36f).shape(CgRing.of(0.4f, 1.4f, 0.28f))
            .at(0f, 0.35f, 0f).speed(4f, 11f).life(0.9f, 1.8f).size(0.3f, 0.7f, 1.4f)
            .module(new CgVfxModule.Gravity(1.5f))
            .module(new CgVfxModule.Drag(1.3f, 0.05f))
            .module(new CgVfxModule.Turbulence(1.2f, 0.15f, 0.4f))
            .module(new CgVfxModule.Wind(0.4f))
            .module(new CgVfxModule.Ground(0f, 0.02f, 0f, 0.3f))
            .size(CgKeyframes.start(0f, 0.4f).to(0.3f, 1f, CgEasings.OUT_CUBIC).to(1f, 1.8f, CgEasings.OUT_QUAD).build())
            .opacity(CgKeyframes.start(0f, 0f).to(0.06f, 1f, CgEasings.OUT_QUAD).to(1f, 0f, CgEasings.IN_OUT_SINE).build())
            .build();
    /** How close to the ground the body tears it up, in its radii, and the most blocks of it that count. */
    public static final float REACH = 3f, BLOCKS = 16f;

    private final CgVfxKit kit;
    private CgVfxEmitterInstance wake;
    private int spots;

    /** A wake played from {@code kit}, which holds {@link #EMITTER} and scales it. */
    public CgBeamWake(CgVfxEffect<?> effect, CgVfxKit kit) {
        super(effect);
        this.kit = kit;
    }

    /** Spawns under the stretch of {@code path} running low while {@code tearing}; stopped after, its dust settling. */
    public void tick(float dt, CgVfxPath path, boolean tearing) {
        if (authoritative()) return;
        float ground = groundHeight();
        if (wake == null) {
            if (Float.isNaN(ground) || !tearing) return;
            wake = instance(EMITTER, kit, 0.731f);
            if (wake == null) return;
            wake.start(0f, ground, 0f);
            wake.ground(ground);
        }
        if (!tearing) {
            wake.stop();
        } else {
            float total = 0f;
            for (int i = 0; i < path.count(); i++) total += weight(path, i, ground);
            wake.rateScale(Math.min(total * get(CgBeamBody.RING_SPACING), BLOCKS));
            float pick = total * CgVfxEmitterInstance.rand(Float.floatToIntBits(seed()), spots++, 2);
            for (int i = 0; i < path.count() && total > 0f; i++) {
                pick -= weight(path, i, ground);
                if (pick > 0f) continue;
                wake.moveTo(path.x(i), ground, path.z(i));
                break;
            }
        }
        tick(wake, dt);
    }

    /** How much ring {@code i} tears up the ground: 1 within its radius of it, nothing past {@link #REACH} radii. */
    private static float weight(CgVfxPath path, int i, float ground) {
        float r = path.radius(i);
        return 1f - smooth(r, REACH * r, path.y(i) - ground);
    }
}
