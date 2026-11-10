package com.crystalgraphics.audio;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.After;
import org.junit.BeforeClass;
import org.junit.Test;

import com.crystalgraphics.asset.codec.CgAssetYaml;
import com.crystalgraphics.asset.kind.CgAssetKinds;
import com.crystalgraphics.asset.kind.CgSequenceKinds;
import com.crystalgraphics.asset.tree.CgAssetData;
import com.crystalgraphics.frame.CgWorld;
import com.crystalgraphics.i18n.CgKitStrings;
import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.audio.CgAudioDecoder;
import com.crystalgraphics.platform.audio.CgAudioStream;
import com.crystalgraphics.platform.service.CgI18n;
import com.crystalgraphics.sequence.CgSequencePlayer;
import com.crystalgraphics.sequence.CgSequencer;
import com.crystalgraphics.sequence.definition.CgSequenceDefinition;

/** A sequence's subtitle track: each line in the player's language over its section, an open end lasting its voice. */
public class CgSequenceSubtitleTest {

    /** 1000 ticks a second. The voice is 3 s long; the first line lasts it, the second its own section. */
    private static final String MOVE = "_v: 1\ntick_resolution: 1000\ndisplay_rate: 50\nrange: [0, 6000]\ntracks:\n"
            + "  - { id: voices, kind: sound, sections: [ { id: v1, range: [500, null], sound: subtest:sound/S_voice } ] }\n"
            + "  - { id: lines, kind: subtitle, sections: [ { id: l1, range: [500, null], text: subtest:text/Line_01, sound: v1 } ] }\n"
            + "  - { id: shout, kind: subtitle, sections: [ { id: l2, range: [4000, 4500], text: subtest:text/Line_02 } ] }\n";

    @BeforeClass
    public static void aKit() throws IOException {
        Path kit = Files.createTempDirectory("subsub");
        Files.createDirectories(kit.resolve("sounds"));
        Files.createDirectories(kit.resolve("strings"));
        Files.write(kit.resolve("sounds/voice.ogg"), ByteBuffer.allocate(12).putInt(44100).putInt(3).putInt(440).array());
        Files.write(kit.resolve("sounds.yaml"), """
                _v: 1
                namespace: subtest
                sounds:
                  S_voice: { path: subsub:sounds/voice.ogg }
                  S_shout: { path: subsub:sounds/voice.ogg, subtitle_priority: 20000,
                             subtitles: [ { text: subtest:text/Line_02 }, { text: mymod:text/combo.nice, time: 1.5 } ] }
                """.getBytes(StandardCharsets.UTF_8));
        Files.write(kit.resolve("strings/en_us.yaml"), """
                _v: 1
                strings:
                  Line_01: { text: Kamehame..., speaker: subtest:text/Speaker_Goku }
                  Line_02: HAAAA!
                  Speaker_Goku: Goku
                """.getBytes(StandardCharsets.UTF_8));
        Files.write(kit.resolve("strings/fr_fr.yaml"), """
                _v: 1
                strings:
                  Line_01: { text: Kaméhamé..., speaker: subtest:text/Speaker_Goku }
                """.getBytes(StandardCharsets.UTF_8));
        Files.write(kit.resolve("subtitle_style.yaml"), """
                _v: 1
                widget: subsub:hud/subtitles
                line: Txt_Line
                """.getBytes(StandardCharsets.UTF_8));
        CgAssetKinds.register();
        CgKitSounds.registerKit(kit);
        CgKitStrings.registerKit("subsub", kit);
        CgSubtitleStyle.registerKit("subsub", kit);
    }

    /** Provided once, as {@link CgI18n} keeps the host it is first given; a test sets the language it answers. */
    private static final class Language implements CgI18n.Host {
        static final Language INSTANCE = new Language();
        static { CgPlatform.provide(CgI18n.HOST, INSTANCE); }
        volatile String code = "en_us";
        final Map<String, String> keys = new ConcurrentHashMap<>();
        @Override public String translate(String key) { return keys.get(key); }
        @Override public String language() { return code; }
    }

    @After
    public void english() {
        Language.INSTANCE.code = "en_us";
        Language.INSTANCE.keys.clear();
    }

