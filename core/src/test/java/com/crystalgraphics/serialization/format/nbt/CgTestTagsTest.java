package com.crystalgraphics.serialization.format.nbt;

import com.crystalgraphics.serialization.CgCodec;
import com.crystalgraphics.serialization.CgCodecException;
import com.crystalgraphics.serialization.CgCodecs;
import com.crystalgraphics.serialization.format.CgFormats;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/** NBT in a unit test, under the game's rules. */
public class CgTestTagsTest {

    enum Kind { BEAM, DASH }

    record Sample(String s, int i, long l, double d, boolean b, byte[] bytes, List<Float> floats, Map<String, Kind> kinds) {
        static final CgCodec<Sample> CODEC = CgCodecs.record(
                CgCodecs.field("s", CgCodecs.STRING, Sample::s), CgCodecs.field("i", CgCodecs.INT, Sample::i),
                CgCodecs.field("l", CgCodecs.LONG, Sample::l), CgCodecs.field("d", CgCodecs.DOUBLE, Sample::d),
                CgCodecs.field("b", CgCodecs.BOOL, Sample::b), CgCodecs.field("bytes", CgCodecs.BYTES, Sample::bytes),
                CgCodecs.field("floats", CgCodecs.listOf(CgCodecs.FLOAT), Sample::floats),
                CgCodecs.field("kinds", CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.enumOf(Kind.class)), Sample::kinds),
                Sample::new);
    }

    record Owner(UUID id, Map<String, Integer> counts) {
        static final CgCodec<Owner> CODEC = CgCodecs.record(
                CgCodecs.field("id", CgCodecs.UUID, Owner::id),
                CgCodecs.field("counts", CgCodecs.mapOf(CgCodecs.STRING, CgCodecs.INT), Owner::counts),
                Owner::new);
    }

    private CgTestTags tags;

    @Before
    public void tags() {
        tags = CgTestTags.install();
    }

    @After
    public void untag() {
        tags.close();
    }

    @Test
    public void everyCodecKindRoundTripsThroughTags() {
        Map<String, Kind> kinds = new LinkedHashMap<>();
        kinds.put("first", Kind.BEAM);
        Sample sample = new Sample("Goku", 9, 1L << 40, 2.5, true, new byte[] {1, 2, 3}, Arrays.asList(0.5f, 1f), kinds);
        Object tag = CgFormats.toNbt(Sample.CODEC, sample);
        Sample back = CgFormats.fromNbt(Sample.CODEC, tag);
        assertEquals(sample.s(), back.s());
        assertEquals(sample.l(), back.l());
        assertTrue(back.b());
        assertArrayEquals(sample.bytes(), back.bytes());
        assertEquals(sample.floats(), back.floats());
        assertEquals(kinds, back.kinds());
        assertEquals("{s:\"Goku\",i:9,l:1099511627776L,d:2.5d,b:1b,bytes:[B;1b,2b,3b],floats:[0.5f,1.0f],"
                + "kinds:{first:\"BEAM\"}}", CgTestTags.toSnbt(tag));
    }

    @Test
    public void anEmptyMapIsAValueNotAnAbsence() {
        Owner owner = new Owner(new UUID(1, 2), Collections.emptyMap());
        assertEquals(owner, CgFormats.fromNbt(Owner.CODEC, CgFormats.toNbt(Owner.CODEC, owner)));
    }

    @Test
    public void aByteReadsAsABoolean() {
        Object preset = CgTestTags.parse("{s:\"x\",i:1,l:2L,d:0.5,b:1b,bytes:[B;],floats:[],kinds:{}}");
        assertTrue(CgFormats.fromNbt(Sample.CODEC, preset).b());
        assertEquals(true, CgFormats.fromNbt(CgCodecs.BOOL, CgTestTags.parse("true")));
    }

    @Test
    public void aListHoldsOneKindOfTag() {
        assertThrows(CgCodecException.class, () -> CgTestTags.parse("[1,\"two\"]"));
        Object list = tags.list();
        tags.add(list, 1);
        assertThrows(CgCodecException.class, () -> tags.add(list, 1L));
    }

    @Test
    public void snbtReadsEverySuffixAndPrintsItBack() {
        Object tag = CgTestTags.parse("{a:1b,b:2s,c:3,d:4L,e:5.5f,f:6.25d,g:\"two words\",h:[B;1b,-2b],i:[1,2],"
                + "j:{k:bare},\"l m\":'it\\'s'}");
        assertEquals("{a:1b,b:2s,c:3,d:4L,e:5.5f,f:6.25d,g:\"two words\",h:[B;1b,-2b],i:[1,2],j:{k:\"bare\"},"
                + "\"l m\":\"it's\"}", CgTestTags.toSnbt(tag));
        assertEquals(Arrays.asList(16711680, 255), CgTestTags.parse("[I; 16711680, 255]"));
        assertEquals(2.0, CgTestTags.parse("2."));
        assertEquals("300b", CgTestTags.parse("300b"));
        assertThrows(CgCodecException.class, () -> CgTestTags.parse("{a:1"));
        assertThrows(CgCodecException.class, () -> CgTestTags.parse("{a:1} extra"));
    }

    @Test
    public void closingPutsTheSlotBack() {
        tags.close();
        assertThrows(IllegalStateException.class, () -> CgFormats.toNbt(CgCodecs.INT, 1));
        tags = CgTestTags.install();
        assertEquals(1, (int) CgFormats.fromNbt(CgCodecs.INT, CgFormats.toNbt(CgCodecs.INT, 1)));
    }
}
