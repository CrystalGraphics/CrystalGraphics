package com.crystalgraphics.platform.input;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** The SDL3 key table, proved without a game as the GLFW one is. @see CgGlfwKeyCodesTest */
public class CgSdlKeyCodesTest {

    /** Minecraft 26.3's InputConstants, read from its jar. Literals on both sides on purpose. */
    @Test
    public void minecraftsOwnScancodesTranslate() {
        assertEquals(CgKeyCodes.KEY_A, CgSdlKeyCodes.toCg(4));          // KEY_A
        assertEquals(CgKeyCodes.KEY_ESCAPE, CgSdlKeyCodes.toCg(41));    // KEY_ESCAPE
        assertEquals(CgKeyCodes.KEY_F3, CgSdlKeyCodes.toCg(60));        // KEY_F3
        assertEquals(CgKeyCodes.KEY_LEFT, CgSdlKeyCodes.toCg(80));      // KEY_LEFT
        assertEquals(CgKeyCodes.KEY_LSHIFT, CgSdlKeyCodes.toCg(225));   // KEY_LSHIFT
    }

    /** HID puts zero after nine, as DirectInput does -- the one row where the two agree. */
    @Test
    public void digitsAreNotOffByOne() {
        assertEquals(CgKeyCodes.KEY_1, CgSdlKeyCodes.toCg(30));
        assertEquals(CgKeyCodes.KEY_9, CgSdlKeyCodes.toCg(38));
        assertEquals(CgKeyCodes.KEY_0, CgSdlKeyCodes.toCg(39));
    }

    @Test
    public void everyMappedKeyRoundTrips() {
        int mapped = 0;
        for (int sdl = 0; sdl < 512; sdl++) {
            int cg = CgSdlKeyCodes.toCg(sdl);
            if (cg == CgKeyCodes.KEY_NONE) continue;
            mapped++;
            assertEquals("SDL " + sdl + " -> Cg 0x" + Integer.toHexString(cg) + " -> SDL", sdl, CgSdlKeyCodes.toSdl(cg));
        }
        assertTrue("suspiciously few keys mapped: " + mapped, mapped > 100);
    }

    @Test
    public void everyCgKeyIsEitherMappedOrDeclaredUnmappable() {
        Set<Integer> unmapped = new HashSet<>();
        for (int cg : CgSdlKeyCodes.UNMAPPED_CG) unmapped.add(cg);
        List<String> missing = new ArrayList<>();
        List<String> both = new ArrayList<>();
        for (Field field : CgKeyCodes.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != int.class
                    || !field.getName().startsWith("KEY_")) continue;
            int cg;
            try {
                cg = field.getInt(null);
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
            boolean mapped = CgSdlKeyCodes.toSdl(cg) != CgSdlKeyCodes.SDL_SCANCODE_UNKNOWN;
            if (!mapped && !unmapped.contains(cg)) missing.add(field.getName());
            if (mapped && unmapped.contains(cg)) both.add(field.getName());
        }
        assertTrue("neither mapped nor listed in UNMAPPED_CG: " + missing, missing.isEmpty());
        assertTrue("listed as unmappable but mapped: " + both, both.isEmpty());
    }

    /** SDL numbers 1..5 with middle second; the engine 0..4 with right second. */
    @Test
    public void mouseButtonsTranslateBothWays() {
        assertEquals(CgMouseCodes.LEFT_BUTTON, CgSdlKeyCodes.mouseToCg(1));
        assertEquals(CgMouseCodes.MIDDLE_BUTTON, CgSdlKeyCodes.mouseToCg(2));
        assertEquals(CgMouseCodes.RIGHT_BUTTON, CgSdlKeyCodes.mouseToCg(3));
        assertEquals(CgMouseCodes.NONE, CgSdlKeyCodes.mouseToCg(0));
        for (int sdl = 1; sdl <= 5; sdl++) {
            assertEquals(sdl, CgSdlKeyCodes.mouseToSdl(CgSdlKeyCodes.mouseToCg(sdl)));
        }
    }

    @Test
    public void unknownAndOutOfRangeInputsAreAnswered() {
        assertEquals(CgKeyCodes.KEY_NONE, CgSdlKeyCodes.toCg(-1));
        assertEquals(CgKeyCodes.KEY_NONE, CgSdlKeyCodes.toCg(9999));
        assertEquals(CgKeyCodes.KEY_NONE, CgSdlKeyCodes.toCg(0));
        assertEquals(CgSdlKeyCodes.SDL_SCANCODE_UNKNOWN, CgSdlKeyCodes.toSdl(-1));
        assertEquals(CgSdlKeyCodes.SDL_SCANCODE_UNKNOWN, CgSdlKeyCodes.toSdl(CgKeyCodes.KEY_NONE));
    }
}
