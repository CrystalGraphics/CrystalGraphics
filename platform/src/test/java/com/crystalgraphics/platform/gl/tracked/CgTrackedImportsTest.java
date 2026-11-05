package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.rhi.format.CgFormat;
import com.crystalgraphics.platform.rhi.nulldrv.CgNullRHI;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import com.crystalgraphics.platform.rhi.resource.CgGpuTexture;
import org.junit.Test;

import java.nio.ByteBuffer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;

/** The tracked backend's imports: a CgGL name's RHI image and storage, and an RHI image's CgGL name. */
public class CgTrackedImportsTest {

    @Test
    public void aTextureNameIsItsImageAndAnImageGetsOneNameForGood() {
        CgNullRHI device = new CgNullRHI(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        CgTrackedImports imports = new CgTrackedImports(gl);
        int name = gl.glGenTextures();
        gl.glBindTexture(CgGL.GL_TEXTURE_2D, name);
        gl.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8, 4, 4, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        CgGpuTexture image = imports.texture(name, null);
        assertEquals(4, image.desc().width());

        CgGpuTexture made = device.createTexture(new CgGpuTexture.Desc("graph", CgGpuTexture.Kind.D2, CgFormat.RGBA8_UNORM,
                8, 8, 1, 1, 1, CgGpuTexture.Usage.ALL));
        int adopted = imports.name(made);
        assertNotEquals(name, adopted);
        assertEquals("the same name each ask", adopted, imports.name(made));
        assertSame("and the name is the image", made, imports.texture(adopted, made.desc()));
    }

    @Test
    public void aBufferNameIsItsStorageWhereverItMoved() {
        CgNullRHI device = new CgNullRHI(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        CgTrackedImports imports = new CgTrackedImports(gl);
        int name = gl.glGenBuffers();
        gl.glBindBuffer(CgGL.GL_ARRAY_BUFFER, name);
        gl.glBufferData(CgGL.GL_ARRAY_BUFFER, 256, CgGL.GL_DYNAMIC_DRAW);
        assertEquals(gl.bufferObjects().get(name).storage.allocation().buffer(), imports.buffer(name));
        assertEquals(gl.bufferObjects().get(name).storage.allocation().offset(), imports.bufferOffset(name));
    }

    @Test
    public void aDeviceBufferGetsANameItsStorageAndForgettingDropsIt() {
        CgNullRHI device = new CgNullRHI(8, 8);
        CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);
        CgTrackedImports imports = new CgTrackedImports(gl);
        CgGpuBuffer made = device.createBuffer(new CgGpuBuffer.Desc("graph buffer", 128, CgGpuBuffer.Usage.ALL, false));
        int name = imports.name(made);
        assertEquals("the same name each ask", name, imports.name(made));
        assertSame(made, imports.buffer(name));
        assertEquals(0, imports.bufferOffset(name));
        imports.forget(made);
        assertNotEquals("its name gone: asked again, a new one", name, imports.name(made));
    }
}