    @Test
    public void anIdNoKitHoldsIsAModsTranslation() {
        Language.INSTANCE.keys.put("mymod.combo.nice", "Nice!");
        Language.INSTANCE.keys.put("subtitles.entity.generic.explode", "Explosion");
        Language.INSTANCE.keys.put("subtest.Line_02", "not the kit's");
        assertEquals("the key <ns>.<id>", "Nice!", CgKitStrings.text("mymod:text/combo.nice"));
        assertEquals("else <id> as written", "Explosion",
                CgKitStrings.text("minecraft:text/subtitles.entity.generic.explode"));
        assertEquals("a kit's own table first", "HAAAA!", CgKitStrings.text("subtest:text/Line_02"));
        assertNull(CgKitStrings.text("mymod:text/combo.missing"));
    }

    @Test
    public void aSoundsOwnSubtitlesQueueAsItPlaysInThePlayersLanguage() {
        play();
        Language.INSTANCE.keys.put("mymod.combo.nice", "Nice!");
        long id = audio.play(CgSoundRef.of("subtest:sound/S_shout", "test"), CgSoundClass.VOICE, Double.NaN, 0, 0, 1f, 1f,
                false, Double.NaN, 0L);
        double[] at = {0.2};
        audio.subtitles().update(sound -> sound == id ? at[0] : Double.NaN);
        assertEquals("its first cue, the kit's string", "HAAAA!", audio.subtitles().text());
        at[0] = 1.6;
        audio.subtitles().update(sound -> sound == id ? at[0] : Double.NaN);
        assertEquals("its second, the mod's lang key", "Nice!", audio.subtitles().text());
        audio.subtitles().update(sound -> Double.NaN);
        assertNull("gone with its sound", audio.subtitles().text());
        world.close();
    }

    @Test
    public void cuesOutOfOrderFailWhereTheSoundIsRead() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> CgSoundRef.of(Map.of(
                "path", "mymod:sounds/kame.ogg",
                "subtitles", List.of(Map.of("text", "a", "time", 1.0), Map.of("text", "b", "time", 0.5))), "mymod:kame"));
        assertTrue(e.getMessage(), e.getMessage().contains("mymod:kame.subtitles[1]"));
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
        CgSequenceDefinition move = CgSequenceDefinition.compile(CgAssetData.of("subsub:move", CgSequenceKinds.SEQUENCE,
                (Map<?, ?>) CgAssetYaml.read(MOVE), null));
        return sequencer.play(move, Map.of());
    }

    /** Steps the sequence, then the subtitles as the engine does each frame. */
    private void step(float seconds) {
        sequencer.evaluate(seconds);
        audio.subtitles().update(id -> audio.activeSounds().playing(id) ? audio.activeSounds().time(id) : Double.NaN);
    }

    /** A line shows from its tick with its speaker; an open end lasts the voice it names; the next its own section. */
    @Test
    public void linesShowOverTheirSectionsAndAnOpenEndLastsItsVoice() {
        play();
        step(0.4f);
        assertNull("before the first line's tick", audio.subtitles().text());
        step(0.2f);
        assertEquals("Kamehame...", audio.subtitles().text());
        assertEquals("the speaker, the string's own", "Goku", audio.subtitles().speaker());
        assertSame("drawn in its game's kit's style", CgSubtitleStyle.of("subsub"), audio.subtitles().style());
        step(2.8f);                                                  // 3.4 s: 2.9 s into the 3 s voice
        assertEquals("Kamehame...", audio.subtitles().text());
        step(0.2f);                                                  // 3.6 s: the voice is over
        assertNull(audio.subtitles().text());
        step(0.6f);                                                  // 4.2 s: inside the second line's section
        assertEquals("its own track, its own section","HAAAA!", audio.subtitles().text());
        step(0.4f);                                                  // 4.6 s: its section ended
        assertNull(audio.subtitles().text());
        world.close();
    }

    /** Joined by a seek, a line shows at its age; the player's language reads its own text, English where it has none. */
    @Test
    public void aSeekJoinsALineAndTheLanguageIsThePlayers() {
        Language.INSTANCE.code = "fr_fr";
        CgSequencePlayer player = play();
        player.jumpTo(2.0);
        step(0f);
        assertEquals("1.5 s into the voice, in French", "Kaméhamé...", audio.subtitles().text());
        assertEquals("no French name: the English one", "Goku", audio.subtitles().speaker());
        player.jumpTo(4.2);
        step(0f);
        assertEquals("no French line: the English one", "HAAAA!", audio.subtitles().text());
        world.close();
    }
}
