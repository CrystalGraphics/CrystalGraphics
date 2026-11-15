package com.crystalgraphics.demo.anim;

import com.crystalgraphics.frame.CgTickFunction;
import com.crystalgraphics.frame.CgTickGroup;
import com.crystalgraphics.frame.CgWorld;
import org.joml.Matrix4f;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The demo's time: a rate (the client world's time dilation), a pause, and frame steps forward and back while paused.
 *
 * <pre>{@code
 * seconds += time.advance(frameSeconds);   // each frame: the demo's clock, and the world's dilation set
 * time.togglePause();
 * time.step(1);                            // paused: a frame on, every body ticked 1/30 s
 * time.step(-1);                           // a frame back, shown from what was recorded
 * }</pre>
 *
 * <ul>
 *   <li>A graph keeps state between frames (dynamics, springs), so a step back cannot be evaluated: every body's
 *       skinning is recorded every {@link #FRAME} of play, {@link #HISTORY} deep, and a step back shows a record. Steps
 *       on from there walk back through the records, then tick again.</li>
 *   <li>Playing on from a frame stepped back to jumps to where play paused.</li>
 * </ul>
 */
final class AnimDemoTime {

    static final float FRAME = 1f / 30f;
    private static final float[] RATES = {0.1f, 0.25f, 0.5f, 1f, 1.5f, 2f};
    /** Records kept: two seconds of frames. */
    private static final int HISTORY = 60;

    /** One body's recorded skinning, a ring of frames. */
    private static final class Track {
        final float[][] frames;
        int written;

        Track(int bones) {
            frames = new float[HISTORY][bones * 16];
        }
    }

    private final AnimDemoCasts casts;
    private final Map<AnimDemoCast.Body, Track> tracks = new HashMap<>();
    private int rate = 3, recorded, back, pendingSteps;
    private boolean paused;
    private float sinceRecord;
    private CgTickFunction show;

    AnimDemoTime(AnimDemoCasts casts) {
        this.casts = casts;
    }

    void togglePause() {
        paused = !paused;
        if (!paused) back = 0;
    }

    /** The rate, {@code by} steps faster (or slower, negative) along 0.1x to 2x. */
    void faster(int by) {
        rate = Math.max(0, Math.min(RATES.length - 1, rate + by));
    }

    /** Pauses, then {@code frames} frames on (positive) or back (negative). */
    void step(int frames) {
        paused = true;
        for (int i = 0; i < Math.abs(frames); i++) {
            if (frames < 0) {
                back = Math.min(back + 1, Math.max(0, Math.min(recorded, HISTORY) - 1));
            } else if (back > 0) {
                back--;
            } else {
                pendingSteps++;
            }
        }
    }

    /**
     * This frame's demo seconds, {@code frameSeconds} of the host's: scaled by the rate, nothing while paused, a frame
     * for each step on. Sets the client world's dilation to match.
     */
    float advance(float frameSeconds) {
        attach();
        float dilation = paused ? 0f : RATES[rate];
        CgWorld.client().timeDilation(dilation);
        return paused ? pendingSteps * FRAME : frameSeconds * dilation;
    }

    /** The steps on asked for since the last frame: every body ticked a frame each, before the world poses them. */
    void tickSteps() {
        for (; pendingSteps > 0; pendingSteps--) {
            List<AnimDemoCast.Body> bodies = casts.bodies();
            for (int i = 0; i < bodies.size(); i++) bodies.get(i).instance.tick(FRAME);
            record();
        }
    }

    /** After the world poses its bodies: records them each {@link #FRAME} of play, or shows a frame stepped back to. */
    private void afterPose(float dt) {
        if (back > 0) {
            int at = Math.floorMod(recorded - 1 - back, HISTORY);
            List<AnimDemoCast.Body> bodies = casts.bodies();
            for (int n = 0; n < bodies.size(); n++) {
                AnimDemoCast.Body b = bodies.get(n);
                Track t = tracks.get(b);
                if (t == null || t.written < recorded - back) continue;
                Matrix4f[] skinning = b.instance.skinning();
                float[] f = t.frames[at];
                for (int i = 0; i < skinning.length; i++) skinning[i].set(f, i * 16);
            }
            return;
        }
        if (paused) return;
        sinceRecord += dt;
        if (sinceRecord < FRAME) return;
        sinceRecord = Math.min(sinceRecord - FRAME, FRAME);
        record();
    }

    private void record() {
        int at = recorded % HISTORY;
        List<AnimDemoCast.Body> bodies = casts.bodies();
        if (tracks.size() > bodies.size()) tracks.keySet().retainAll(bodies);
        for (int n = 0; n < bodies.size(); n++) {
            AnimDemoCast.Body b = bodies.get(n);
            Matrix4f[] skinning = b.instance.skinning();
            Track t = tracks.get(b);
            if (t == null) tracks.put(b, t = new Track(skinning.length));
            float[] f = t.frames[at];
            for (int i = 0; i < skinning.length; i++) skinning[i].get(f, i * 16);
            t.written = recorded + 1;
        }
        recorded++;
    }

    /** Orders the show after the world's animation, once it has one. */
    private void attach() {
        if (show != null) return;
        for (CgTickFunction f : CgWorld.client().schedule().order()) {
            if (f.name().equals("crystalgraphics:animation")) {
                show = CgWorld.client().schedule().add(CgTickGroup.POSE, "crystalgraphics:anim-demo.time", this::afterPose)
                        .after(f).whilePaused(true);
                return;
            }
        }
    }

    /** Its HUD line, starting with a line break. */
    String hud() {
        String state = paused ? back > 0 ? String.format(Locale.ROOT, "paused, %d frame%s back", back, back == 1 ? "" : "s") : "paused" : "playing";
        return String.format(Locale.ROOT, "%nTime %.2fx [U/I] | %s [P] | Frame [, .]", RATES[rate], state);
    }

    void delete() {
        if (show != null) show.close();
        show = null;
        tracks.clear();
        CgWorld.client().timeDilation(1f);
    }
}
