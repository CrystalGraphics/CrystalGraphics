package com.crystalgraphics.platform.gl.state;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static com.crystalgraphics.platform.gl.driver.GLC.*;
import static org.junit.Assert.assertEquals;

/** The GL device's redundancy filter over a driver that records what reaches it. */
public class GLStateFilterTest {

    private final List<String> calls = new ArrayList<>();
    private GLStateFilter gl;

    @Before
    public void make() {
        CgGLDriver recording = (CgGLDriver) Proxy.newProxyInstance(CgGLDriver.class.getClassLoader(),
                new Class<?>[] {CgGLDriver.class}, (proxy, method, args) -> {
                    calls.add(method.getName());
                    Class<?> r = method.getReturnType();
                    if (r == boolean.class) return false;
                    if (r == int.class) return 0;
                    if (r == long.class) return 0L;
                    if (r == float.class) return 0f;
                    return null;
                });
        gl = new GLStateFilter(recording);
    }

    @Test
    public void aCallSettingWhatTheContextHasIsDropped() {
        gl.glEnable(GL_DEPTH_TEST);
        gl.glEnable(GL_DEPTH_TEST);
        gl.glDepthFunc(GL_LEQUAL);
        gl.glDepthFunc(GL_LEQUAL);
        gl.glBindBufferRange(GL_UNIFORM_BUFFER, 2, 7, 256, 64);
        gl.glBindBufferRange(GL_UNIFORM_BUFFER, 2, 7, 256, 64);
        gl.glBindBufferRange(GL_UNIFORM_BUFFER, 2, 7, 512, 64);   // another range of the same buffer
        gl.glColorMaski(1, true, false, true, true);
        gl.glColorMaski(1, true, false, true, true);
        assertEquals(List.of("glEnable", "glDepthFunc", "glBindBufferRange", "glBindBufferRange", "glColorMaski"), calls);
    }

    @Test
    public void everyFieldStartsUnknownAndInvalidateMakesItSoAgain() {
        gl.glUseProgram(0);                                    // unknown: reaches GL even for 0
        gl.glUseProgram(0);
        gl.invalidate();
        gl.glUseProgram(0);
        assertEquals(List.of("glUseProgram", "glUseProgram"), calls);
    }

    @Test
    public void deletingABoundObjectUnbindsIt() {
        gl.glBindTextureUnit(3, 9);
        gl.glDeleteTextures(9);
        gl.glBindTextureUnit(3, 0);                            // GL already unbound it
        gl.glBindTextureUnit(3, 9);                            // a reused name: bound anew
        assertEquals(List.of("glBindTextureUnit", "glDeleteTextures", "glBindTextureUnit"), calls);
    }

    @Test
    public void restoreSetsOnlyWhatDiffersAndLeavesTheUnknown() {
        GLContextState host = new GLContextState();
        host.depthFunc = GL_LESS;
        host.program = 4;
        gl.adopt(host);
        gl.glDepthFunc(GL_GREATER);
        gl.glScissor(0, 0, 8, 8);
        calls.clear();
        gl.restore(host);
        assertEquals("the depth function back; the program untouched; the scissor unknown to the host, left",
                List.of("glDepthFunc"), calls);
    }

    @Test
    public void bothFacesAtOnceMatchEachFaceSeparately() {
        gl.glStencilMaskSeparate(GL_FRONT_AND_BACK, 0xFF);
        gl.glStencilMaskSeparate(GL_FRONT, 0xFF);
        gl.glStencilMaskSeparate(GL_BACK, 0x0F);
        assertEquals(List.of("glStencilMaskSeparate", "glStencilMaskSeparate"), calls);
    }
}
