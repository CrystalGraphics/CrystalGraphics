package com.crystalgraphics.serialization;

import com.crystalgraphics.serialization.format.CgFormats;
import com.crystalgraphics.serialization.format.CgJsonOps;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
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

    @Test
    public void everyArityRoundTrips() {
        Tag tag = new Tag("kamehameha");
        assertEquals(tag, Tag.CODEC.decode(CgPlainOps.INSTANCE, Tag.CODEC.encode(CgPlainOps.INSTANCE, tag)));
        Wide wide = new Wide(1, 2, 3, 4, "five", true, 7.5, Arrays.asList("eight"));
        assertEquals(wide, Wide.CODEC.decode(CgPlainOps.INSTANCE, Wide.CODEC.encode(CgPlainOps.INSTANCE, wide)));
        assertEquals(wide, CgFormats.JSON.fromText(Wide.CODEC, CgFormats.JSON.toText(Wide.CODEC, wide)));
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
