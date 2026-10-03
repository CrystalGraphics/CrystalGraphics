package com.crystalgraphics.compute.emit;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.*;

public class CgGlslBuiltinsTest {

    @Test
    public void rename_takesOnlyCallsOfAMissingBuiltin() {
        String code = "uint a = bitCount(x); uint b = mybitCount(x); uint c = s.bitCount(x); uint bitCountX = 1u;"
                + " uint d = bitCount (y);";
        String renamed = CgGlslBuiltins.rename(code, Set.of("bitCount"), 330);
        assertEquals("uint a = _cg_bitCount(x); uint b = mybitCount(x); uint c = s.bitCount(x); uint bitCountX = 1u;"
                + " uint d = _cg_bitCount (y);", renamed);
        assertSame(code, CgGlslBuiltins.rename(code, Set.of("bitCount"), 400));
    }

    @Test
    public void polyfills_emitWhatEachCalls_first_andOnce() {
        String glsl = CgGlslBuiltins.polyfills(Set.of("findLSB", "bitCount", "imulExtended"), 330);
        int bitCount = glsl.indexOf("_cg_bitCount(uint");
        int findLsb = glsl.indexOf("_cg_findLSB(uint");
        int umul = glsl.indexOf("void _cg_umulExtended(uint");
        int imul = glsl.indexOf("void _cg_imulExtended(int");
        assertTrue(glsl, bitCount >= 0 && bitCount < findLsb && umul >= 0 && umul < imul);
        assertEquals(glsl.indexOf("int _cg_bitCount(uint v) {"), glsl.lastIndexOf("int _cg_bitCount(uint v) {"));
        assertEquals("", CgGlslBuiltins.polyfills(Set.of("bitCount"), 400));
    }

    @Test
    public void packHalf_isPolyfilledBelow420_textureGatherIsRefusedBelow400() {
        assertEquals(Set.of("packHalf2x16"), CgGlslBuiltins.missing(Set.of("packHalf2x16", "bitCount"), 400));
        assertNull(CgGlslBuiltins.refusal(Set.of("packHalf2x16", "bitCount"), 330));
        String why = CgGlslBuiltins.refusal(Set.of("textureGather"), 330);
        assertEquals("uses textureGather, GLSL 4.00, which GLSL 3.30 lacks and no polyfill gives exactly", why);
        assertNull(CgGlslBuiltins.refusal(Set.of("textureGather"), 400));
    }

    @Test
    public void version_knowsOnlyBuiltinsNewerThan330() {
        assertEquals(420, CgGlslBuiltins.version("packHalf2x16"));
        assertEquals(0, CgGlslBuiltins.version("floatBitsToUint"));
        assertFalse(CgGlslBuiltins.versioned("texture"));
        assertEquals("4.60", CgGlslBuiltins.versionName(460));
    }
}
