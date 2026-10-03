package com.crystalgraphics.serialization;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Raw bytes in the codec layer — added for the remote workspace, where file contents cross the wire.
 *
 * <p>Two implementations have to agree: {@link CgPlainOps} holds a {@code byte[]} natively, and the
 * {@link CgDynamicOps} default base64s through a string. The point of the pair is that callers cannot
 * tell.</p>
 */
public class CgDynamicOpsBytesTest {

    /** A textual ops with no native byte type — what {@code JsonOps} effectively is. */
    private static final CgDynamicOps<Object> TEXTUAL = new CgDynamicOps<>() {
        @Override public Object empty() { return null; }
        @Override public Object createString(String value) { return value; }
        @Override public Object createNumber(Number value) { return value; }
        @Override public Object createBoolean(boolean value) { return value; }
        @Override public Object createList(List<Object> values) { return values; }
        @Override public Object createMap(Map<Object, Object> entries) { return entries; }
        @Override public String getStringValue(Object value) {
            if (!(value instanceof String s)) throw new CgCodecException("Not a string: " + value);
            return s;
        }
        @Override public Number getNumberValue(Object value) {
            if (!(value instanceof Number n)) throw new CgCodecException("Not a number"); return n;
        }
        @Override public boolean getBooleanValue(Object value) {
            if (!(value instanceof Boolean b)) throw new CgCodecException("Not a boolean"); return b;
        }
        @SuppressWarnings("unchecked")
        @Override public List<Object> getListValue(Object value) {
            if (!(value instanceof List)) throw new CgCodecException("Not a list"); return (List<Object>) value;
        }
        @SuppressWarnings("unchecked")
        @Override public Map<Object, Object> getMapValue(Object value) {
            if (!(value instanceof Map)) throw new CgCodecException("Not a map"); return (Map<Object, Object>) value;
        }
    };

