package com.crystalgraphics.api.font;

import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link CgFontFamilyGroup} — style-keyed family resolution with
 * fallback-to-regular. Uses a real loaded font (native FreeType/HarfBuzz) rather than a
 * mock, matching {@link CgFontKeyTest}'s established pattern for this test module.
 *
 * <p>Every {@link CgFont} created via {@link #familyOf} is tracked and disposed in
 * {@link #disposeCreatedFonts}, so native memory is released here rather than left to finalizers.</p>
 */
public class CgFontFamilyGroupTest {

    private final List<CgFont> createdFonts = new ArrayList<>();

    @After
    public void disposeCreatedFonts() {
        for (CgFont font : createdFonts) {
            font.dispose();
        }
        createdFonts.clear();
    }

    @Test
    public void testResolve_exactMatch_returnsThatStyle() {
        CgFontFamily regular = familyOf(CgFontStyle.REGULAR);
        CgFontFamily bold = familyOf(CgFontStyle.BOLD);
        CgFontFamilyGroup group = new CgFontFamilyGroup(Map.of(
                CgFontStyle.REGULAR, regular,
                CgFontStyle.BOLD, bold));

        assertSame(regular, group.resolve(CgFontStyle.REGULAR));
        assertSame(bold, group.resolve(CgFontStyle.BOLD));
    }

    @Test
    public void testResolve_noExactMatch_fallsBackToRegular() {
        CgFontFamily regular = familyOf(CgFontStyle.REGULAR);
        CgFontFamilyGroup group = new CgFontFamilyGroup(Map.of(CgFontStyle.REGULAR, regular));

        assertSame("ITALIC has no entry, should fall back to REGULAR",
                regular, group.resolve(CgFontStyle.ITALIC));
        assertSame("BOLD_ITALIC has no entry, should fall back to REGULAR",
                regular, group.resolve(CgFontStyle.BOLD_ITALIC));
    }

    @Test
    public void testOfRegular_resolvesEveryStyleToTheSameFamily() {
        CgFontFamily regular = familyOf(CgFontStyle.REGULAR);
        CgFontFamilyGroup group = CgFontFamilyGroup.ofRegular(regular);

        for (CgFontStyle style : CgFontStyle.values()) {
            assertSame(regular, group.resolve(style));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_rejectsMapWithoutRegular() {
        CgFontFamily bold = familyOf(CgFontStyle.BOLD);
        new CgFontFamilyGroup(Map.of(CgFontStyle.BOLD, bold));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructor_rejectsNullMap() {
        new CgFontFamilyGroup(null);
    }

    @Test
    public void testConstructor_copiesMapDefensively() {
        CgFontFamily regular = familyOf(CgFontStyle.REGULAR);
        Map<CgFontStyle, CgFontFamily> mutable = new HashMap<>();
        mutable.put(CgFontStyle.REGULAR, regular);
        CgFontFamilyGroup group = new CgFontFamilyGroup(mutable);

        mutable.put(CgFontStyle.BOLD, familyOf(CgFontStyle.BOLD));

        assertSame("Later mutation of the caller's map must not affect the group",
                regular, group.resolve(CgFontStyle.BOLD));
    }

    /** A lazy group loads a style's face only when a span asks for it, and asks once either way. */
    @Test
    public void testLazy_buildsEachStyleOnceOnFirstResolve() {
        CgFontFamily regular = familyOf(CgFontStyle.REGULAR);
        CgFontFamily bold = familyOf(CgFontStyle.BOLD);
        Map<CgFontStyle, Integer> asked = new EnumMap<>(CgFontStyle.class);
        CgFontFamilyGroup group = CgFontFamilyGroup.lazy(regular, style -> {
            asked.merge(style, 1, Integer::sum);
            return style == CgFontStyle.BOLD ? bold : null;
        });

        assertTrue("nothing is built up front", asked.isEmpty());
        assertSame(bold, group.resolve(CgFontStyle.BOLD));
        assertSame(bold, group.resolve(CgFontStyle.BOLD));
        assertSame("no italic face: regular answers", regular, group.resolve(CgFontStyle.ITALIC));
        assertSame(regular, group.resolve(CgFontStyle.ITALIC));
        assertEquals(Integer.valueOf(1), asked.get(CgFontStyle.BOLD));
        assertEquals("a missing face is remembered too", Integer.valueOf(1), asked.get(CgFontStyle.ITALIC));
    }

    // ---------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------

    private CgFontFamily familyOf(CgFontStyle style) {
        byte[] bytes = loadTestFontBytes();
        CgFont font = CgFont.load(bytes, "test-font-" + style, style, 16);
        createdFonts.add(font);
        return CgFontFamily.of(font);
    }

    private static byte[] loadTestFontBytes() {
        InputStream in = CgFontFamilyGroupTest.class.getResourceAsStream("/fonts/test-font.ttf");
        assertNotNull("test font resource must exist", in);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new AssertionError("Failed to read test font resource", e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }
}
