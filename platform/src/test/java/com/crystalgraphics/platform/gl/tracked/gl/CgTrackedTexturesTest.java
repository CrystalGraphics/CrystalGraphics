package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.pipeline.CgBindingLayout;
import com.crystalgraphics.platform.device.recording.CgRecordingDevice;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.platform.device.shader.CgGlslCompiler;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.CgTrackedGLBackend;
import com.crystalgraphics.platform.gl.tracked.FakeGlslCompiler;
import com.crystalgraphics.platform.gl.tracked.tracker.CgDrawState;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.*;

/** Textures on the tracked backend: GL's uploads reach the device's layout, and a draw samples what GL would. */
public class CgTrackedTexturesTest {

    private static final FakeGlslCompiler COMPILER = new FakeGlslCompiler(List.of(), List.of(),
            List.of(new CgGlslCompiler.Sampler("_MainTex", 0, 0x8B5E, false)), List.of(), -1, 0,
            List.of(new CgBindingLayout.Slot(0, CgBindingLayout.Type.SAMPLED_TEXTURE)));

    private final CgRecordingDevice device = new CgRecordingDevice(16, 16);
    private final CgTrackedGLBackend gl = new CgTrackedGLBackend(device, COMPILER, true);

    private void program() {
        int vs = gl.glCreateShader(CgGL.GL_VERTEX_SHADER), fs = gl.glCreateShader(CgGL.GL_FRAGMENT_SHADER);
        int p = gl.glCreateProgram();
        gl.glAttachShader(p, vs);
        gl.glAttachShader(p, fs);
        gl.glLinkProgram(p);
        gl.glUseProgram(p);
    }

    private CgTextureView sampled() {
        CgDrawState s = gl.tracker().state;
        return s.bindings.view(s.bindings.indexOf(0));
    }

    @Test
    public void anRgbUploadIsWidenedToRgbaAcrossGlsPaddedRows() {
        GlPixels.Store store = new GlPixels.Store();       // alignment 4: a 2-pixel RGB row is 6 bytes, padded to 8
        ByteBuffer rgb = ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 0, 0, 7, 8, 9, 10, 11, 12, 0, 0});
        ByteBuffer rgba = GlPixels.unpack(rgb, CgGL.GL_RGB, CgGL.GL_UNSIGNED_BYTE, 2, 2, 1, store, CgFormat.RGBA8_UNORM);
        byte[] out = new byte[16];
        rgba.get(out);
        assertArrayEquals(new byte[] {1, 2, 3, -1, 4, 5, 6, -1, 7, 8, 9, -1, 10, 11, 12, -1}, out);
    }

    @Test
    public void aDrawSamplesTheLevelsSpecifiedUntilMipmapsAreGenerated() {
        program();
        int tex = gl.glGenTextures();
        gl.glBindTexture(CgGL.GL_TEXTURE_2D, tex);
        gl.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8, 8, 8, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE,
                ByteBuffer.allocateDirect(8 * 8 * 4));
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        assertEquals("only level 0 has contents", 1, sampled().mips());

        gl.glGenerateMipmap(CgGL.GL_TEXTURE_2D);
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        assertEquals("8x8 has four levels", 4, sampled().mips());

        gl.glTexParameteri(CgGL.GL_TEXTURE_2D, CgGL.GL_TEXTURE_MIN_FILTER, CgGL.GL_LINEAR);
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        assertEquals("a non-mipmap filter reads the base level alone", 1, sampled().mips());
    }

    @Test
    public void aTextureWithNoImageSamplesAsGlsIncompleteTexture() {
        program();
        gl.glBindTexture(CgGL.GL_TEXTURE_2D, gl.glGenTextures());
        gl.glDrawArrays(CgGL.GL_TRIANGLES, 0, 3);
        assertEquals("incomplete", sampled().texture().label());
    }

    @Test
    public void anUploadFromAnUnpackBufferIsADeviceCopyFromItWhenNothingConverts() {
        int tex = gl.glGenTextures();
        gl.glBindTexture(CgGL.GL_TEXTURE_2D, tex);
        gl.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8, 8, 8, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        int pbo = unpackBuffer(4096);
        int mark = device.log().size();
        gl.glTexSubImage2D(CgGL.GL_TEXTURE_2D, 0, 2, 2, 4, 4, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, 256L);
        List<String> since = device.logSince(mark);
        assertTrue(since.toString(), since.stream().anyMatch(c -> c.startsWith("copyBufferToTexture") && c.contains("+256 ")));
        assertTrue(since.toString(), since.stream().noneMatch(c -> c.startsWith("writeTexture")));
        gl.glDeleteBuffers(pbo);
    }

    @Test
    public void anUploadFromAnUnpackBufferThatConvertsIsUnpackedFromItsMemory() {
        int tex = gl.glGenTextures();
        gl.glBindTexture(CgGL.GL_TEXTURE_2D, tex);
        gl.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8, 2, 2, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        int pbo = unpackBuffer(256);
        int mark = device.log().size();
        gl.glPixelStorei(CgGL.GL_UNPACK_ALIGNMENT, 1);
        gl.glTexSubImage2D(CgGL.GL_TEXTURE_2D, 0, 0, 0, 2, 2, CgGL.GL_RGB, CgGL.GL_UNSIGNED_BYTE, 0L);
        List<String> since = device.logSince(mark);
        assertTrue(since.toString(), since.stream().anyMatch(c -> c.startsWith("writeTexture")));
        assertTrue(since.toString(), since.stream().noneMatch(c -> c.startsWith("copyBufferToTexture")));
    }

    @Test
    public void anUploadFromAnUnpackBufferWithNoneBoundIsAnError() {
        gl.glBindTexture(CgGL.GL_TEXTURE_2D, gl.glGenTextures());
        gl.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8, 2, 2, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        gl.glTexSubImage2D(CgGL.GL_TEXTURE_2D, 0, 0, 0, 2, 2, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, 0L);
        assertEquals(CgGL.GL_INVALID_OPERATION, gl.glGetError());
    }

    /** A persistently mapped unpack buffer of {@code bytes}, left bound: what {@code CgUploads} stages in. */
    private int unpackBuffer(int bytes) {
        int pbo = gl.glGenBuffers();
        gl.glBindBuffer(CgGL.GL_PIXEL_UNPACK_BUFFER, pbo);
        int flags = CgGL.GL_MAP_WRITE_BIT | CgGL.GL_MAP_PERSISTENT_BIT | CgGL.GL_MAP_COHERENT_BIT;
        gl.glBufferStorage(CgGL.GL_PIXEL_UNPACK_BUFFER, bytes, flags);
        assertNotNull(gl.glMapBufferRange(CgGL.GL_PIXEL_UNPACK_BUFFER, 0, bytes, flags, null));
        return pbo;
    }

    @Test
    public void halfFloatRoundingCarriesIntoTheExponent() {
        assertEquals(0x3C00, GlPixels.floatToHalf(1f) & 0xFFFF);
        assertEquals(0x4000, GlPixels.floatToHalf(1.9995117f) & 0xFFFF);
        assertEquals(65504f, GlPixels.halfToFloat(GlPixels.floatToHalf(65504f)), 0f);
        assertEquals(0.5f, GlPixels.halfToFloat(GlPixels.floatToHalf(0.5f)), 0f);
    }
}
