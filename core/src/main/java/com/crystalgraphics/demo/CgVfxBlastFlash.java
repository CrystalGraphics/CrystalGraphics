package com.crystalgraphics.demo;

import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxMomentListener;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The energy wave's blast over and over, for judging its screen flash and impact frame: a wave fires uncharged across
 * the view at a target on the ground and stops as it hits, so it bursts there; a wait after its impact frame (or its
 * hit, without one) everything clears and the next fires. Its switches are {@link CgVfxDemoControls}'. The harness's
 * {@code vfx-blast-flash} and a {@link CgRenderDemo} scene.
 *
 * <pre>{@code
 * CgVfxBlastFlash blast = new CgVfxBlastFlash();
 * blast.prepare();                                 // and draw nothing until blast.warmed()
 *
 * // each frame: the target on the ground, the view's horizontal direction (the wave fires across it), and the
 * // scene's seconds through the time switch
 * blast.submit(CgWorldRenderer.get(), x, y, z, forwardX, forwardZ, CgVfxDemoControls.get().time(hostSeconds));
 * }</pre>
 *
 * <p>{@link #clear} ends what plays and keeps what it built; the next submit starts a new wave.</p>
 */
public final class CgVfxBlastFlash {

    /** Blocks from the muzzle to the target, across the view, and the muzzle's height over the target. */
    private static final double SPAN = 12.0, MUZZLE_UP = 2.5;

    private final CgVfxSystem vfx = new CgVfxSystem();
    /** Waves fired and not yet stopped: each stops as it hits. */
    private final List<CgEnergyWave> flying = new ArrayList<>();
    /** The wave in play, and when the scene clears: NaN until it bursts. */
    private CgEnergyWave current;
    private float clock, clearAt = Float.NaN;
    private int fired;

    public void prepare() {
        vfx.prepare(CgEnergyWave.kamehameha());
    }

    public boolean warmed() {
        return vfx.warmed();
    }

    /** Plays the scene round the target {@code (x, y, z)} at {@code seconds}, firing across the view's horizontal {@code forward}. */
    public void submit(CgWorldRenderer world, double x, double y, double z, double forwardX, double forwardZ,
                       float seconds) {
        clock = seconds;
        cycle(x, y, z, forwardX, forwardZ);
        for (Iterator<CgEnergyWave> it = flying.iterator(); it.hasNext(); ) {
            CgEnergyWave wave = it.next();
            if (!wave.impacting()) continue;
            wave.stop();
            it.remove();
        }
        vfx.update(clock);
        vfx.submit(world);
    }

    private void cycle(double x, double y, double z, double forwardX, double forwardZ) {
        if (current != null) {
            // From the burst, after the impact frame when it has one, so a frame switched off midway waits on nothing.
            if (Float.isNaN(clearAt) && current.burst()) clearAt = clock + CgVfxDemoControls.get().waitSeconds();
            if (Float.isNaN(clearAt) || clock < clearAt) return;
            vfx.clear();
            flying.clear();
        }
        clearAt = Float.NaN;
        current = fire(x, y, z, forwardX, forwardZ);
    }

    private CgEnergyWave fire(double x, double y, double z, double forwardX, double forwardZ) {
        CgVfxDemoControls controls = CgVfxDemoControls.get();
        double length = Math.hypot(forwardX, forwardZ);
        double rightX = length > 1.0e-6 ? -forwardZ / length : 1.0, rightZ = length > 1.0e-6 ? forwardX / length : 0.0;
        CgEnergyWave wave = new CgEnergyWave(CgEnergyWave.kamehameha(), x - rightX * SPAN, y + MUZZLE_UP, z - rightZ * SPAN);
        wave.aim((float) rightX, -0.2f, (float) rightZ).target(x, y, z).fire();
        wave.ground(y);
        controls.apply(wave);
        flying.add(vfx.play(wave));
        fired++;
        return wave;
    }

    /** Hears every moment its waves cross, as {@link CgVfxSystem#onMoment}. */
    public void onMoment(CgVfxMomentListener listener) {
        vfx.onMoment(listener);
    }

    /** The wave in play, or null before the first. */
    public CgEnergyWave wave() {
        return current;
    }

    /** Waves fired so far, the one in play included. */
    public int fired() {
        return fired;
    }

    public void clear() {
        vfx.clear();
        flying.clear();
        current = null;
    }

    public void delete() {
        vfx.delete();
        flying.clear();
        current = null;
    }
}
