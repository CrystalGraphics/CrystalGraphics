package com.crystalgraphics.util;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * P6.3.1 — content hashing.
 *
 * <p>Small, but it is a cache <b>key</b>, and a wrong key here does not fail loudly: it binds one
 * generated shader to another one's compiled program and renders something plausible.</p>
 */
public class CgContentHashTest {

    @Test
    public void theSameContentAlwaysHashesTheSame() {
        assertEquals(CgContentHash.of("void main() { }"), CgContentHash.of("void main() { }"));
    }

    /** The case that actually matters: machine-generated sources differing by one constant. */
    @Test
    public void aSingleCharacterDifferenceChangesTheHash() {
        assertNotEquals(CgContentHash.of("fragColor = vec4(1.0);"),
                CgContentHash.of("fragColor = vec4(0.0);"));
    }

    @Test
    public void theHashIsSixteenLowercaseHexCharacters() {
        String hash = CgContentHash.of("anything at all");
        assertEquals(16, hash.length());
        assertTrue("expected lowercase hex, got: " + hash, hash.matches("[0-9a-f]{16}"));
    }

    /** Pinned literally, because "stable across runs and JVMs" is the property being claimed and a
     * regression in it would otherwise only show up as caches quietly missing. */
    @Test
    public void theHashIsPinnedRatherThanMerelySelfConsistent() {
        assertEquals("e3b0c44298fc1c14", CgContentHash.of(""));
    }

    @Test
    public void nullIsRefused() {
        try {
            CgContentHash.of(null);
            fail("expected null content to be refused");
        } catch (IllegalArgumentException expected) {
            // a null key would collide with every other null key
        }
    }
}
