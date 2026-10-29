package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.crystalgraphics.platform.audio.CgHostVolume;

/** The active sounds: voices given by priority, groups culled by their rule, loops kept virtual, mixes faded. */
public class CgActiveSoundsTest {

    /** Records what the active sounds asked of the engine. */
    private static final class Recording implements CgActiveSounds.Sink {
        final List<Double> offsets = new ArrayList<>();
        final Map<CgAudioSource, Float> gains = new IdentityHashMap<>();
        final List<CgAudioSource> stopped = new ArrayList<>();

        @Override
        public CgAudioSource voice(CgSound sound, float pitch, boolean loop, double offset) {
            offsets.add(offset);
            return new CgAudioTone(440, 0.1f, 10);
        }

        @Override
        public boolean ended(CgAudioSource voice) {
            return false;
        }

        @Override
        public void start(CgAudioSource voice, float gain, CgSoundClass soundClass, double at) {
            gains.put(voice, gain);
        }

        @Override
        public void gain(CgAudioSource voice, float gain) {
            gains.put(voice, gain);
        }

        @Override
        public void stop(CgAudioSource voice) {
            stopped.add(voice);
        }
    }

    private static CgSound sound(double seconds) {
        int rate = 1000;
        return new CgSound("test:s", 1, rate, (long) (seconds * rate), new short[(int) (seconds * rate)], null);
    }

    private static long play(CgActiveSounds a, double seconds, boolean loop) {
        return a.play(sound(seconds), CgSoundClass.SFX, 1f, 1f, loop, Double.NaN);
    }

    private static void update(CgActiveSounds a, double dt, double listenerX) {
        a.update(dt, listenerX, 0, 0, false, CgHostVolume.NONE);
    }

    /** A brawl's bursts, many and low in priority, never take a move's voices under a small limit. */
    @Test
    public void lowPriorityBurstsNeverTakeTheMovesVoices() {
        CgActiveSounds a = new CgActiveSounds(4, new Recording());
        CgSoundConcurrency bursts = new CgSoundConcurrency(8, CgSoundConcurrency.Rule.STOP_OLDEST);
        long[] burst = new long[10];
        for (int i = 0; i < 5; i++) a.group(burst[i] = play(a, 0.5, false), bursts);
        long beam = play(a, 3, true), shout = play(a, 3, false);
        a.priority(beam, 10f).priority(shout, 10f);
        for (int i = 5; i < 10; i++) a.group(burst[i] = play(a, 0.5, false), bursts);
        update(a, 0.016, 0);
        assertNotNull(a.voice(beam));
        assertNotNull(a.voice(shout));
        assertEquals("the group culled to its count", 2, a.culled());
        assertFalse("the oldest go first", a.playing(burst[0]) || a.playing(burst[1]));
        int voiced = 0;
        for (long id : burst) if (a.voice(id) != null) voiced++;
        assertEquals("the bursts share what is left", 2, voiced);
    }

    /** A loop leaving its range goes virtual, its time running on, and comes back where it would be. */
    @Test
    public void aLoopOutOfRangeGoesVirtualAndReturnsWhereItWouldBe() {
        Recording sink = new Recording();
        CgActiveSounds a = new CgActiveSounds(64, sink);
        long hum = play(a, 2, true);
        a.at(hum, 10, 0, 0).range(hum, 20);
        update(a, 0.5, 0);
        CgAudioSource first = a.voice(hum);
        assertNotNull(first);
        update(a, 0.5, 100);
        assertTrue(a.virtual(hum));
        assertNull(a.voice(hum));
        assertTrue(sink.stopped.contains(first));
        update(a, 1.0, 100);
        update(a, 1.0, 0);
        assertNotNull(a.voice(hum));
        assertEquals("2.5 s in, a 2 s loop is 0.5 s through", 0.5, sink.offsets.get(1), 1e-9);
        assertEquals(2.5, a.time(hum), 1e-9);
    }

    /** A one-shot out of range ends: there is no point in it to come back to. */
    @Test
    public void aOneShotOutOfRangeEnds() {
        CgActiveSounds a = new CgActiveSounds(64, new Recording());
        long boom = play(a, 2, false);
        a.at(boom, 50, 0, 0).range(boom, 20);
        update(a, 0.016, 0);
        assertFalse(a.playing(boom));
    }

    /** Each rule keeps what it should. */
    @Test
    public void groupsCullByTheirRule() {
        CgActiveSounds a = new CgActiveSounds(64, new Recording());
        CgSoundConcurrency prevent = new CgSoundConcurrency(2, CgSoundConcurrency.Rule.PREVENT_NEW);
        long[] p = new long[3];
        for (int i = 0; i < 3; i++) a.group(p[i] = play(a, 1, false), prevent);
        CgSoundConcurrency farthest = new CgSoundConcurrency(2, CgSoundConcurrency.Rule.STOP_FARTHEST);
        long[] f = new long[3];
        double[] at = {5, 50, 10};
        for (int i = 0; i < 3; i++) a.group(f[i] = play(a, 1, false), farthest).at(f[i], at[i], 0, 0);
        CgSoundConcurrency quiet = new CgSoundConcurrency(1, CgSoundConcurrency.Rule.STOP_QUIETEST);
        long loud = play(a, 1, false), soft = play(a, 1, false);
        a.group(soft, quiet).gain(soft, 0.2f).group(loud, quiet);
        CgSoundConcurrency owned = new CgSoundConcurrency(1, CgSoundConcurrency.Rule.STOP_OLDEST).limitToOwner(true);
        long mine = play(a, 1, false), theirs = play(a, 1, false);
        a.group(mine, owned).owner(mine, "a").group(theirs, owned).owner(theirs, "b");
        update(a, 0.016, 0);
        assertTrue(a.playing(p[0]) && a.playing(p[1]) && !a.playing(p[2]));
        assertTrue(a.playing(f[0]) && !a.playing(f[1]) && a.playing(f[2]));
        assertTrue(a.playing(loud) && !a.playing(soft));
        assertTrue("one each per owner", a.playing(mine) && a.playing(theirs));
    }

    /** A mix fades its adjustment in, and out again, on the voices of its class and the classes within it. */
    @Test
    public void aMixFadesInAndOut() {
        Recording sink = new Recording();
        CgActiveSounds a = new CgActiveSounds(64, sink);
        long boom = play(a, 10, false);
        long tune = a.play(sound(10), CgSoundClass.MUSIC, 1f, 1f, false, Double.NaN);
        CgSoundMix duck = new CgSoundMix(1f, 0.5f).adjust(CgSoundClass.SFX, 0.5f, 1f);
        a.push(duck);
        update(a, 0.5, 0);
        assertEquals("half faded in", 0.75f, sink.gains.get(a.voice(boom)), 1e-5f);
        update(a, 0.5, 0);
        assertEquals(0.5f, sink.gains.get(a.voice(boom)), 1e-5f);
        assertEquals("music is not within sfx", 1f, sink.gains.get(a.voice(tune)), 1e-5f);
        a.pop(duck);
        update(a, 0.5, 0);
        assertEquals(1f, sink.gains.get(a.voice(boom)), 1e-5f);
    }

    /** A stale id names nothing, though its slot plays another sound. */
    @Test
    public void aStaleIdNamesNothing() {
        CgActiveSounds a = new CgActiveSounds(64, new Recording());
        long first = play(a, 1, false);
        a.stop(first);
        long second = play(a, 1, false);
        assertEquals("the slot is reused", (int) first, (int) second);
        assertFalse(a.playing(first));
        a.stop(first);
        assertTrue(a.playing(second));
    }
}
