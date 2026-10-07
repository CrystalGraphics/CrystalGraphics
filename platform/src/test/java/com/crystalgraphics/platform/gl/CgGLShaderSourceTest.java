package com.crystalgraphics.platform.gl;

import org.junit.Test;

import static org.junit.Assert.*;

/** No backend sees a non-ASCII character in a shader's source: LWJGL 2 turns U+2500 into a NUL that ends it. */
public class CgGLShaderSourceTest {

    @Test
    public void everyCharacterAbove127ReachesTheBackendAsASpace() {
        RecordingGlBackend gl = RecordingGlBackend.install();
        CgGL.glShaderSource(1, "// ── Hashes — x×y\nvoid main() {}\n");
        assertEquals("//    Hashes   x y\nvoid main() {}\n", gl.shaderSources.get(0));
    }

    @Test
    public void anAsciiSourcePassesAsItIs() {
        String source = "void main() {}\n";
        assertSame(source, CgGL.ascii(source));
    }
}
