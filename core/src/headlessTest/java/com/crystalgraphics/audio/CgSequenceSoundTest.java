package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.platform.audio.CgAudioStream;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/** A sequence's sound track: each section's sound on its tick, by the audio clock, as Unreal's audio track plays it. */
public class CgSequenceSoundTest {

    /** 1000 ticks a second: a section at 150 starts inside a 200 ms frame. */
    private static final String MOVE = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 2000]\ntracks:\n"
            + "  - { id: t1, kind: sound, sections: [\n"
            + "      { id: hit, range: [150, 600], sound: seqtest:sound/S_hit },\n"
            + "      { id: hum, range: [300, 1800], sound: seqtest:sound/S_hum, looping: true },\n"
            + "      { id: late, range: [500, 1500], sound: seqtest:sound/S_hit } ] }\n";

    @BeforeClass
    public static void aKit() throws IOException {
        Path kit = Files.createTempDirectory("seqtest_kit");
        Files.createDirectories(kit.resolve("sounds"));
        ByteBuffer sine = ByteBuffer.allocate(12).putInt(44100).putInt(3).putInt(440);
        Files.write(kit.resolve("sounds/hit.ogg"), sine.array());
        Files.write(kit.resolve("sounds/hum.ogg"), sine.array());
        Files.write(kit.resolve("sounds.yaml"), """
                _v: 1
                namespace: seqtest
                sounds:
                  S_hit: { path: seqtest_kit:sounds/hit.ogg }
                  S_hum: { path: seqtest_kit:sounds/hum.ogg }
                """.getBytes(StandardCharsets.UTF_8));
        CgKitSounds.registerKit(kit);
    }

    /** The test's "files": a rate, seconds and hertz, decoded as a sine. */
    private static final CgAudioDecoder SINES = ogg -> {
        ByteBuffer b = ogg.duplicate();
        int rate = b.getInt(), seconds = b.getInt(), hertz = b.getInt();
        return new CgAudioDecoder.Stream() {
            long at;

            @Override public int channels() { return 1; }
            @Override public int sampleRate() { return rate; }
            @Override public long frames() { return (long) rate * seconds; }
            @Override public int read(float[] out, int offset, int frames) {
                int n = (int) Math.min(frames, frames() - at);
                for (int i = 0; i < n; i++, at++) out[offset + i] = (float) Math.sin(2 * Math.PI * hertz * at / rate);
                return n;
            }
            @Override public void seek(long frame) { at = frame; }
            @Override public void close() { }
        };
    };

    private CgWorld world;
    private CgAudioEngine audio;
    private CgSequencer sequencer;

    private CgSequencePlayer play() {
        world = new CgWorld();
        audio = world.add(new CgAudioEngine(format -> new CgAudioStream() {
            @Override public int free() { return 0; }
            @Override public boolean write(float[] interleaved) { return true; }
            @Override public int underruns() { return 0; }
            @Override public double latency() { return 0; }
            @Override public void close() { }
        }, SINES));
        sequencer = world.add(new CgSequencer());
        CgSequenceDefinition move = CgSequenceDefinition.compile(CgAssetData.of("mymod:move", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(MOVE), null));
        return sequencer.play(move, Map.of());
    }

    /** A section crossed inside a frame starts where it fell, on the audio clock; its end stops it. */
    @Test
    public void aSectionStartsOnItsTickAndStopsAtItsEnd() {
        play();
        double now = audio.clock().now();
        sequencer.evaluate(0.2f);                                   // ticks 0 to 200: the hit at 150
        long hit = audio.activeSounds().latest();
        assertEquals("50 ms before the frame's end", now - 0.05, audio.activeSounds().startsAt(hit), 1e-6);
        sequencer.evaluate(0.2f);                                   // to 400: the hum at 300
        long hum = audio.activeSounds().latest();
        assertEquals(now - 0.1, audio.activeSounds().startsAt(hum), 1e-6);
        sequencer.evaluate(0.4f);                                   // to 800: past the hit's end at 600
        assertFalse("its section ended", audio.activeSounds().playing(hit));
        assertTrue(audio.activeSounds().playing(hum));
        world.close();
    }

    /** Joined late, a looping section starts at its age in the section; a one-shot already passed is dropped. */
    @Test
    public void aLateJoinStartsALoopAtItsAgeAndDropsAPassedOneShot() {
        CgSequencePlayer player = play();
        player.jumpTo(1.0);
        sequencer.evaluate(0f);
        assertEquals("only the hum: the late hit passed at 500", 1, audio.activeSounds().count());
        long hum = audio.activeSounds().latest();
        assertEquals("700 ms into its section", 0.7, audio.activeSounds().time(hum), 1e-6);
        assertTrue("at once, not on a sample", Double.isNaN(audio.activeSounds().startsAt(hum)));
        world.close();
    }
}
