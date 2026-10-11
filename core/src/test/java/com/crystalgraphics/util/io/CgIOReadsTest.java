package com.crystalgraphics.util.io;

import com.crystalgraphics.api.shader.CgShaderPreprocessor;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CgIOReadsTest {

    private static final String MATH = "/assets/crystalgraphics/shaders/lib/math.glsl";
    private static final String VECTOR = "/assets/crystalgraphics/shaders/lib/vector.glsl";
    private static final String COLOR = "/assets/crystalgraphics/shaders/lib/color.glsl";

    @Test
    public void nestedScopesReachTheirParent() {
        try (CgIO.Reads outer = CgIO.recordReads()) {
            CgIO.loadSource("crystalgraphics:shaders/lib/math.glsl");
            try (CgIO.Reads inner = CgIO.recordReads()) {
                CgIO.loadSource("crystalgraphics:shaders/lib/color.glsl");
                assertTrue(inner.paths().contains(COLOR));
                assertFalse(inner.paths().contains(MATH));
            }
            assertTrue(outer.paths().contains(MATH));
            assertTrue(outer.paths().contains(COLOR));
        }
        CgIO.noteRead(MATH);   // no scope open: nothing to record into, and no error
    }

    @Test
    public void includesAreRecordedEvenFromTheCache() {
        String source = "#include \"crystalgraphics:shaders/lib/vector.glsl\"\nvoid main() {}\n";
        for (int pass = 0; pass < 2; pass++) {   // the second is answered by the preprocessor's cache
            try (CgIO.Reads reads = CgIO.recordReads()) {
                new CgShaderPreprocessor().process(source, "crystalgraphics:shaders/test.frag");
                assertTrue("pass " + pass, reads.paths().contains(VECTOR));
                assertTrue("pass " + pass, reads.paths().contains(MATH));
            }
        }
    }
}
