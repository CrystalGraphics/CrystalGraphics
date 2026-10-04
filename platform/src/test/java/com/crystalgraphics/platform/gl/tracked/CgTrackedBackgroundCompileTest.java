package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.gl.CgGL;
import org.junit.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

/** Links compiled in the background, answered as a driver with {@code KHR_parallel_shader_compile} answers them. */
public class CgTrackedBackgroundCompileTest {

    /** {@link FakeGlslCompiler#EMPTY}'s answer, held until the test lets it go. */
    private static final class Gated implements CgGlslCompiler {
        final CountDownLatch release = new CountDownLatch(1);
        volatile Thread ranOn;

        @Override
        public Program compile(String vertexGlsl, String fragmentGlsl, Map<String, Integer> attribLocations, String label) {
            ranOn = Thread.currentThread();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            return FakeGlslCompiler.EMPTY.compile(vertexGlsl, fragmentGlsl, attribLocations, label);
        }

        @Override
        public ComputeProgram compileCompute(String glsl, String label) {
            throw new UnsupportedOperationException();
        }
    }

    private static int link(CgTrackedGLBackend gl, String fragment) {
        int vs = gl.glCreateShader(CgGL.GL_VERTEX_SHADER), fs = gl.glCreateShader(CgGL.GL_FRAGMENT_SHADER);
        gl.glShaderSource(vs, "void main() {}");
        gl.glShaderSource(fs, fragment);
        int program = gl.glCreateProgram();
        gl.glAttachShader(program, vs);
        gl.glAttachShader(program, fs);
        gl.glLinkProgram(program);
        return program;
    }

    @Test
    public void aLinkReturnsBeforeShadercHas_andItsStatusWaitsForIt() {
        Gated compiler = new Gated();
        CgTrackedGLBackend gl = new CgTrackedGLBackend(new CgRecordingDevice(8, 8), compiler, true).compileInBackground();
        int program = link(gl, "void main() {}");

        assertEquals(CgGL.GL_FALSE, gl.glGetProgrami(program, CgGL.GL_COMPLETION_STATUS_KHR));
        compiler.release.countDown();
        assertEquals(CgGL.GL_TRUE, gl.glGetProgrami(program, CgGL.GL_LINK_STATUS));
        assertNotSame(Thread.currentThread(), compiler.ranOn);
        assertEquals(CgGL.GL_TRUE, gl.glGetProgrami(program, CgGL.GL_COMPLETION_STATUS_KHR));
    }

    @Test
    public void aCompileThatFailsOnTheWorkerFailsTheLink_withItsLog() {
        CgTrackedGLBackend gl = new CgTrackedGLBackend(new CgRecordingDevice(8, 8), FakeGlslCompiler.EMPTY, true)
                .compileInBackground();
        int program = link(gl, "BROKEN");

        assertEquals(CgGL.GL_FALSE, gl.glGetProgrami(program, CgGL.GL_LINK_STATUS));
        assertTrue(gl.glGetProgramInfoLog(program, 4096).contains("undeclared identifier"));
    }

    @Test
    public void withoutAWorker_aLinkIsDoneAtTheCall() {
        CgTrackedGLBackend gl = new CgTrackedGLBackend(new CgRecordingDevice(8, 8), FakeGlslCompiler.EMPTY, true);
        int program = link(gl, "void main() {}");

        assertEquals(CgGL.GL_TRUE, gl.glGetProgrami(program, CgGL.GL_COMPLETION_STATUS_KHR));
        assertEquals(CgGL.GL_TRUE, gl.glGetProgrami(program, CgGL.GL_LINK_STATUS));
    }
}
