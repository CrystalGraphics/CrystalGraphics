package com.crystalgraphics.serialization;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The wire encoding, in {@code headlessTest} because that is where the server contract lives — this runs
 * with no GL, no CrystalGraphics core and no Minecraft, which is the same set a dedicated server has.
 */
public class CgBinaryFormatTest {

    private static Object roundTrip(Object tree) {
        return CgBinaryFormat.decode(CgBinaryFormat.encode(tree));
    }

    @Test
    public void everyPlainOpsTypeSurvivesARoundTrip() {
        assertNull(roundTrip(null));
        assertEquals(Boolean.TRUE, roundTrip(true));
        assertEquals(Boolean.FALSE, roundTrip(false));
        assertEquals("hello", roundTrip("hello"));
        assertEquals(1.5f, (Float) roundTrip(1.5f), 0f);
        assertEquals(1.5d, (Double) roundTrip(1.5d), 0d);
        assertArrayEquals(new byte[] {1, 2, 3}, (byte[]) roundTrip(new byte[] {1, 2, 3}));
    }

    /**
     * The subtle one, and the reason every numeric box has its own tag.
     *
     * <p>A {@code CgPlainOps} tree holds {@code Object}, so a codec reading a field back sees the runtime
     * class. Collapsing every integer to {@code Long} on the way out — which is what a JSON round trip
     * does — makes "the same tree" true of the values and false of the types, and it fails at some
     * reader's cast rather than at the encoder.</p>
     */
    @Test
    public void numericWidthIsPreservedRatherThanCollapsed() {
        assertSame(Byte.class, roundTrip((byte) 7).getClass());
        assertSame(Short.class, roundTrip((short) 7).getClass());
        assertSame(Integer.class, roundTrip(7).getClass());
        assertSame(Long.class, roundTrip(7L).getClass());
        assertSame(Float.class, roundTrip(7f).getClass());
        assertSame(Double.class, roundTrip(7d).getClass());

        assertEquals(7, roundTrip(7));
        assertEquals(7L, roundTrip(7L));
    }

    @Test
    public void signedAndBoundaryNumbersSurvive() {
        for (int value : new int[] {0, 1, -1, 127, -128, 32767, -32768,
                Integer.MAX_VALUE, Integer.MIN_VALUE}) {
            assertEquals("int " + value, value, roundTrip(value));
        }
        for (long value : new long[] {0L, -1L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            assertEquals("long " + value, value, roundTrip(value));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void mapOrderIsPreserved() {
        // Content addressing depends on it: CgContentHash canonicalises a tree, so an encoding whose
        // iteration order varied would give one tree two hashes.
        Map<Object, Object> source = new LinkedHashMap<>();
        source.put("zebra", 1);
        source.put("apple", 2);
        source.put("mango", 3);

        Map<Object, Object> decoded = (Map<Object, Object>) roundTrip(source);
        assertEquals(new ArrayList<>(source.keySet()), new ArrayList<>(decoded.keySet()));
        // And re-encoding is byte-identical, which is the property that actually matters.
        assertArrayEquals(CgBinaryFormat.encode(source), CgBinaryFormat.encode(decoded));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void nestedStructuresSurvive() {
        Map<Object, Object> inner = new LinkedHashMap<>();
        inner.put("id", 42);
        inner.put("tags", Arrays.asList("a", "b"));

        List<Object> tree = Arrays.asList("head", inner, null, new byte[] {9});

        List<Object> decoded = (List<Object>) roundTrip(tree);
        assertEquals("head", decoded.get(0));
        assertEquals(42, ((Map<Object, Object>) decoded.get(1)).get("id"));
        assertNull(decoded.get(2));
        assertArrayEquals(new byte[] {9}, (byte[]) decoded.get(3));
    }

    @Test
    public void aStringLongerThanWriteUtfCouldCarrySurvives() {
        // writeUTF caps at 65,535 bytes, which a file this protocol exists to carry reaches easily.
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 100_000; i++) big.append('x');
        assertEquals(big.toString(), roundTrip(big.toString()));
    }

    @Test
    public void nonAsciiSurvives() {
        assertEquals("héllo — 日本語 🎨", roundTrip("héllo — 日本語 🎨"));
    }

    // ── The failure modes that matter once bytes come off a network ─────────

    @Test
    public void anUnencodableTypeIsRefusedByName() {
        try {
            CgBinaryFormat.encode(new java.util.Date());
            fail("expected a refusal");
        } catch (CgCodecException expected) {
            assertTrue("names the offending type: " + expected.getMessage(),
                    expected.getMessage().contains("java.util.Date"));
        }
    }

    @Test(expected = CgCodecException.class)
    public void anUnknownTagIsRefused() {
        CgBinaryFormat.decode(new byte[] {(byte) 0x7F});
    }

    @Test(expected = CgCodecException.class)
    public void truncatedInputIsRefused() {
        byte[] whole = CgBinaryFormat.encode("a reasonably long string");
        CgBinaryFormat.decode(Arrays.copyOf(whole, whole.length - 4));
    }

    @Test(expected = CgCodecException.class)
    public void trailingBytesAreRefused() {
        // A partial parse that ignored a tail would turn a version mismatch into a wrong tree.
        byte[] whole = CgBinaryFormat.encode(1);
        byte[] padded = Arrays.copyOf(whole, whole.length + 1);
        CgBinaryFormat.decode(padded);
    }

    /**
     * A length the sender asks for but did not supply must not become an allocation.
     *
     * <p>Five bytes of varint can request two gigabytes. Reading is bounded by what actually arrived, so
     * refusing a length larger than the remaining input costs nothing and removes the class of attack.</p>
     */
    @Test(expected = CgCodecException.class)
    public void aHostileLengthIsRefusedBeforeAllocating() {
        // TAG_BYTES followed by a varint for ~2 GB, and no data behind it.
        byte[] hostile = new byte[] {0x0A, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0x07};
        CgBinaryFormat.decode(hostile);
    }

    /**
     * Unbounded nesting is a few hundred bytes that overflows the stack of whoever drains the connection.
     */
    @Test(expected = CgCodecException.class)
    public void runawayNestingIsRefusedRatherThanOverflowingTheStack() {
        byte[] bomb = new byte[4096];
        for (int i = 0; i < bomb.length; i += 2) {
            bomb[i] = 0x0B;     // LIST
            bomb[i + 1] = 0x01; // holding exactly one element
        }
        CgBinaryFormat.decode(bomb);
    }

    @Test
    public void anEmptyListAndMapAreDistinctFromNull() {
        assertEquals(new ArrayList<>(), roundTrip(new ArrayList<>()));
        assertEquals(new LinkedHashMap<>(), roundTrip(new LinkedHashMap<>()));
        assertNotNull(roundTrip(new ArrayList<>()));
    }
}
