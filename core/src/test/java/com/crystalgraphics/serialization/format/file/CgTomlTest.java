package com.crystalgraphics.serialization.format.file;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.CgDynamicOps;
import com.crystalgraphics.serialization.CgObjectCodec;
import com.crystalgraphics.serialization.CgPlainOps;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** TOML 1.0 read as the spec writes it, a codec's value written and read back, and comments kept. */
public class CgTomlTest {

    record Spell(String name, int cost) {
        static final CgCodec<Spell> CODEC = CgCodecs.record(
                CgCodecs.field("name", CgCodecs.STRING, Spell::name),
                CgCodecs.comment("Ki it takes.").field("cost", CgCodecs.INT, Spell::cost),
                Spell::new);
    }

    record Audio(float volume, boolean subtitles) {
        static final CgCodec<Audio> CODEC = CgCodecs.record(
                CgCodecs.comment("Master volume, 0 to 1.").field("volume", CgCodecs.FLOAT, Audio::volume),
                CgCodecs.field("subtitles", CgCodecs.BOOL, Audio::subtitles),
                Audio::new);
    }

    static class Config {
        String title = "";
        Audio audio = new Audio(1f, false);
        final List<Spell> spells = new ArrayList<>();

        static final CgObjectCodec<Config> CODEC = CgCodecs.object(Config::new)
                .comment("Shown in the menu.").field("title", CgCodecs.STRING, c -> c.title, (c, v) -> c.title = v)
                .comment("Sound.").field("audio", Audio.CODEC, c -> c.audio, (c, v) -> c.audio = v)
                .into("spells", CgCodecs.listOf(Spell.CODEC), c -> c.spells)
                .build();
    }

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private static Map<String, Object> read(String text) {
        return TomlReader.read(text, null);
    }

