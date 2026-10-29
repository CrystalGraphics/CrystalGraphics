package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;

import org.joml.Matrix4f;
import org.junit.Test;

import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.platform.audio.CgAudioStream;
import com.crystalgraphics.render.stage.CgHostView;

/** The listener: on the camera's frame, panned by Unreal's stereo law, and the attenuation's curves. */
public class CgAudioListenerTest {

    /** A camera cut moves the listener on the frame it happens, not the frame after. */
    @Test
    public void theListenerFollowsACameraCutOnItsFrame() {
        CgWorld world = new CgWorld();
        CgHostView view = new CgHostView().set(0, 64, 0, new Matrix4f(), new Matrix4f());
        CgAudioEngine audio = world.add(new CgAudioEngine(format -> new CgAudioStream() {
            @Override public int free() { return 0; }
            @Override public boolean write(float[] interleaved) { return true; }
            @Override public int underruns() { return 0; }
            @Override public double latency() { return 0; }
            @Override public void close() { }
        }));
        world.frame(view);
        assertEquals(0, audio.listener().x(), 0);
        view.set(100, 64, 0, new Matrix4f(), new Matrix4f());
        world.frame(view);
        assertEquals("on the cut's frame", 100, audio.listener().x(), 0);
        assertEquals(64, audio.listener().y(), 0);
        world.close();
    }

    /** Ahead is centred at equal power; at a speaker, all of it there; between, by sine and cosine. */
    @Test
    public void panningFollowsTheEqualPowerLawBetweenTheSpeakers() {
        CgAudioListener ears = new CgAudioListener().set(0, 0, 0, 1, 0, 0, 0, 1, 0, 0);
        float[] lr = new float[2];
        ears.pan(10, 0, 0, lr);
        assertEquals((float) Math.sqrt(0.5), lr[0], 1e-6f);
        assertEquals((float) Math.sqrt(0.5), lr[1], 1e-6f);
        double r30 = Math.toRadians(30);
        ears.pan(Math.cos(r30), 0, Math.sin(r30), lr);
        assertEquals("at the right speaker", 1f, lr[1], 1e-5f);
        assertEquals(0f, lr[0], 1e-5f);
        ears.pan(Math.cos(r30), 0, -Math.sin(r30), lr);
        assertEquals("at the left speaker", 1f, lr[0], 1e-5f);
        assertEquals(90, ears.azimuth(0, 0, 1), 1e-9);
        assertEquals(270, ears.azimuth(0, 0, -1), 1e-9);
    }

    /** Each curve at the middle of its falloff, and the natural sound's reach to -60 dB. */
    @Test
    public void attenuationCurvesFollowUnrealsFormulas() {
        CgSoundAttenuation a = new CgSoundAttenuation().inner(4).falloff(36);
        assertEquals(1f, a.gain(3), 0);
        assertEquals(0.5f, a.gain(22), 1e-6f);
        assertEquals(0f, a.gain(50), 0);
        a.algorithm(CgSoundAttenuation.Algorithm.LOGARITHMIC);
        assertEquals((float) (0.5 * -Math.log(0.5)), a.gain(22), 1e-6f);
        a.algorithm(CgSoundAttenuation.Algorithm.NATURAL_SOUND);
        assertEquals("-30 dB halfway", (float) Math.pow(10, -30 / 20.0), a.gain(22), 1e-6f);
        a.dbAtMax(-30);
        assertEquals("on until -60 dB: twice the falloff", 4 + 72, a.maxDistance(), 1e-6);
    }
}
