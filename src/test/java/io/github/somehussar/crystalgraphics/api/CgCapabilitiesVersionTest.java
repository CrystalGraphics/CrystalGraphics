package io.github.somehussar.crystalgraphics.api;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for OpenGL version parsing and comparison in {@link CgCapabilities}.
 *
 * <p>The {@code parseGLVersion} method is package-visible specifically to
 * enable these tests without requiring an active OpenGL context.</p>
 */
public class CgCapabilitiesVersionTest {

    // ---------------------------------------------------------------
    //  parseGLVersion tests
    // ---------------------------------------------------------------

    @Test
    public void testParseStandardVersion() {
        int[] v = CgCapabilities.parseGLVersion("4.6.0 NVIDIA 537.58");
        assertEquals(4, v[0]);
        assertEquals(6, v[1]);
    }

    @Test
    public void testParseMajorMinorOnly() {
        int[] v = CgCapabilities.parseGLVersion("3.1");
        assertEquals(3, v[0]);
        assertEquals(1, v[1]);
    }

    @Test
    public void testParseMesaVersion() {
        int[] v = CgCapabilities.parseGLVersion("3.1 Mesa 22.3.6");
        assertEquals(3, v[0]);
        assertEquals(1, v[1]);
    }

    @Test
    public void testParseVersionWithRelease() {
        int[] v = CgCapabilities.parseGLVersion("4.5.0");
        assertEquals(4, v[0]);
        assertEquals(5, v[1]);
    }

    @Test
    public void testParseOpenGL20() {
        int[] v = CgCapabilities.parseGLVersion("2.0");
        assertEquals(2, v[0]);
        assertEquals(0, v[1]);
    }

    @Test
    public void testParseOpenGL21Intel() {
        int[] v = CgCapabilities.parseGLVersion("2.1 Intel Build 8.15.10.2559");
        assertEquals(2, v[0]);
        assertEquals(1, v[1]);
    }

    @Test
    public void testParseNull() {
        int[] v = CgCapabilities.parseGLVersion(null);
        assertEquals(0, v[0]);
        assertEquals(0, v[1]);
    }

    @Test
    public void testParseEmpty() {
        int[] v = CgCapabilities.parseGLVersion("");
        assertEquals(0, v[0]);
        assertEquals(0, v[1]);
    }

    @Test
    public void testParseGarbage() {
        int[] v = CgCapabilities.parseGLVersion("not a version");
        assertEquals(0, v[0]);
        assertEquals(0, v[1]);
    }

    @Test
    public void testParseNoDot() {
        int[] v = CgCapabilities.parseGLVersion("42");
        assertEquals(0, v[0]);
        assertEquals(0, v[1]);
    }

    @Test
    public void testParseOpenGLES() {
        // Some drivers report "OpenGL ES 3.2" - prefix text before digits
        int[] v = CgCapabilities.parseGLVersion("OpenGL ES 3.2 v1.r32");
        assertEquals(3, v[0]);
        assertEquals(2, v[1]);
    }

    // ---------------------------------------------------------------
    //  String overload parsing (exercises same parser)
    // ---------------------------------------------------------------

    @Test
    public void testParseSimpleVersionString() {
        int[] v = CgCapabilities.parseGLVersion("3.0");
        assertEquals(3, v[0]);
        assertEquals(0, v[1]);
    }

    @Test
    public void testParseLargeVersion() {
        int[] v = CgCapabilities.parseGLVersion("46.12.0");
        assertEquals(46, v[0]);
        assertEquals(12, v[1]);
    }
}
