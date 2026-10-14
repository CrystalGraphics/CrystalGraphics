package com.crystalgraphics.serialization.format;

import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgObjectCodec;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A codec's value in JSON, JSONC, YAML and TOML, files and text, and the format a file's name picks. */
public class CgTextFormatsTest {

    static final class Settings {
        String name = "";
        int level = 1;
        long seed;
        float volume = 1f;
        double scale = 1;
        boolean ki;
        byte[] icon = new byte[0];
        final List<String> muted = new ArrayList<>();
        final Map<String, Integer> skills = new LinkedHashMap<>();

        static final CgObjectCodec<Settings> CODEC = CgCodecs.object(Settings::new)
                .field("name", CgCodecs.STRING, s -> s.name, (s, v) -> s.name = v)
                .field("level", CgCodecs.INT, s -> s.level, (s, v) -> s.level = v)
                .field("seed", CgCodecs.LONG, s -> s.seed, (s, v) -> s.seed = v)
                .field("volume", CgCodecs.FLOAT, s -> s.volume, (s, v) -> s.volume = v)
                .field("scale", CgCodecs.DOUBLE, s -> s.scale, (s, v) -> s.scale = v)
                .field("ki", CgCodecs.BOOL, s -> s.ki, (s, v) -> s.ki = v)
                .field("icon", CgCodecs.BYTES, s -> s.icon, (s, v) -> s.icon = v)
                .into("muted", CgCodecs.listOf(CgCodecs.STRING), s -> s.muted)
                .into("skills", CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT), s -> s.skills)
                .build();
    }

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static Settings goku() {
        Settings s = new Settings();
        s.name = "Goku";
        s.level = 42;
        s.seed = Long.MAX_VALUE;
        s.volume = 0.5f;
        s.scale = Math.PI;
        s.ki = true;
        s.icon = new byte[]{1, -2, 3};
        s.muted.addAll(Arrays.asList("vegeta", "frieza"));
        s.skills.put("kamehameha", 3);
        return s;
    }

    private static void assertGoku(Settings s) {
        assertEquals("Goku", s.name);
        assertEquals(42, s.level);
        assertEquals(Long.MAX_VALUE, s.seed);
        assertEquals(0.5f, s.volume, 0f);
        assertEquals(Math.PI, s.scale, 0);
        assertTrue(s.ki);
        assertArrayEquals(new byte[]{1, -2, 3}, s.icon);
        assertEquals(Arrays.asList("vegeta", "frieza"), s.muted);
        assertEquals(Integer.valueOf(3), s.skills.get("kamehameha"));
    }

    @Test
    public void everyKindOfValueCrossesBothTextFormats() {
        for (CgTextFormat format : Arrays.asList(CgFormats.JSON, CgFormats.JSONC, CgFormats.YAML, CgFormats.TOML)) {
            String text = format.toText(Settings.CODEC, goku());
            assertTrue(text, text.contains("kamehameha"));
            assertGoku(format.fromText(Settings.CODEC, text));
        }
    }

    @Test
    public void aHandWrittenFileIsRead() throws IOException {
        Path yaml = folder.getRoot().toPath().resolve("settings.yml");
        Files.write(yaml, ("# a comment\nname: Goku\nlevel: 9\nmuted:\n  - vegeta\nskills:\n  kamehameha: 3\n")
                .getBytes(StandardCharsets.UTF_8));
        Settings s = CgFormats.of(yaml).read(yaml, Settings.CODEC);
        assertEquals("Goku", s.name);
        assertEquals(9, s.level);
        assertEquals("absent keys keep the constructor's", 1f, s.volume, 0f);
        assertEquals(Arrays.asList("vegeta"), s.muted);

        Path json = folder.getRoot().toPath().resolve("settings.json");
        Files.write(json, "{ \"name\": \"Vegeta\", \"ki\": true }".getBytes(StandardCharsets.UTF_8));
        Settings v = CgFormats.of(json).read(json, Settings.CODEC);
        assertEquals("Vegeta", v.name);
        assertTrue(v.ki);
    }

    @Test
    public void aFileIsWrittenWholeAndReadIntoALiveObject() throws IOException {
        for (String name : Arrays.asList("live.json", "live.yaml", "live.toml", "live.jsonc", "live.dat")) {
            Path file = folder.getRoot().toPath().resolve(name);
            CgFormat format = CgFormats.of(file);
            format.write(file, Settings.CODEC, goku());
            assertFalse(Files.exists(file.resolveSibling(name + ".tmp")));

            Settings live = new Settings();
            List<String> muted = live.muted;
            assertSame(live, format.read(file, Settings.CODEC, live));
            assertSame(muted, live.muted);
            assertGoku(live);
        }
    }

    @Test
    public void theNamePicksTheFormat() {
        assertSame(CgFormats.JSON, CgFormats.of(folder.getRoot().toPath().resolve("a.JSON")));
        assertSame(CgFormats.YAML, CgFormats.of(folder.getRoot().toPath().resolve("a.yml")));
        assertSame(CgFormats.YAML, CgFormats.of(folder.getRoot().toPath().resolve("a.yaml")));
        assertSame(CgFormats.JSONC, CgFormats.of(folder.getRoot().toPath().resolve("a.jsonc")));
        assertSame(CgFormats.TOML, CgFormats.of(folder.getRoot().toPath().resolve("a.toml")));
        assertSame(CgFormats.BINARY, CgFormats.of(folder.getRoot().toPath().resolve("a.dat")));
    }

    @Test
    public void textThatIsNotTheFormatSaysSo() {
        assertThrows(CgCodecException.class, () -> CgFormats.JSON.fromText(Settings.CODEC, "{ \"name\": "));
        assertThrows(CgCodecException.class, () -> CgFormats.YAML.fromText(Settings.CODEC, "name: [unclosed"));
        // a file builds maps, lists and scalars, never a class it names
        assertThrows(CgCodecException.class,
                () -> CgFormats.YAML.fromText(Settings.CODEC, "!!java.io.File [\"/tmp\"]"));
        assertThrows(CgCodecException.class, () -> CgFormats.TOML.fromText(Settings.CODEC, "name = \"unclosed"));
    }
}
