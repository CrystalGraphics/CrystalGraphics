package com.crystalgraphics.vfx.effect;

import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.CgVfxFrame;
import com.crystalgraphics.vfx.look.CgVfxLook;
import com.crystalgraphics.vfx.look.CgVfxSchema;
import com.crystalgraphics.vfx.particle.CgVfxEmitter;
import com.crystalgraphics.vfx.particle.CgVfxEmitterInstance;

import java.util.ArrayList;
import java.util.List;

/**
 * Every emitter of a look, started once at the origin; dies when they have all finished. The effect for a look that is
 * particles alone and needs no code: an explosion kit's debris, a puff, a hit spark.
 *
 * <pre>{@code
 * CgVfxLook puff = CgVfxLook.builder(CgVfxBurst.SCHEMA).with(PUFF_KIT).build();
 * vfx.play(new CgVfxBurst(puff, x, y, z)).ground(floorY);
 * }</pre>
 *
 * <ul>
 *   <li>Set {@link #ground} before the first update, or the first tick's particles miss it.</li>
 *   <li>An emitter with an open rate never finishes, so neither does the burst until it is stopped.</li>
 * </ul>
 */
public class CgVfxBurst extends CgVfxEffect<CgVfxBurst> {

    /** A burst reads no parameter of its own: its kits' layers read theirs. */
    public static final CgVfxSchema SCHEMA = CgVfxSchema.of("crystalgraphics:burst");

    private final List<CgVfxEmitterInstance> emitters = new ArrayList<>();
    private boolean grounded;

    public CgVfxBurst(CgVfxLook look, double x, double y, double z) {
        super(look, x, y, z);
        List<CgVfxEmitter> kinds = look.emitters();
        for (int i = 0; i < kinds.size(); i++) {
            CgVfxEmitterInstance emitter = new CgVfxEmitterInstance(kinds.get(i), seed + i * 0.137f);
            emitter.start(0f, 0f, 0f);
            emitters.add(emitter);
        }
    }

    @Override
    public void stop() {
        for (int i = 0; i < emitters.size(); i++) emitters.get(i).stop();
        super.stop();
    }

    @Override
    protected void tick(float dt) {
        if (!grounded) {
            grounded = true;
            for (int i = 0; i < emitters.size(); i++) emitters.get(i).ground(groundHeight());
        }
        boolean done = true;
        for (int i = 0; i < emitters.size(); i++) {
            tick(emitters.get(i), dt);
            done &= emitters.get(i).finished();
        }
        if (done && age > 1f) die();
    }

    @Override
    protected void submit(CgVfxFrame frame) {
        for (int i = 0; i < emitters.size(); i++) frame.particles(this, emitters.get(i));
    }
}
