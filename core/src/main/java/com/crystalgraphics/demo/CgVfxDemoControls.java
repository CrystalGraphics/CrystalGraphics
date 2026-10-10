package com.crystalgraphics.demo;

import com.crystalgraphics.easing.CgEasings;
import com.crystalgraphics.easing.CgKeyframes;
import com.crystalgraphics.platform.input.CgKeyCodes;
import com.crystalgraphics.vfx.CgVfxEffect;
import com.crystalgraphics.vfx.effect.beam.CgEnergyWave;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The VFX showcases' shared switches, one set for every scene so a value stays as scenes change: the blast's flash,
 * its impact frame, the billows, the time scale and the wait before the next wave. The harness's VFX scenes and
 * {@link CgRenderDemo}'s scenes all read {@link #get()}.
 *
 * <pre>{@code
 * CgVfxDemoControls controls = CgVfxDemoControls.get();
 * if (controls.press(key, shift)) hudChanged();           // one of KEYS
 *
 * // each frame: the host's seconds through the time switch, into the scene
 * float t = controls.time(hostSeconds);
 * scene.submit(world, x, y, z, t);
 *
 * // each wave a scene fires
 * CgEnergyWave wave = vfx.play(new CgEnergyWave(CgEnergyWave.kamehameha(), x, y, z));
 * controls.apply(wave);
 *
 * hud.line(controls.hudLines(true));                      // false: a scene with no waves shows the time alone
 * }</pre>
 *
 * <ul>
 *   <li>{@link #time} keeps one clock: call it once a frame, from whatever plays the scene.</li>
 *   <li>Flash, impact frame and billows reach every wave {@link #apply} set that still plays, at the press. An impact
 *       frame switched on reaches a blast that has not yet burst; switched off, it hides one playing, whose blast
 *       still holds for it.</li>
 *   <li>Press on the thread that updates the scenes' systems, between their updates.</li>
 * </ul>
 */
public final class CgVfxDemoControls {

    /** The keys {@link #press} takes: flash, impact frame, billows, time, a shorter and a longer wait. */
    public static final int[] KEYS = {CgKeyCodes.KEY_F, CgKeyCodes.KEY_I, CgKeyCodes.KEY_B, CgKeyCodes.KEY_Y,
            CgKeyCodes.KEY_COMMA, CgKeyCodes.KEY_PERIOD};

    private enum Flash { DOUBLE, SINGLE, OFF }

    /** The flash before the double: one pulse, held, gone. */
    private static final CgKeyframes SINGLE = CgKeyframes.start(0f, 0f)
            .to(0.025f, 1f, CgEasings.OUT_QUAD)
            .to(0.08f, 0.85f, CgEasings.LINEAR)
            .to(0.5f, 0f, CgEasings.OUT_CUBIC)
            .build();
    private static final CgKeyframes NONE = CgKeyframes.start(0f, 0f).to(1f, 0f, CgEasings.LINEAR).build();
    private static final float[] SPEEDS = {0f, 0.01f, 0.1f, 0.25f, 0.5f, 1f, 2f};
    private static final int REAL_TIME = 5;
    private static final float MIN_WAIT = 0.5f, MAX_WAIT = 8f;

    private static final CgVfxDemoControls INSTANCE = new CgVfxDemoControls();

    private Flash flash = Flash.DOUBLE;
    private boolean impact = true, billows = true;
    private int speed = REAL_TIME;
    private float wait = 4f;
    /** The clock slowed by the time switch, and the host seconds it last advanced to. */
    private float clock;
    private double last = Double.NaN;
    /** Each wave {@link #apply} set and its own flash, the double; pruned once dead. */
    private record Live(CgEnergyWave wave, CgKeyframes flash) {
    }

    private final List<Live> live = new ArrayList<>();

    private CgVfxDemoControls() {
    }

    public static CgVfxDemoControls get() {
        return INSTANCE;
    }

    /** Acts on one of {@link #KEYS}, Shift reversing the time switch; false for any other key. */
    public boolean press(int key, boolean shift) {
        switch (key) {
            case CgKeyCodes.KEY_F -> {
                flash = Flash.values()[(flash.ordinal() + 1) % Flash.values().length];
                reapply();
            }
            case CgKeyCodes.KEY_I -> {
                impact = !impact;
                reapply();
            }
            case CgKeyCodes.KEY_B -> {
                billows = !billows;
                reapply();
            }
            case CgKeyCodes.KEY_Y -> speed = shift ? Math.max(0, speed - 1) : Math.min(SPEEDS.length - 1, speed + 1);
            case CgKeyCodes.KEY_COMMA -> wait = Math.max(MIN_WAIT, wait - 0.5f);
            case CgKeyCodes.KEY_PERIOD -> wait = Math.min(MAX_WAIT, wait + 0.5f);
            default -> {
                return false;
            }
        }
        return true;
    }

    /** The switches onto every wave still playing, so a press shows at once. */
    private void reapply() {
        for (int i = live.size() - 1; i >= 0; i--) {
            Live entry = live.get(i);
            if (entry.wave.state() == CgVfxEffect.State.DEAD) live.remove(i);
            else applyTo(entry);
        }
    }

    /** The scene's seconds at host time {@code seconds}: advanced since the last call at the time switch's rate. */
    public float time(double seconds) {
        if (!Double.isNaN(last)) clock += (float) (seconds - last) * SPEEDS[speed];
        last = seconds;
        return clock;
    }

    /** Seconds before the next wave: after the blast's impact frame, or after a beam stops firing. */
    public float waitSeconds() {
        return wait;
    }

    /** Sets the wave's flash, impact frame and billows from the switches, and again at each press while it plays. */
    public void apply(CgEnergyWave wave) {
        for (int i = live.size() - 1; i >= 0; i--) {
            if (live.get(i).wave.state() == CgVfxEffect.State.DEAD) live.remove(i);
        }
        Live entry = new Live(wave, wave.curve(CgEnergyWave.BLAST_FLASH));
        live.add(entry);
        applyTo(entry);
    }

    private void applyTo(Live entry) {
        entry.wave.set(CgEnergyWave.BLAST_FLASH, switch (flash) {
            case DOUBLE -> entry.flash;
            case SINGLE -> SINGLE;
            case OFF -> NONE;
        });
        entry.wave.set(CgEnergyWave.BLAST_IMPACT, impact ? 1f : 0f);
        entry.wave.set(CgEnergyWave.BLAST_BILLOWS, billows ? 1f : 0f);
    }

    /**
     * The switches as HUD lines: billows, flash and impact frame, a blank line, time and the wait; with {@code waves}
     * false, the time alone.
     */
    public String hudLines(boolean waves) {
        String time = "Time [Y, Shift+Y]: " + SPEEDS[speed] + "x";
        if (!waves) return time;
        return String.format(Locale.ROOT, "Smoke Clouds [B]: %s\nFlash [F]: %s - Impact frame [I]: %s\n\n%s\nNext after [, .]: %.1f s",
                billows ? "on" : "off", flash.name().toLowerCase(Locale.ROOT), impact ? "on" : "off", time, wait);
    }
}
