package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;

import java.util.EnumMap;
import java.util.Map;

import org.junit.After;
import org.junit.Test;

import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.audio.CgAudioStream;
import com.crystalgraphics.platform.audio.CgHostDuck;
import com.crystalgraphics.platform.audio.CgHostVolume;
import com.crystalgraphics.platform.audio.CgHostVolume.Category;

/** A cinematic's hush on the host's own sounds: a mix's host ducks, faded with it, and restored at its end. */
public class CgHostDuckTest {

    /** What the host was told, by category. */
    private final Map<Category, Float> host = new EnumMap<>(Category.class);

    @After
    public void noHost() {
        CgPlatform.provide(CgHostDuck.SERVICE, null);
    }

    private CgAudioEngine engine(CgWorld world) {
        CgPlatform.provide(CgHostDuck.SERVICE, (category, factor) -> host.put(category, factor));
        return world.add(new CgAudioEngine(format -> new CgAudioStream() {
            @Override public int free() { return 0; }
            @Override public boolean write(float[] interleaved) { return true; }
            @Override public int underruns() { return 0; }
            @Override public double latency() { return 0; }
            @Override public void close() { }
        }));
    }

    /** Pushed, the host's blocks fall to the mix's level over its fade; popped, they come back to 1. */
    @Test
    public void aMixDucksTheHostAndRestoresIt() {
        CgActiveSounds a = new CgActiveSounds(64, new CgActiveSoundsTest.Recording());
        CgSoundMix hush = new CgSoundMix(1f, 0.5f).duckHost(Category.BLOCKS, 0.2f).duckHost(Category.WEATHER, 0.5f);
        a.push(hush);
        update(a, 0.5);
        assertEquals("half faded in", 0.6f, a.hostDuck(Category.BLOCKS), 1e-6f);
        update(a, 0.5);
        assertEquals(0.2f, a.hostDuck(Category.BLOCKS), 1e-6f);
        assertEquals(0.5f, a.hostDuck(Category.WEATHER), 1e-6f);
        assertEquals("untouched", 1f, a.hostDuck(Category.MUSIC), 0f);
        a.pop(hush);
        update(a, 0.5);
        assertEquals(1f, a.hostDuck(Category.BLOCKS), 0f);
    }

    /** The engine tells the host once a change moves it, and restores every category when it closes. */
    @Test
    public void theEngineTellsTheHostAndRestoresItOnClose() {
        CgWorld world = new CgWorld();
        CgAudioEngine audio = engine(world);
        audio.push(new CgSoundMix(0f, 0f).duckHost(Category.HOSTILE, 0.25f));
        world.frame(null);
        assertEquals(0.25f, host.get(Category.HOSTILE), 1e-6f);
        assertEquals("nothing posted for a category no mix names", null, host.get(Category.BLOCKS));
        world.close();
        assertEquals("restored as the engine closes", 1f, host.get(Category.HOSTILE), 0f);
    }

    private static void update(CgActiveSounds a, double dt) {
        a.update(dt, new CgAudioListener(), false, CgHostVolume.NONE);
    }
}
