package com.crystalgraphics.demo;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.render.world.CgWorldRenderer;
import com.crystalgraphics.vfx.CgVfxMomentListener;
import com.crystalgraphics.vfx.CgVfxSystem;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * The energy wave's blast over and over, for judging its screen flash and impact frame: a wave fires uncharged across
 * the view at a target on the ground and stops as it hits, so it bursts there; a wait after its impact frame (or its
 * hit, without one) everything clears and the next fires. The harness's {@code vfx-blast-flash} and a
 * {@link CgRenderDemo} scene.
 *
 * <pre>{@code
 * CgVfxBlastFlash blast = new CgVfxBlastFlash();
 * blast.prepare();                                 // and draw nothing until blast.warmed()
 *
 * // each frame: the target on the ground, the view's horizontal direction (the wave fires across it), host seconds
 * blast.submit(CgWorldRenderer.get(), x, y, z, forwardX, forwardZ, seconds);
 * hud.line(blast.hudLine());
 *
 * if (blast.press(key)) hudChanged();              // one of KEYS: a switch, from the next wave fired
 * }</pre>
 *
 * <ul>
 *   <li>{@link #clear} ends what plays and keeps what it built; the next submit starts a new wave.</li>
 *   <li>The time switch slows the scene's own clock, so moments and shakes slow with it.</li>
 * </ul>
 */
public final class CgVfxBlastFlash {

    /** The keys {@link #press} takes: flash, impact frame, time, a shorter and a longer wait. */
    public static final int[] KEYS = {CgKeyCodes.KEY_F, CgKeyCodes.KEY_I, CgKeyCodes.KEY_Y, CgKeyCodes.KEY_COMMA,
            CgKeyCodes.KEY_PERIOD};

    private enum Flash { DOUBLE, SINGLE, OFF }

    /** The flash before the double: one pulse, held, gone. */
    private static final CgKeyframes SINGLE = CgKeyframes.start(0f, 0f)
            .to(0.025f, 1f, CgEasings.OUT_QUAD)
            .to(0.08f, 0.85f, CgEasings.LINEAR)
            .to(0.5f, 0f, CgEasings.OUT_CUBIC)
            .build();
    private static final CgKeyframes NONE = CgKeyframes.start(0f, 0f).to(1f, 0f, CgEasings.LINEAR).build();
    private static final float[] SPEEDS = {1f, 0.25f, 0.1f};
    private static final float MIN_WAIT = 0.5f, MAX_WAIT = 5f;
    /** Blocks from the muzzle to the target, across the view, and the muzzle's height over the target. */
    private static final double SPAN = 12.0, MUZZLE_UP = 2.5;

    private final CgVfxSystem vfx = new CgVfxSystem();
    /** Waves fired and not yet stopped: each stops as it hits. */
    private final List<CgEnergyWave> flying = new ArrayList<>();
    private Flash flash = Flash.DOUBLE;
    private boolean impact = true;
    private int speed;
    private float wait = 2f;
    /** The scene's own clock, slowed by the time switch, and the host seconds it last advanced to. */
    private float clock;
    private double last = Double.NaN;
    /** The wave in play; whether it was fired with an impact frame, has hit, and has shown it; when the scene clears. */
    private CgEnergyWave current;
    private boolean currentFramed, currentHit, currentShown;
    private float clearAt = Float.NaN;
    private int fired;

    public void prepare() {
        vfx.prepare(CgEnergyWave.kamehameha());
    }

    public boolean warmed() {
        return vfx.warmed();
    }

    /** Plays the scene round the target {@code (x, y, z)}, firing across the view's horizontal {@code forward}. */
    public void submit(CgWorldRenderer world, double x, double y, double z, double forwardX, double forwardZ,
                       double seconds) {
        if (!Double.isNaN(last)) clock += (float) (seconds - last) * SPEEDS[speed];
        last = seconds;
        cycle(x, y, z, forwardX, forwardZ);
        for (Iterator<CgEnergyWave> it = flying.iterator(); it.hasNext(); ) {
            CgEnergyWave wave = it.next();
            if (!wave.impacting()) continue;
            if (wave == current) currentHit = true;
            wave.stop();
            it.remove();
        }
        vfx.update(clock);
        vfx.submit(world);
    }

    private void cycle(double x, double y, double z, double forwardX, double forwardZ) {
        if (current != null) {
            if (current.impactFrameShowing()) currentShown = true;
            else if (Float.isNaN(clearAt) && (currentFramed ? currentShown : currentHit)) clearAt = clock + wait;
            if (Float.isNaN(clearAt) || clock < clearAt) return;
            vfx.clear();
            flying.clear();
        }
        currentHit = currentShown = false;
        clearAt = Float.NaN;
        current = fire(x, y, z, forwardX, forwardZ);
    }

    private CgEnergyWave fire(double x, double y, double z, double forwardX, double forwardZ) {
        double length = Math.hypot(forwardX, forwardZ);
        double rightX = length > 1.0e-6 ? -forwardZ / length : 1.0, rightZ = length > 1.0e-6 ? forwardX / length : 0.0;
        CgEnergyWave wave = new CgEnergyWave(CgEnergyWave.kamehameha(), x - rightX * SPAN, y + MUZZLE_UP, z - rightZ * SPAN);
        wave.aim((float) rightX, -0.2f, (float) rightZ).target(x, y, z).fire();
        wave.ground(y);
        if (flash != Flash.DOUBLE) wave.set(CgEnergyWave.BLAST_FLASH, flash == Flash.SINGLE ? SINGLE : NONE);
        wave.set(CgEnergyWave.BLAST_IMPACT, impact ? 1f : 0f);
        currentFramed = impact;
        flying.add(vfx.play(wave));
        fired++;
        return wave;
    }

    /** Acts on one of {@link #KEYS}; false for any other key. */
    public boolean press(int key) {
        switch (key) {
            case CgKeyCodes.KEY_F -> flash = Flash.values()[(flash.ordinal() + 1) % Flash.values().length];
            case CgKeyCodes.KEY_I -> impact = !impact;
            case CgKeyCodes.KEY_Y -> speed = (speed + 1) % SPEEDS.length;
            case CgKeyCodes.KEY_COMMA -> wait = Math.max(MIN_WAIT, wait - 0.5f);
            case CgKeyCodes.KEY_PERIOD -> wait = Math.min(MAX_WAIT, wait + 0.5f);
            default -> {
                return false;
            }
        }
        return true;
    }

    public String hudLine() {
        return String.format(Locale.ROOT, "Flash [F]: %s   Impact frame [I]: %s   Time [Y]: %sx   Next after [, .]: %.1f s",
                flash.name().toLowerCase(Locale.ROOT), impact ? "on" : "off", SPEEDS[speed], wait);
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
        last = Double.NaN;
    }

    public void delete() {
        vfx.delete();
        flying.clear();
        current = null;
    }
}
