package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.CgAttribFormat;
import com.crystalgraphics.platform.device.CgPipelineDesc;
import com.crystalgraphics.platform.device.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgGL;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.*;

/** Buffers and vertex arrays on the tracked backend, with GL's rules about what a draw reads. */
public class CgTrackedBuffersTest {

    private final CgRecordingDevice device = new CgRecordingDevice(16, 16);
    private final CgTrackedGLBackend gl = new CgTrackedGLBackend(device, true);

    private int buffer(int target, int bytes, int usage) {
        int b = gl.glGenBuffers();
        gl.glBindBuffer(target, b);
        gl.glBufferData(target, bytes, usage);
        return b;
    }

    @Test
    public void aPointerReadsItsBuffersStorageAsItIsAtTheDraw() {
        int vao = gl.glGenVertexArrays();
        gl.glBindVertexArray(vao);
        buffer(CgGL.GL_ARRAY_BUFFER, 64, 0x88E8 /* GL_DYNAMIC_DRAW */);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, CgGL.GL_FLOAT, false, 12, 0);
        CgDrawState s = gl.tracker().state;
        gl.vertexArrays().apply(s);
        CgAllocation first = s.vertexAllocations[0];

        gl.glBufferData(CgGL.GL_ARRAY_BUFFER, 64, 0x88E8);   // an orphan
        gl.vertexArrays().apply(s);
        assertNotSame("the draw reads the new storage", first, s.vertexAllocations[0]);
    }

    @Test
    public void theElementBufferBelongsToTheVertexArray() {
        int a = gl.glGenVertexArrays(), b = gl.glGenVertexArrays();
        gl.glBindVertexArray(a);
        int ibo = buffer(CgGL.GL_ELEMENT_ARRAY_BUFFER, 12, CgGL.GL_STATIC_DRAW);
        gl.glBindVertexArray(b);
        assertEquals(0, gl.glGetInteger(CgGL.GL_ELEMENT_ARRAY_BUFFER_BINDING));
        gl.glBindVertexArray(a);
        assertEquals(ibo, gl.glGetInteger(CgGL.GL_ELEMENT_ARRAY_BUFFER_BINDING));
    }

    @Test
    public void interleavedAttributesShareABindingAndAnInstancedOneHasItsOwn() {
        gl.glBindVertexArray(gl.glGenVertexArrays());
        buffer(CgGL.GL_ARRAY_BUFFER, 256, CgGL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribPointer(0, 3, CgGL.GL_FLOAT, false, 24, 0);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribPointer(1, 4, CgGL.GL_UNSIGNED_BYTE, true, 24, 12);
        buffer(CgGL.GL_ARRAY_BUFFER, 256, 0x88E0 /* GL_STREAM_DRAW */);
        gl.glEnableVertexAttribArray(2);
        gl.glVertexAttribPointer(2, 4, CgGL.GL_FLOAT, false, 16, 32);
        gl.glVertexAttribDivisor(2, 1);

        CgDrawState s = gl.tracker().state;
        gl.vertexArrays().apply(s);
        List<CgPipelineDesc.VertexBuffer> layouts = s.vertexLayouts;
        assertEquals(2, layouts.size());
        assertEquals(List.of(new CgPipelineDesc.VertexAttrib(0, CgAttribFormat.FLOAT32X3, 0),
                new CgPipelineDesc.VertexAttrib(1, CgAttribFormat.UNORM8X4, 12)), layouts.get(0).attribs());
        assertTrue(layouts.get(1).perInstance());
        assertEquals("the instanced range starts at its pointer", 32, s.vertexOffsets[1]);

        gl.vertexArrays().apply(s);
        assertSame("unchanged attributes keep their layouts, and with them the pipeline", layouts, s.vertexLayouts);
    }

    @Test
    public void aPersistentMapIsTheMemoryItselfAndAnInvalidatingOneIsFresh() {
        int ring = gl.glGenBuffers();
        gl.glBindBuffer(CgGL.GL_ARRAY_BUFFER, ring);
        gl.glBufferStorage(CgGL.GL_ARRAY_BUFFER, 64, TrackedBuffers.GL_MAP_PERSISTENT_BIT | TrackedBuffers.GL_MAP_WRITE_BIT);
        ByteBuffer mapped = gl.glMapBufferRange(CgGL.GL_ARRAY_BUFFER, 16, 16,
                TrackedBuffers.GL_MAP_WRITE_BIT | TrackedBuffers.GL_MAP_PERSISTENT_BIT, null);
        mapped.putInt(0, 42);
        CgAllocation storage = gl.bufferObjects().get(ring).storage.allocation();
        assertEquals(42, storage.memory().getInt(16));

        int orphaned = buffer(CgGL.GL_UNIFORM_BUFFER, 64, 0x88E8);
        CgAllocation before = gl.bufferObjects().get(orphaned).storage.allocation();
        gl.glMapBufferRange(CgGL.GL_UNIFORM_BUFFER, 0, 64,
                TrackedBuffers.GL_MAP_WRITE_BIT | TrackedBuffers.GL_MAP_INVALIDATE_BUFFER_BIT, null);
        gl.glUnmapBuffer(CgGL.GL_UNIFORM_BUFFER);
        assertNotSame(before, gl.bufferObjects().get(orphaned).storage.allocation());
    }
}
