package com.crystalgraphics.platform.input;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The GLFW key table is the one part of the 1.20.x platform seam that is pure arithmetic over two int
 * vocabularies, so it is the one part that can be proved without a game. A single wrong row is a key
 * that silently does something else for ever.
 */
public class CgGlfwKeyCodesTest {

    /** Highest GLFW key: GLFW_KEY_LAST is GLFW_KEY_MENU, 348. */
    private static final int GLFW_KEY_LAST = 348;

    /** Hand-checked against the GLFW header and CgKeyCodes. Literals on both sides on purpose. */
    @Test
    public void knownKeysTranslateToTheirDocumentedValues() {
        assertEquals(CgKeyCodes.KEY_A, CgGlfwKeyCodes.toCg(65));            // GLFW_KEY_A
        assertEquals(0x1E, CgGlfwKeyCodes.toCg(65));
        assertEquals(CgKeyCodes.KEY_ESCAPE, CgGlfwKeyCodes.toCg(256));      // GLFW_KEY_ESCAPE
        assertEquals(0x01, CgGlfwKeyCodes.toCg(256));
        assertEquals(CgKeyCodes.KEY_LCONTROL, CgGlfwKeyCodes.toCg(341));    // GLFW_KEY_LEFT_CONTROL
        assertEquals(0x1D, CgGlfwKeyCodes.toCg(341));
        assertEquals(CgKeyCodes.KEY_SPACE, CgGlfwKeyCodes.toCg(32));        // GLFW_KEY_SPACE
        assertEquals(CgKeyCodes.KEY_RETURN, CgGlfwKeyCodes.toCg(257));      // GLFW_KEY_ENTER
        assertEquals(CgKeyCodes.KEY_BACK, CgGlfwKeyCodes.toCg(259));        // GLFW_KEY_BACKSPACE
        assertEquals(CgKeyCodes.KEY_PRIOR, CgGlfwKeyCodes.toCg(266));       // GLFW_KEY_PAGE_UP
        assertEquals(CgKeyCodes.KEY_NUMPADENTER, CgGlfwKeyCodes.toCg(335)); // GLFW_KEY_KP_ENTER
    }

    /**
     * The digits are the row most likely to be typed wrong, because the two vocabularies disagree about
     * where zero goes: ASCII runs 0-9 and DirectInput runs 1-9 then 0.
     */
    @Test
    public void digitsAreNotOffByOne() {
        assertEquals(CgKeyCodes.KEY_0, CgGlfwKeyCodes.toCg(48));
        assertEquals(CgKeyCodes.KEY_1, CgGlfwKeyCodes.toCg(49));
        assertEquals(CgKeyCodes.KEY_9, CgGlfwKeyCodes.toCg(57));
        assertEquals(0x0B, CgGlfwKeyCodes.toCg(48));
        assertEquals(0x02, CgGlfwKeyCodes.toCg(49));
    }

    /** Round trip. This is also the injectivity check: two GLFW keys sharing a Cg value would fail it. */
    @Test
    public void everyMappedKeyRoundTrips() {
        int mapped = 0;
        for (int glfw = 0; glfw <= GLFW_KEY_LAST; glfw++) {
            int cg = CgGlfwKeyCodes.toCg(glfw);
            if (cg == CgKeyCodes.KEY_NONE) continue;
            mapped++;
            assertEquals("GLFW " + glfw + " -> Cg 0x" + Integer.toHexString(cg) + " -> GLFW",
                    glfw, CgGlfwKeyCodes.toGlfw(cg));
        }
        assertTrue("suspiciously few keys mapped: " + mapped, mapped > 100);
    }

    /**
     * Every CgKeyCodes constant is either mapped or named in UNMAPPED_CG. Without this the table can go
     * stale silently -- a key nobody listed and nobody mapped just stops working.
     */
    @Test
    public void everyCgKeyIsEitherMappedOrDeclaredUnmappable() {
        Set<Integer> unmapped = new HashSet<>();
        for (int cg : CgGlfwKeyCodes.UNMAPPED_CG) unmapped.add(cg);

        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Integer> key : cgKeyConstants().entrySet()) {
            int cg = key.getValue();
            boolean hasGlfw = CgGlfwKeyCodes.toGlfw(cg) != CgGlfwKeyCodes.GLFW_KEY_UNKNOWN;
            if (!hasGlfw && !unmapped.contains(cg)) {
                missing.add(key.getKey() + " (0x" + Integer.toHexString(cg) + ")");
            }
        }
        assertTrue("CgKeyCodes constants neither mapped nor listed in UNMAPPED_CG: " + missing,
                missing.isEmpty());
    }

    /** The counter-control: UNMAPPED_CG must not quietly grow to cover a key that IS mapped. */
    @Test
    public void nothingIsBothMappedAndDeclaredUnmappable() {
        List<String> both = new ArrayList<>();
        for (int cg : CgGlfwKeyCodes.UNMAPPED_CG) {
            if (cg != CgKeyCodes.KEY_NONE
                    && CgGlfwKeyCodes.toGlfw(cg) != CgGlfwKeyCodes.GLFW_KEY_UNKNOWN) {
                both.add("0x" + Integer.toHexString(cg));
            }
        }
        assertTrue("listed as unmappable but mapped: " + both, both.isEmpty());
    }

    @Test
    public void unknownAndOutOfRangeInputsAreAnswered() {
        assertEquals(CgKeyCodes.KEY_NONE, CgGlfwKeyCodes.toCg(-1));
        assertEquals(CgKeyCodes.KEY_NONE, CgGlfwKeyCodes.toCg(9999));
        assertEquals(CgKeyCodes.KEY_NONE, CgGlfwKeyCodes.toCg(200));   // GLFW leaves this gap empty
        assertEquals(CgGlfwKeyCodes.GLFW_KEY_UNKNOWN, CgGlfwKeyCodes.toGlfw(-1));
        assertEquals(CgGlfwKeyCodes.GLFW_KEY_UNKNOWN, CgGlfwKeyCodes.toGlfw(9999));
        assertEquals(CgGlfwKeyCodes.GLFW_KEY_UNKNOWN, CgGlfwKeyCodes.toGlfw(CgKeyCodes.KEY_NONE));
    }

    private static Map<String, Integer> cgKeyConstants() {
        Map<String, Integer> constants = new LinkedHashMap<>();
        for (Field field : CgKeyCodes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (field.getType() != int.class) continue;
            if (!field.getName().startsWith("KEY_")) continue;
            try {
                constants.put(field.getName(), field.getInt(null));
            } catch (IllegalAccessException e) {
                throw new AssertionError("cannot read " + field.getName(), e);
            }
        }
        assertTrue("no CgKeyCodes constants found", constants.size() > 100);
        return constants;
    }
}
