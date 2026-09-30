package com.crystalgraphics.platform.gl;

import com.crystalgraphics.platform.CgPlatform;
import com.crystalgraphics.platform.CgPlatformService;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Arrays;

import static org.junit.Assert.*;

/** Host brackets nest, only the outermost pair reaches the backend, and the first installs it. */
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

    @Test
    public void registrationBuildsNoBackendAndTheFirstBracketInstallsIt() {
        RecordingGlBackend backend = new RecordingGlBackend();
        int[] built = {0};
        CgPlatform.register((CgPlatformService) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {CgPlatformService.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("gl")) return null;
                    built[0]++;
                    return backend;
                }));
        assertEquals("a dedicated server registers too", 0, built[0]);
        assertFalse(CgGL.isInstalled());

        CgGL.fromHost();
        CgGL.toHost();
        assertEquals(1, built[0]);
        assertTrue(CgGL.isInstalled());
        assertEquals(Arrays.asList("fromHost", "toHost"), backend.calls());
    }
}
