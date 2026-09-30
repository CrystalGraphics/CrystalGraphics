package com.crystalgraphics.platform.gl;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/** Host brackets nest, and only the outermost pair reaches the backend. */
public class CgGLHostSectionTest {

    private RecordingGlBackend gl;

    @Before
    public void setUp() {
        gl = RecordingGlBackend.install();
    }

    @Test
    public void aBracketReachesTheBackendOnce() {
        CgGL.fromHost();
        CgGL.toHost();
        assertEquals(Arrays.asList("fromHost", "toHost"), gl.calls());
    }

    @Test
    public void anInnerBracketCollapsesIntoTheOuter() {
        CgGL.fromHost();          // a host's bracket
        CgGL.fromHost();          // CgGraphicsLifecycle's, inside it
        CgGL.toHost();
        assertEquals(Arrays.asList("fromHost"), gl.calls());
        CgGL.toHost();
        assertEquals(Arrays.asList("fromHost", "toHost"), gl.calls());
    }

    @Test
    public void aCloseWithNothingOpenThrowsAndChangesNothing() {
        assertThrows(IllegalStateException.class, CgGL::toHost);
        assertTrue(gl.calls().isEmpty());
        CgGL.fromHost();
        CgGL.toHost();
        assertEquals(Arrays.asList("fromHost", "toHost"), gl.calls());
    }
}
