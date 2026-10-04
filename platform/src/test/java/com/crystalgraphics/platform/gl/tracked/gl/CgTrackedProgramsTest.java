package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.FakeGlslCompiler;
import com.crystalgraphics.platform.gl.tracked.memory.CgAllocation;
import com.crystalgraphics.platform.gl.tracked.tracker.CgDrawState;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.*;

/** Programs on the tracked backend: GL's uniform and block calls land where the compiler put each name. */
public class CgTrackedProgramsTest {

    private static final int UBO = 0x88E8; // GL_DYNAMIC_DRAW

    /** a_pos at 0; a loose block (binding 0) holding u_color, u_scale and u_list[3]; CgFrameBlock at binding 1. */
    private static final FakeGlslCompiler COMPILER = new FakeGlslCompiler(
            List.of(new CgGlslCompiler.Attribute("a_pos", 0, 0x8B50)),
            List.of(new CgGlslCompiler.Block("CgFrameBlock", 1)),
            List.of(),
            List.of(new CgGlslCompiler.Uniform("u_color", 0x8B52, 1, 0, 16, 0, -1, 1, 4, false),
                    new CgGlslCompiler.Uniform("u_scale", 0x1406, 1, 0, 16, 16, -1, 1, 1, false),
                    new CgGlslCompiler.Uniform("u_list", 0x1406, 3, 16, 16, 32, -1, 1, 1, false)),
            0, 80,
            List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.UNIFORM_BUFFER),
                    new CgBindingLayout.Slot(1, CgBindingLayout.Type.UNIFORM_BUFFER)));

    private final CgRecordingDevice device = new CgRecordingDevice(16, 16);
    private final CgTrackedGLBackend gl = new CgTrackedGLBackend(device, COMPILER, true);

    private int program(String vertexSource) {
        int vs = gl.glCreateShader(CgGL.GL_VERTEX_SHADER), fs = gl.glCreateShader(CgGL.GL_FRAGMENT_SHADER);
        gl.glShaderSource(vs, vertexSource);
        gl.glShaderSource(fs, "void main() {}");
        int p = gl.glCreateProgram();
        gl.glAttachShader(p, vs);
        gl.glAttachShader(p, fs);
        gl.glBindAttribLocation(p, 0, "a_pos");
        gl.glLinkProgram(p);
        return p;
    }

    /** A program in use, a vertex array feeding a_pos, and CgFrameBlock's buffer at binding point 2. */
    private int ready() {
        int p = program("void main() {}");
        gl.glUseProgram(p);
        gl.glUniformBlockBinding(p, gl.glGetUniformBlockIndex(p, "CgFrameBlock"), 2);
        int frame = gl.glGenBuffers();
        gl.glBindBuffer(CgGL.GL_UNIFORM_BUFFER, frame);
        gl.glBufferData(CgGL.GL_UNIFORM_BUFFER, 256, UBO);
        gl.glBindBufferBase(CgGL.GL_UNIFORM_BUFFER, 2, frame);
        gl.glBindVertexArray(gl.glGenVertexArrays());
        gl.glBindBuffer(CgGL.GL_ARRAY_BUFFER, gl.glGenBuffers());
        gl.glBufferData(CgGL.GL_ARRAY_BUFFER, 64, UBO);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 2, CgGL.GL_FLOAT, false, 8, 0);
        return p;
    }

    /** The loose uniforms' upload the last draw bound: a range of this frame's uploads. */
    private ByteBuffer looseBlock() {
        CgDrawState s = gl.tracker().state;
        int i = s.bindings.indexOf(0);
        ByteBuffer m = s.bindingAllocation(i).memory();
        m.position((int) (s.bindings.offset(i) - s.bindingAllocation(i).offset()));
        return m.slice().order(m.order());
    }

    /** Where that upload is: its buffer and offset. */
    private String looseAt() {
        CgDrawState s = gl.tracker().state;
        int i = s.bindings.indexOf(0);
        return s.bindingAllocation(i).buffer().label() + "@" + s.bindings.offset(i);
    }

    @Test
    public void uniformsLandAtTheirOffsetsAndAreUploadedOnlyWhenTheyChange() {
        int p = ready();
        gl.glUniform4f(gl.glGetUniformLocation(p, "u_color"), 1, 2, 3, 4);
        gl.glUniform1f(gl.glGetUniformLocation(p, "u_list[2]"), 9);
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        ByteBuffer first = looseBlock();
        String firstAt = looseAt();
        assertEquals(4f, first.getFloat(12), 0f);
        assertEquals("u_list[2] is two 16-byte elements past u_list", 9f, first.getFloat(32 + 32), 0f);

        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        assertEquals("nothing changed: the upload is reused", firstAt, looseAt());

        gl.glUniform1f(gl.glGetUniformLocation(p, "u_scale"), 0.5f);
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        assertNotEquals(firstAt, looseAt());
        assertEquals("the earlier draws keep their bytes", 0f, first.getFloat(16), 0f);
        assertEquals(0.5f, looseBlock().getFloat(16), 0f);
    }

    @Test
    public void aBlockReadsTheBufferAtTheBindingPointGlChose() {
        ready();
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        CgDrawState s = gl.tracker().state;
        int i = s.bindings.indexOf(1);
        CgAllocation bound = s.bindingAllocation(i);
        assertEquals(256, s.bindings.size(i));
        assertSame(gl.bufferObjects().get(gl.glGetInteger(CgGL.GL_UNIFORM_BUFFER_BINDING)).storage.allocation(), bound);
    }

    @Test
    public void aPipelineBuiltAheadIsTheOneTheDrawBinds() {
        int p = program("void main() {}");
        gl.glUseProgram(p);
        gl.glBindVertexArray(gl.glGenVertexArrays());
        gl.buildPipeline(CgGL.GL_TRIANGLES);                 // CgFrameBlock unbound, a_pos from no array
        gl.buildPipeline(CgGL.GL_TRIANGLES);
        assertEquals(1, gl.tracker().stats().pipelineMisses);

        gl.glUniformBlockBinding(p, gl.glGetUniformBlockIndex(p, "CgFrameBlock"), 2);
        int frame = gl.glGenBuffers();
        gl.glBindBuffer(CgGL.GL_UNIFORM_BUFFER, frame);
        gl.glBufferData(CgGL.GL_UNIFORM_BUFFER, 256, UBO);
        gl.glBindBufferBase(CgGL.GL_UNIFORM_BUFFER, 2, frame);
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        assertEquals("the draw found the pipeline already built", 1, gl.tracker().stats().pipelineMisses);
    }

    @Test
    public void aCompileErrorIsALinkFailureWithTheCompilersLog() {
        int p = program("BROKEN");
        assertEquals(CgGL.GL_FALSE, gl.glGetProgrami(p, CgGL.GL_LINK_STATUS));
        assertTrue(gl.glGetProgramInfoLog(p, 1024).contains("undeclared identifier"));
    }

    @Test
    public void locationsNameEveryElementAndNothingElse() {
        int p = program("void main() {}");
        assertEquals(gl.glGetUniformLocation(p, "u_list"), gl.glGetUniformLocation(p, "u_list[0]"));
        assertEquals(gl.glGetUniformLocation(p, "u_list") + 2, gl.glGetUniformLocation(p, "u_list[2]"));
        assertEquals(-1, gl.glGetUniformLocation(p, "u_missing"));
        assertEquals(CgGL.GL_INVALID_INDEX, gl.glGetUniformBlockIndex(p, "NoSuchBlock"));
    }
}
