package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgObjectCodec;
import com.crystalgraphics.serialization.format.CgFormats;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** JSON with comments: read as VS Code reads it, written with a codec's comments, a person's kept. */
public class CgJsoncTest {

    record Spell(String name, int cost) {
        static final CgCodec<Spell> CODEC = CgCodecs.record(
                CgCodecs.field("name", CgCodecs.STRING, Spell::name),
                CgCodecs.comment("Ki it takes.").field("cost", CgCodecs.INT, Spell::cost),
                Spell::new);
    }

    static class Config {
        String title = "";
        float volume = 1f;
        final List<Spell> spells = new ArrayList<>();

        static final CgObjectCodec<Config> CODEC = CgCodecs.object(Config::new)
                .comment("Shown in the menu.").field("title", CgCodecs.STRING, c -> c.title, (c, v) -> c.title = v)
                .comment("Master volume, 0 to 1.").field("volume", CgCodecs.FLOAT, c -> c.volume, (c, v) -> c.volume = v)
                .into("spells", CgCodecs.listOf(Spell.CODEC), c -> c.spells)
                .build()
                .comment("mymod settings.");
    }

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    @SuppressWarnings("unchecked")
    public void commentsAndTrailingCommasRead() {
        Map<String, Object> t = (Map<String, Object>) JsoncReader.read(""
                + "// a header\n"
                + "{\n"
                + "  /* block */ \"a\": 1, // after a\n"
                + "  \"b\": [1, 2.5, -3e2, \"x\\u00e9\", true, null,],\n"
                + "  \"c\": { \"d\": 9223372036854775807, },\n"
                + "}\n", null);
        assertEquals(1, t.get("a"));
        assertEquals(Arrays.asList(1, 2.5, -300.0, "x\u00e9", true, null), t.get("b"));
        assertEquals(Long.MAX_VALUE, ((Map<String, Object>) t.get("c")).get("d"));
    }

    @Test
    public void whatJsonForbidsIsRefused() {
        for (String bad : Arrays.asList("{\"a\": 1 \"b\": 2}", "{\"a\": 01}", "{a: 1}", "{\"a\": 1} 2",
                "{\"a\": \"\\q\"}", "{\"a\": 1, \"a\": 2}", "/* open", "[1, 2")) {
            assertThrows(bad, CgCodecException.class, () -> JsoncReader.read(bad, null));
        }
        CgCodecException e = assertThrows(CgCodecException.class, () -> JsoncReader.read("{\n\n\"a\": nope}", null));
        assertTrue(e.getMessage(), e.getMessage().contains("line 3"));
        assertThrows(CgCodecException.class, () -> CgFormats.JSONC.toText(CgCodecs.DOUBLE, Double.NaN));
    }

    @Test
    public void aCodecsCommentsAreWrittenAboveTheirKeys() {
        Config c = new Config();
        c.title = "Saga";
        c.volume = 0.5f;
        c.spells.add(new Spell("beam", 3));
        String text = CgFormats.JSONC.toText(Config.CODEC, c);
        assertTrue(text, text.startsWith("// mymod settings.\n\n{\n  // Shown in the menu.\n  \"title\": \"Saga\",\n"));
        assertTrue(text, text.contains("\n\n  // Master volume, 0 to 1.\n  \"volume\": 0.5,\n"));
        assertTrue(text, text.contains("      // Ki it takes.\n      \"cost\": 3\n"));

        Config back = CgFormats.JSONC.fromText(Config.CODEC, text);
        assertEquals("Saga", back.title);
        assertEquals(c.spells, back.spells);
        assertFalse("strict JSON writes none", CgFormats.JSON.toText(Config.CODEC, c).contains("//"));
        assertEquals("Saga", CgFormats.JSONC.fromText(Config.CODEC, CgFormats.JSON.toText(Config.CODEC, c)).title);
    }

    @Test
    public void aPersonsCommentsSurviveTheModWritingTheFileAgain() throws IOException {
        Path file = folder.newFile("mymod.jsonc").toPath();
        Files.write(file, ("// mine, on top\n"
                + "{\n"
                + "  /* why: the menu */\n"
                + "  \"title\": \"x\", // set by hand\n"
                + "  \"volume\": 0.2,\n"
                + "  // a key the mod no longer writes\n"
                + "  \"legacy\": 3\n"
                + "}\n"
                + "// the end\n").getBytes(StandardCharsets.UTF_8));
        Config live = CgFormats.JSONC.read(file, Config.CODEC, new Config());
        live.volume = 0.3f;
        CgFormats.JSONC.write(file, Config.CODEC, live);

        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(text, text.startsWith("// mine, on top\n\n{\n  /* why: the menu */\n  \"title\": \"x\", // set by hand\n"));
        assertTrue(text, text.contains("  // Master volume, 0 to 1.\n  \"volume\": 0.3,\n"));
        assertFalse(text, text.contains("no longer writes"));
        assertTrue(text, text.endsWith("}\n// the end\n"));
        assertEquals(0.3f, CgFormats.JSONC.read(file, Config.CODEC).volume, 0f);
    }
}