    @Test
    public void theSpecsValuesRead() {
        Map<String, Object> t = read(""
                + "int = +1_000\nhex = 0xDEAD_beef\noct = 0o755\nbin = 0b1101\nbig = 9_223_372_036_854_775_807\n"
                + "flt = -6.626e-34\nexp = 5e+22\ninf = -inf\nnan = nan\n"
                + "basic = \"tab\\there \\u00E9 \\U0001F600 \\\"q\\\"\"\n"
                + "literal = 'C:\\Users\\nodejs'\n"
                + "ml = \"\"\"\nRoses \\\n    are red\"\"\"\n"
                + "mll = '''\nfirst\nsecond'''\n"
                + "quotes = \"\"\"Here are two quotation marks: \"\". Simple enough.\"\"\"\n"
                + "odt = 1979-05-27T07:32:00Z\nldt = 1979-05-27 07:32:00.999\nld = 1979-05-27\nlt = 07:32:00\n"
                + "arr = [ 1, [2, 3], \"x\", { a = 1 }, ]\n"
                + "multi = [\n  1, # one\n  2,\n]\n");
        assertEquals(1000, t.get("int"));
        assertEquals(0xDEADBEEFL, t.get("hex"));
        assertEquals(493, t.get("oct"));
        assertEquals(13, t.get("bin"));
        assertEquals(Long.MAX_VALUE, t.get("big"));
        assertEquals(-6.626e-34, t.get("flt"));
        assertEquals(5e22, t.get("exp"));
        assertEquals(Double.NEGATIVE_INFINITY, t.get("inf"));
        assertTrue(Double.isNaN((Double) t.get("nan")));
        assertEquals("tab\there \u00E9 \uD83D\uDE00 \"q\"", t.get("basic"));
        assertEquals("C:\\Users\\nodejs", t.get("literal"));
        assertEquals("Roses are red", t.get("ml"));
        assertEquals("first\nsecond", t.get("mll"));
        assertEquals("Here are two quotation marks: \"\". Simple enough.", t.get("quotes"));
        assertEquals("1979-05-27T07:32:00Z", t.get("odt"));
        assertEquals("1979-05-27 07:32:00.999", t.get("ldt"));
        assertEquals("1979-05-27", t.get("ld"));
        assertEquals("07:32:00", t.get("lt"));
        assertEquals(4, ((List<?>) t.get("arr")).size());
        assertEquals(Arrays.asList(1, 2), t.get("multi"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void tablesDottedKeysAndArraysOfTablesNest() {
        Map<String, Object> t = read(""
                + "name.first = \"Goku\"\n"
                + "[fruit.apple]\ncolor = \"red\"\n"
                + "[fruit.apple.texture]\nsmooth = true\n"
                + "[fruit]\nkind = 1\n"
                + "[[spells]]\nname = \"beam\"\n"
                + "[spells.cost]\nki = 3\n"
                + "[[spells]]\nname = \"dash\"\n"
                + "[\"quoted key\".'lit']\nx = 1\n");
        assertEquals("Goku", ((Map<String, Object>) t.get("name")).get("first"));
        Map<String, Object> fruit = (Map<String, Object>) t.get("fruit");
        assertEquals(1, fruit.get("kind"));
        Map<String, Object> apple = (Map<String, Object>) fruit.get("apple");
        assertEquals(true, ((Map<String, Object>) apple.get("texture")).get("smooth"));
        List<Map<String, Object>> spells = (List<Map<String, Object>>) t.get("spells");
        assertEquals(2, spells.size());
        assertEquals(3, ((Map<String, Object>) spells.get(0).get("cost")).get("ki"));
        assertEquals("dash", spells.get(1).get("name"));
        assertEquals(1, ((Map<String, Object>) ((Map<String, Object>) t.get("quoted key")).get("lit")).get("x"));
    }

    @Test
    public void whatTheSpecForbidsIsRefused() {
        for (String bad : Arrays.asList(
                "a = 1\na = 2\n",                          // a key set twice
                "[a]\n[a]\n",                              // a table defined twice
                "a.b = 1\n[a]\n",                          // a table a dotted key already defined
                "a = { b = 1 }\n[a]\n",                    // an inline table added to
                "a = { b = 1, }\n",                        // a trailing comma in an inline table
                "a = [1]\n[[a]]\n",                        // a static array extended as a table array
                "a = 012\n",                               // a leading zero
                "a = \"line\nbreak\"\n",                   // a newline in a basic string
                "a = \"\\q\"\n",                           // an unknown escape
                "a = 1 b = 2\n",                           // two values on one line
                "a = 9_223_372_036_854_775_808\n",         // past 64 bits
                "a = \n",                                  // no value
                "= 1\n")) {                                // no key
            assertThrows(bad, CgCodecException.class, () -> read(bad));
        }
        CgCodecException e = assertThrows(CgCodecException.class, () -> read("a = 1\n\nb = 012\n"));
        assertTrue(e.getMessage(), e.getMessage().contains("line 3"));
    }

    @Test
    public void aCodecsValueIsWrittenAsTablesAndReadBack() {
        Config c = new Config();
        c.title = "Saiyan \"Saga\"";
        c.audio = new Audio(0.5f, true);
        c.spells.add(new Spell("beam", 3));
        c.spells.add(new Spell("dash", 1));
        String text = CgFormats.TOML.toText(Config.CODEC, c);
        assertTrue(text, text.startsWith("# Shown in the menu.\ntitle = \"Saiyan \\\"Saga\\\"\"\n"));
        assertTrue(text, text.contains("\n# Sound.\n[audio]\n# Master volume, 0 to 1.\nvolume = 0.5\nsubtitles = true\n"));
        assertTrue(text, text.contains("\n[[spells]]\nname = \"beam\"\n"));

        Config back = CgFormats.TOML.fromText(Config.CODEC, text);
        assertEquals(c.title, back.title);
        assertEquals(c.audio, back.audio);
        assertEquals(c.spells, back.spells);
    }

    @Test
    public void aPersonsCommentsSurviveTheModWritingTheFileAgain() throws IOException {
        Path file = folder.newFile("mymod.toml").toPath();
        Files.write(file, ("# my own title note\n"
                + "title = \"x\"   # set by hand\n"
                + "\n"
                + "[audio]   # all sound\n"
                + "volume = 0.2\n"
                + "subtitles = false\n"
                + "# about a key the mod no longer writes\n"
                + "legacy = 3\n"
                + "\n"
                + "[[spells]]\n"
                + "name = \"beam\"\n"
                + "cost = 3   # cheap\n"
                + "\n"
                + "# the end\n").getBytes(StandardCharsets.UTF_8));
        Config live = CgFormats.TOML.read(file, Config.CODEC, new Config());
        live.audio = new Audio(0.3f, true);
        CgFormats.TOML.write(file, Config.CODEC, live);

        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        assertTrue(text, text.startsWith("# my own title note\ntitle = \"x\"  # set by hand\n"));   // theirs, over ours
        assertTrue(text, text.contains("[audio]  # all sound\n# Master volume, 0 to 1.\nvolume = 0.3\n"));
        assertTrue(text, text.contains("cost = 3  # cheap\n"));
        assertFalse(text, text.contains("no longer writes"));
        assertTrue(text, text.endsWith("\n# the end\n"));
        assertEquals(new Audio(0.3f, true), CgFormats.TOML.read(file, Config.CODEC).audio);
    }

    /** {@code a = value}, its comment written by hand; the codec's own comment is the file's header. */
    static final CgCodec<Integer> HEADED = new CgCodec<Integer>() {
        @Override
        public <T> T encode(CgDynamicOps<T> ops, Integer value) {
            return CgCodecs.map(ops)
                    .comment("Set by hand.").field("a", CgCodecs.INT, value)
                    .comment("Never written: its key is omitted.").optional("b", CgCodecs.INT, 0, 0)
                    .build();
        }

        @Override
        public <T> Integer decode(CgDynamicOps<T> ops, T input) {
            return CgCodecs.read(ops, input).field("a", CgCodecs.INT);
        }
    }.comment("mymod settings.");

    @Test
    public void aHeaderIsTheBlockAboveTheFirstKeyWithABlankLineAfterIt() throws IOException {
        assertEquals("# mymod settings.\n\n# Set by hand.\na = 1\n", CgFormats.TOML.toText(HEADED, 1));

        Path file = folder.newFile("headed.toml").toPath();
        Files.write(file, "# mine\n\n# on a\na = 1\n".getBytes(StandardCharsets.UTF_8));
        CgFormats.TOML.write(file, HEADED, 2);
        assertEquals("# mine\n\n# on a\na = 2\n", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
    }

    @Test
    public void aNestedValuesOwnCommentGoesAboveItsSection() {
        CgCodec<Audio> sound = Audio.CODEC.comment("Sound.");
        CgCodec<Audio> holder = CgCodecs.record(CgCodecs.field("audio", sound, a -> a), a -> a);
        String text = CgFormats.TOML.toText(holder, new Audio(1f, false));
        assertTrue(text, text.startsWith("# Sound.\n[audio]\n# Master volume, 0 to 1.\nvolume = 1.0\n"));
    }

    @Test
    public void aKeyInsideAnArrayOfTablesTakesItsComment() {
        CgCodec<List<Spell>> list = CgCodecs.record(CgCodecs.field("spells", CgCodecs.listOf(Spell.CODEC), l -> l),
                l -> l);
        String text = CgFormats.TOML.toText(list, Arrays.asList(new Spell("beam", 3), new Spell("dash", 1)));
        assertEquals(text, 2, text.split("# Ki it takes.", -1).length - 1);
    }

    @Test
    public void aCommentWithNoKeyAfterItSaysSo() {
        assertThrows(IllegalStateException.class, () -> CgCodecs.map(CgPlainOps.INSTANCE).comment("dangling").build());
        assertThrows(IllegalStateException.class, () -> CgCodecs.object(Config::new).comment("dangling").build());
    }

    @Test
    public void whatTomlCannotHoldSaysSo() {
        assertThrows(CgCodecException.class, () -> CgFormats.TOML.toText(CgCodecs.INT, 3));
        assertThrows(CgCodecException.class,
                () -> CgFormats.TOML.toText(CgCodecs.mapOf(CgCodecs.DOUBLE, CgCodecs.INT), Collections.singletonMap(1.5, 2)));
    }

    @Test
    public void anArrayIsWrittenInlineAndATableUnderItsHeader() {
        String text = "a = [[1, 2], []]\nm = [{ x = 1 }, 2, {}]\n\n[b]\nc = 1.0\n\n[b.d]\n";
        assertEquals(text, TomlWriter.write(read(text), CgPlainOps.INSTANCE, null));
    }
}
