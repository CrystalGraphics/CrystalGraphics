package com.crystalgraphics.serialization;

import com.crystalgraphics.serialization.format.CgFormats;
import com.crystalgraphics.serialization.format.CgJsonOps;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** A record's codec from its keys: both directions read one declaration. */
public class CgRecordCodecTest {

    record Spell(String name, int cost, String icon) {
        static final CgCodec<Spell> CODEC = CgCodecs.record(
                CgCodecs.field("name", CgCodecs.STRING, Spell::name),
                CgCodecs.optionalField("cost", CgCodecs.INT, Spell::cost, 0),
                CgCodecs.optionalField("icon", CgCodecs.STRING, Spell::icon, null),
                Spell::new);
    }

    record Tag(String id) {
        static final CgCodec<Tag> CODEC = CgCodecs.record(CgCodecs.field("id", CgCodecs.STRING, Tag::id), Tag::new);
    }

    record Wide(int a, int b, int c, int d, String e, boolean f, double g, List<String> h) {
        static final CgCodec<Wide> CODEC = CgCodecs.record(
                CgCodecs.field("a", CgCodecs.INT, Wide::a), CgCodecs.field("b", CgCodecs.INT, Wide::b),
                CgCodecs.field("c", CgCodecs.INT, Wide::c), CgCodecs.field("d", CgCodecs.INT, Wide::d),
                CgCodecs.field("e", CgCodecs.STRING, Wide::e), CgCodecs.field("f", CgCodecs.BOOL, Wide::f),
                CgCodecs.field("g", CgCodecs.DOUBLE, Wide::g),
                CgCodecs.optionalField("h", CgCodecs.listOf(CgCodecs.STRING), Wide::h, Collections.emptyList()),
                Wide::new);
    }

    record Widest(int a, int b, int c, int d, int e, int f, int g, int h, int i, int j, int k, int l, int m, int n,
                  int o, String p) {
        static final CgCodec<Widest> CODEC = CgCodecs.record(
                CgCodecs.field("a", CgCodecs.INT, Widest::a), CgCodecs.field("b", CgCodecs.INT, Widest::b),
                CgCodecs.field("c", CgCodecs.INT, Widest::c), CgCodecs.field("d", CgCodecs.INT, Widest::d),
                CgCodecs.field("e", CgCodecs.INT, Widest::e), CgCodecs.field("f", CgCodecs.INT, Widest::f),
                CgCodecs.field("g", CgCodecs.INT, Widest::g), CgCodecs.field("h", CgCodecs.INT, Widest::h),
                CgCodecs.field("i", CgCodecs.INT, Widest::i), CgCodecs.field("j", CgCodecs.INT, Widest::j),
                CgCodecs.field("k", CgCodecs.INT, Widest::k), CgCodecs.field("l", CgCodecs.INT, Widest::l),
                CgCodecs.field("m", CgCodecs.INT, Widest::m), CgCodecs.field("n", CgCodecs.INT, Widest::n),
                CgCodecs.field("o", CgCodecs.INT, Widest::o), CgCodecs.optionalField("p", CgCodecs.STRING, Widest::p, ""),
                Widest::new);
    }

    @Test
    public void everyArityRoundTrips() {
        Tag tag = new Tag("kamehameha");
        assertEquals(tag, Tag.CODEC.decode(CgPlainOps.INSTANCE, Tag.CODEC.encode(CgPlainOps.INSTANCE, tag)));
        Wide wide = new Wide(1, 2, 3, 4, "five", true, 7.5, Arrays.asList("eight"));
        assertEquals(wide, Wide.CODEC.decode(CgPlainOps.INSTANCE, Wide.CODEC.encode(CgPlainOps.INSTANCE, wide)));
        assertEquals(wide, CgFormats.JSON.fromText(Wide.CODEC, CgFormats.JSON.toText(Wide.CODEC, wide)));
        Widest widest = new Widest(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, "sixteen");
        Map<?, ?> tree = (Map<?, ?>) Widest.CODEC.encode(CgPlainOps.INSTANCE, widest);
        assertArrayEquals("a flat save, in declaration order", "abcdefghijklmnop".split(""), tree.keySet().toArray());
        assertEquals(widest, Widest.CODEC.decode(CgPlainOps.INSTANCE, tree));
    }