    private static byte[] sample() {
        byte[] bytes = new byte[512];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) (i * 31 + 7);
        return bytes;
    }

    /** <b>Both implementations round-trip identically.</b> That is the whole contract. */
    @Test
    public void bothOpsRoundTripBytes() {
        byte[] original = sample();

        assertArrayEquals("native", original, CgPlainOps.INSTANCE.getBytesValue(
                CgPlainOps.INSTANCE.createBytes(original)));
        assertArrayEquals("base64 default", original, TEXTUAL.getBytesValue(
                TEXTUAL.createBytes(original)));
    }

    /** Including the awkward ones — empty, and every possible byte value. */
    @Test
    public void edgeCasesRoundTrip() {
        byte[] empty = new byte[0];
        assertArrayEquals(empty, CgPlainOps.INSTANCE.getBytesValue(CgPlainOps.INSTANCE.createBytes(empty)));
        assertArrayEquals(empty, TEXTUAL.getBytesValue(TEXTUAL.createBytes(empty)));

        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) all[i] = (byte) i;
        assertArrayEquals(all, CgPlainOps.INSTANCE.getBytesValue(CgPlainOps.INSTANCE.createBytes(all)));
        assertArrayEquals(all, TEXTUAL.getBytesValue(TEXTUAL.createBytes(all)));
    }

    /** The textual default really is base64, so a value read as a string is the encoded form. */
    @Test
    public void theTextualDefaultIsBase64InAString() {
        Object encoded = TEXTUAL.createBytes("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals("aGVsbG8=", TEXTUAL.getStringValue(encoded));
    }

    /**
     * <b>{@link CgPlainOps} copies in and out.</b>
     *
     * <p>Every other value it handles is immutable. A shared array would let a caller mutate a tree it
     * had already encoded — and for a content-addressed value that means the hash and the bytes silently
     * stop agreeing.</p>
     */
    @Test
    public void plainOpsDoesNotShareTheArray() {
        byte[] original = { 1, 2, 3 };
        Object held = CgPlainOps.INSTANCE.createBytes(original);

        original[0] = 99;
        assertEquals("mutating the caller's array must not reach the tree",
                1, CgPlainOps.INSTANCE.getBytesValue(held)[0]);

        byte[] read = CgPlainOps.INSTANCE.getBytesValue(held);
        read[0] = 77;
        assertEquals("mutating what was read must not reach the tree either",
                1, CgPlainOps.INSTANCE.getBytesValue(held)[0]);
    }

    @Test
    public void nonBytesAreRejected() {
        try {
            CgPlainOps.INSTANCE.getBytesValue("not bytes");
            fail("expected a CgCodecException");
        } catch (CgCodecException expected) {
            // the point
        }
        try {
            TEXTUAL.getBytesValue("!!! not base64 !!!");
            fail("expected a CgCodecException");
        } catch (CgCodecException expected) {
            // the point
        }
    }

    // ── Content hashing ─────────────────────────────────────────────────────────────────────────

    /** Bytes are hashable, which they were not before — the probe chain had no case for them. */
    @Test
    public void bytesAreContentHashable() {
        Object a = CgPlainOps.INSTANCE.createBytes(sample());
        Object b = CgPlainOps.INSTANCE.createBytes(sample());
        assertEquals("equal bytes hash alike",
                CgContentHash.of(CgPlainOps.INSTANCE, a), CgContentHash.of(CgPlainOps.INSTANCE, b));

        byte[] different = sample();
        different[100] ^= 0xFF;
        assertNotEquals("different bytes do not",
                CgContentHash.of(CgPlainOps.INSTANCE, a),
                CgContentHash.of(CgPlainOps.INSTANCE, CgPlainOps.INSTANCE.createBytes(different)));
    }

    /**
     * <b>A byte block and the base64 string that denotes it must not hash alike.</b>
     *
     * <p>They are different values, and a content address that conflated them would let a document
     * containing the text {@code "aGVsbG8="} collide with one containing the five bytes of {@code hello}.
     * Hence a distinct tag rather than reusing the string tag.</p>
     */
    @Test
    public void bytesDoNotHashAsTheirBase64String() {
        byte[] raw = "hello".getBytes(StandardCharsets.UTF_8);
        String asBase64 = java.util.Base64.getEncoder().encodeToString(raw);

        assertNotEquals(
                CgContentHash.of(CgPlainOps.INSTANCE, CgPlainOps.INSTANCE.createBytes(raw)),
                CgContentHash.of(CgPlainOps.INSTANCE, CgPlainOps.INSTANCE.createString(asBase64)));
    }

    /**
     * <b>A plain string is never mistaken for bytes, even when it is valid base64.</b>
     *
     * <p>The trap the probe exists to avoid: the default {@code getBytesValue} decodes base64 from a
     * string, so a naive probe would report bytes for {@code "abcd"} — an ordinary word that happens to
     * decode — and hash it under the wrong tag. Strings that look like base64 are common.</p>
     */
    @Test
    public void aStringThatLooksLikeBase64StillHashesAsAString() {
        // "abcd" is valid base64 for three bytes, and is also just a word.
        String looksLikeBase64 = "abcd";
        String viaTextual = CgContentHash.of(TEXTUAL, TEXTUAL.createString(looksLikeBase64));
        String viaPlain = CgContentHash.of(CgPlainOps.INSTANCE, CgPlainOps.INSTANCE.createString(looksLikeBase64));

        assertEquals("the same string must hash the same under both ops", viaTextual, viaPlain);
    }

    /** And bytes survive inside a structure, which is how they will actually travel. */
    @Test
    public void bytesHashInsideAMap() {
        Object tree = CgPlainOps.INSTANCE.createMap(Map.of(
                CgPlainOps.INSTANCE.createString("path"), CgPlainOps.INSTANCE.createString("proj:a.png"),
                CgPlainOps.INSTANCE.createString("content"), CgPlainOps.INSTANCE.createBytes(sample())));
        assertTrue(CgContentHash.of(CgPlainOps.INSTANCE, tree).length() > 0);
    }
}
