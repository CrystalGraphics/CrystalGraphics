package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.format.CgAttribFormat;
import com.crystalgraphics.platform.device.pipeline.CgPipelineDesc;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.FakeGlslCompiler;
import com.crystalgraphics.platform.gl.tracked.memory.CgAllocation;
import com.crystalgraphics.platform.gl.tracked.tracker.CgDrawState;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.junit.Assert.*;

/** Buffers and vertex arrays on the tracked backend, with GL's rules about what a draw reads. */
public class CgTrackedBuffersTest {

    private final CgRecordingDevice device = new CgRecordingDevice(16, 16);
    private final CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);

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
    public void anIntegerPointerReadsIntegersAndAHalfFloatOneFloats() {
        gl.glBindVertexArray(gl.glGenVertexArrays());
        buffer(CgGL.GL_ARRAY_BUFFER, 256, CgGL.GL_STATIC_DRAW);
        gl.glEnableVertexAttribArray(0);
        gl.glVertexAttribIPointer(0, 4, CgGL.GL_UNSIGNED_BYTE, 20, 0);
        gl.glEnableVertexAttribArray(1);
        gl.glVertexAttribIPointer(1, 2, CgGL.GL_SHORT, 20, 4);
        gl.glEnableVertexAttribArray(2);
        gl.glVertexAttribIPointer(2, 1, CgGL.GL_UNSIGNED_INT, 20, 8);
        gl.glEnableVertexAttribArray(3);
        gl.glVertexAttribPointer(3, 4, CgGL.GL_HALF_FLOAT, false, 20, 12);

        CgDrawState s = gl.tracker().state;
        gl.vertexArrays().apply(s);
        assertEquals(List.of(new CgPipelineDesc.VertexAttrib(0, CgAttribFormat.UINT8X4, 0),
                new CgPipelineDesc.VertexAttrib(1, CgAttribFormat.SINT16X2, 4),
                new CgPipelineDesc.VertexAttrib(2, CgAttribFormat.UINT32, 8),
                new CgPipelineDesc.VertexAttrib(3, CgAttribFormat.FLOAT16X4, 12)), s.vertexLayouts.get(0).attribs());
    }

    @Test
    public void aCopyIntoDeviceLocalStorageIsADeviceCopyAndBetweenHostVisibleOnesTheCpus() {
        int staging = buffer(CgGL.GL_ARRAY_BUFFER, 16, 0x88E0 /* GL_STREAM_DRAW */);
        gl.glBufferSubData(CgGL.GL_ARRAY_BUFFER, 0, ByteBuffer.allocateDirect(16).order(ByteOrder.nativeOrder()).putInt(0, 7));
        int local = buffer(CgGL.GL_ARRAY_BUFFER, 32, CgGL.GL_STATIC_DRAW);
        int visible = buffer(CgGL.GL_ARRAY_BUFFER, 32, 0x88E8 /* GL_DYNAMIC_DRAW */);

        int mark = device.log().size();
        copy(staging, local, 0, 16);
        assertTrue(device.logSince(mark).stream().anyMatch(line -> line.startsWith("copyBuffer")));

        mark = device.log().size();
        copy(staging, visible, 8, 8);
        assertTrue("host-visible to host-visible records nothing",
                device.logSince(mark).stream().noneMatch(line -> line.startsWith("copyBuffer")));
        CgAllocation to = gl.bufferObjects().get(visible).storage.allocation();
        assertEquals(7, to.memory().getInt(8));

        try {
            copy(local, visible, 0, 8);
            fail("a device-local source into a host-visible buffer is a readback");
        } catch (UnsupportedOperationException expected) {
        }
    }

    private void copy(int from, int to, long writeOffset, long size) {
        gl.glBindBuffer(CgGL.GL_COPY_READ_BUFFER, from);
        gl.glBindBuffer(CgGL.GL_COPY_WRITE_BUFFER, to);
        gl.glCopyBufferSubData(CgGL.GL_COPY_READ_BUFFER, CgGL.GL_COPY_WRITE_BUFFER, 0, writeOffset, size);
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
