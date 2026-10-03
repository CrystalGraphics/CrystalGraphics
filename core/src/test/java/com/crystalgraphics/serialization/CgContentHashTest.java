package com.crystalgraphics.serialization;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * {@link CgContentHash} — the identity a client caches descriptions under.
 *
 * <p>Every property here is load-bearing for the cache. If the hash of an unchanged tree ever varies,
 * the cache silently never hits; if two different trees ever collide, a client is shown the wrong one.</p>
 */
public class CgContentHashTest {

    private static Map<Object, Object> sample() {
        Map<Object, Object> root = new LinkedHashMap<>();
        root.put("tag", "panel");
        root.put("class", List.of("panel", "dark"));
        root.put("checked", true);
        root.put("value", 4f);
        root.put("children", List.of(Map.of("tag", "text", "text", "Title")));
        return root;
    }

    private static String hash(Object tree) {
        return CgContentHash.of(CgPlainOps.INSTANCE, tree);
    }

    @Test
    public void anIdenticalTreeHashesIdentically() {
        assertEquals(hash(sample()), hash(sample()));
    }

    @Test
    public void anyMeaningfulChangeChangesTheHash() {
        String base = hash(sample());

        Map<Object, Object> differentValue = sample();
        differentValue.put("checked", false);
        assertNotEquals(base, hash(differentValue));

        Map<Object, Object> extraKey = sample();
        extraKey.put("id", "settings");
        assertNotEquals(base, hash(extraKey));
    }

    /** List order is meaningful — a child list decides paint and tab order. */
    @Test
    public void listOrderAffectsTheHash() {
        assertNotEquals(hash(List.of("one", "two")), hash(List.of("two", "one")));
    }

    /**
     * Two lists whose concatenated contents are identical but whose boundaries differ must not
     * collide. The type tag and element count already separate them; the length prefixes are defensive.
     */
    @Test
    public void listsWithTheSameContentButDifferentBoundariesDiffer() {
        assertNotEquals(hash(List.of("ab", "c")), hash(List.of("a", "bc")));
    }

    @Test
    public void mapKeyOrderDoesNotAffectTheHash() {
        Map<Object, Object> first = new LinkedHashMap<>();
        first.put("alpha", "1");
        first.put("beta", "2");

        Map<Object, Object> second = new LinkedHashMap<>();
        second.put("beta", "2");
        second.put("alpha", "1");

        assertEquals(hash(first), hash(second));
    }

    /** An int and a float of the same value are the same value, and must not re-transfer. */
    @Test
    public void numericRepresentationDoesNotAffectTheHash() {
        assertEquals(hash(Map.of("n", 3)), hash(Map.of("n", 3.0f)));
    }

    /** A string "3" is not the number 3 — type tags keep them apart. */
    @Test
    public void aStringIsNotItsNumber() {
        assertNotEquals(hash(Map.of("n", "3")), hash(Map.of("n", 3)));
    }

    @Test
    public void theHashIsAFullLengthSha256() {
        String hash = hash(sample());
        assertEquals("SHA-256 is 64 hex characters", 64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }
}