    record Group(String file, String group) {
        static final CgRecordCodec<Group> CODEC = CgCodecs.record(
                CgCodecs.comment("The animation file.").field("File", CgCodecs.STRING, Group::file),
                CgCodecs.optionalField("Group", CgCodecs.STRING, Group::group, "idle"),
                Group::new);
    }

    record Known(String style, Group group) {
        static final CgCodec<Known> CODEC = CgCodecs.record(
                CgCodecs.field("Style", CgCodecs.STRING, Known::style),
                CgCodecs.inline(Group.CODEC, Known::group),
                Known::new);
    }

    @Test
    public void anInlinedRecordsKeysSitBesideTheOuterOnes() {
        Known known = new Known("ssj", new Group("anim.json", "fly"));
        Map<?, ?> tree = (Map<?, ?>) Known.CODEC.encode(CgPlainOps.INSTANCE, known);
        assertArrayEquals(new Object[] {"Style", "File", "Group"}, tree.keySet().toArray());
        assertEquals(known, Known.CODEC.decode(CgPlainOps.INSTANCE, tree));
        assertEquals("the inlined record's optional key", new Known("base", new Group("a.json", "idle")),
                CgFormats.JSON.fromText(Known.CODEC, "{\"Style\":\"base\",\"File\":\"a.json\"}"));
        assertTrue("its keys' comments come along",
                CgFormats.YAML.toText(Known.CODEC, known).contains("# The animation file.\nFile:"));
        assertThrows(IllegalArgumentException.class, () -> CgCodecs.record(
                CgCodecs.field("File", CgCodecs.STRING, Known::style), CgCodecs.inline(Group.CODEC, Known::group),
                Known::new));
    }

    enum Side {
        LEFT, RIGHT;

        String key() {
            return name().toLowerCase();
        }
    }

    @Test
    public void eachConstantIsAKeyOfItsOwn() {
        CgRecordCodec<Map<Side, Integer>> strands = CgCodecs.eachOf(Side.class, Side::key, CgCodecs.INT, null);
        Map<Side, Integer> both = new EnumMap<>(Side.class);
        both.put(Side.LEFT, 3);
        both.put(Side.RIGHT, 5);
        Map<?, ?> tree = (Map<?, ?>) strands.encode(CgPlainOps.INSTANCE, both);
        assertArrayEquals(new Object[] {"left", "right"}, tree.keySet().toArray());
        assertEquals(both, strands.decode(CgPlainOps.INSTANCE, tree));
        assertEquals("absent, with no fallback, the map lacks it", Collections.singletonMap(Side.RIGHT, 5),
                strands.decode(CgPlainOps.INSTANCE, Collections.singletonMap("right", 5)));
        CgRecordCodec<Map<Side, Boolean>> flags = CgCodecs.eachOf(Side.class, Side::key, CgCodecs.BOOL, false);
        Map<Side, Boolean> one = new EnumMap<>(Side.class);
        one.put(Side.RIGHT, true);
        assertEquals("the fallback is not written", Collections.singleton("right"),
                ((Map<?, ?>) flags.encode(CgPlainOps.INSTANCE, one)).keySet());
        assertEquals(Boolean.FALSE, flags.decode(CgPlainOps.INSTANCE, Collections.emptyMap()).get(Side.LEFT));
    }

    record Cost(String name, int cost) {
        static final CgCodec<Cost> LENIENT = CgCodecs.record(
                CgCodecs.field("name", CgCodecs.STRING, Cost::name),
                CgCodecs.optionalField("cost", CgCodecs.INT, Cost::cost, 1).lenient(),
                Cost::new);
    }

    @Test
    public void aLenientKeyThatCannotBeReadReadsItsFallback() {
        assertEquals("a cost saved in a shape no longer read", new Cost("beam", 1),
                Cost.LENIENT.decode(CgJsonOps.INSTANCE, CgJsonOps.fromText("{\"name\":\"beam\",\"cost\":{\"ki\":3}}")));
        assertEquals(new Cost("beam", 4), Cost.LENIENT.decode(CgJsonOps.INSTANCE, CgJsonOps.fromText("{\"name\":\"beam\",\"cost\":4}")));
        assertThrows("a required key has no fallback to read", IllegalStateException.class,
                () -> CgCodecs.field("name", CgCodecs.STRING, Cost::name).lenient());
    }

    @Test
    public void anOptionalAtItsFallbackIsNotWrittenAndAbsentReadsIt() {
        Spell plain = new Spell("ki blast", 0, null);
        Map<?, ?> tree = (Map<?, ?>) Spell.CODEC.encode(CgPlainOps.INSTANCE, plain);
        assertEquals(Collections.singleton("name"), tree.keySet());
        assertEquals(plain, Spell.CODEC.decode(CgPlainOps.INSTANCE, tree));
        assertEquals(new Spell("ki blast", 0, null), CgFormats.JSON.fromText(Spell.CODEC, "{\"name\":\"ki blast\",\"icon\":null}"));
    }

    record Prop(String id, boolean exposed) {
        static final CgCodec<Prop> CODEC = CgCodecs.record(
                CgCodecs.field("id", CgCodecs.STRING, Prop::id),
                CgCodecs.fieldOr("exposed", CgCodecs.BOOL, Prop::exposed, true),
                Prop::new);
    }

    @Test
    public void aFieldOrIsAlwaysWrittenAndAbsentReadsItsFallback() {
        Map<?, ?> tree = (Map<?, ?>) Prop.CODEC.encode(CgPlainOps.INSTANCE, new Prop("p", true));
        assertEquals(Boolean.TRUE, tree.get("exposed"));
        assertEquals(new Prop("p", true), CgFormats.JSON.fromText(Prop.CODEC, "{\"id\":\"p\"}"));
    }

    @Test
    public void keysAreWrittenInDeclarationOrder() {
        Map<?, ?> tree = (Map<?, ?>) Spell.CODEC.encode(CgPlainOps.INSTANCE, new Spell("beam", 5, "beam.png"));
        assertArrayEquals(new Object[] {"name", "cost", "icon"}, tree.keySet().toArray());
    }

    @Test
    public void whatCannotBeReadOrWrittenNamesItsKey() {
        CgCodecException missing = assertThrows(CgCodecException.class,
                () -> Spell.CODEC.decode(CgJsonOps.INSTANCE, CgJsonOps.fromText("{\"cost\":3}")));
        assertTrue(missing.getMessage(), missing.getMessage().contains("'name'"));
        CgCodecException wrong = assertThrows(CgCodecException.class,
                () -> Spell.CODEC.decode(CgJsonOps.INSTANCE, CgJsonOps.fromText("{\"name\":\"x\",\"cost\":\"three\"}")));
        assertTrue(wrong.getMessage(), wrong.getMessage().startsWith("'cost'"));
        CgCodecException nullRequired = assertThrows(CgCodecException.class,
                () -> Tag.CODEC.encode(CgPlainOps.INSTANCE, new Tag(null)));
        assertTrue(nullRequired.getMessage(), nullRequired.getMessage().contains("'id'"));
    }

    @Test
    public void undeclaredKeysAreIgnoredAndADuplicateKeyIsRefused() {
        Map<String, Object> tree = new LinkedHashMap<>();
        tree.put("id", "x");
        tree.put("removed", 3);
        assertEquals(new Tag("x"), Tag.CODEC.decode(CgPlainOps.INSTANCE, tree));
        assertThrows(IllegalArgumentException.class, () -> CgCodecs.record(
                CgCodecs.field("id", CgCodecs.STRING, Tag::id), CgCodecs.field("id", CgCodecs.STRING, Tag::id),
                (a, b) -> new Tag(a)));
    }
}
