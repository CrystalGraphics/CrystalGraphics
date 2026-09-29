package com.crystalgraphics.platform.gl.tracked;

import com.crystalgraphics.platform.device.CgPassDesc;
import com.crystalgraphics.platform.device.CgRecordingDevice;
import com.crystalgraphics.platform.gl.CgGL;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.junit.Assert.*;

/** Framebuffers on the tracked backend: what is attached is what the next pass renders into, as GL defines it. */
public class CgTrackedFramebuffersTest {

    private final CgRecordingDevice device = new CgRecordingDevice(16, 16);
    private final CgTrackedGLBackend gl = new CgTrackedGLBackend(device, FakeGlslCompiler.EMPTY, true);

    private int texture(int size) {
        int t = gl.glGenTextures();
        gl.glBindTexture(CgGL.GL_TEXTURE_2D, t);
        gl.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8, size, size, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return t;
    }

    private int fboWith(int texture) {
        int f = gl.genFramebuffers();
        gl.bindFramebuffer(CgGL.GL_FRAMEBUFFER, f);
        gl.framebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, texture, 0);
        return f;
    }

    private CgPassDesc lastPass() {
        return device.passes().get(device.passes().size() - 1);
    }

    @Test
    public void aClearUnderAPartialColourMaskIsDrawnThroughIt() {
        fboWith(texture(8));
        gl.glColorMask(false, false, false, true);
        gl.glClearColor(0, 0, 0, 1);
        int mark = device.mark();
        gl.glClear(CgGL.GL_COLOR_BUFFER_BIT);
        gl.endFrame();
        List<String> log = device.logSince(mark);
        assertTrue(log.toString(), log.contains("draw 3 1 0 0"));
        assertTrue("no attachment clear writes the masked channels", log.stream().noneMatch(l -> l.startsWith("clearColor")));
        assertEquals("the pass keeps what the mask protects", CgPassDesc.LoadOp.LOAD, lastPass().colors().get(0).load());
    }

    @Test
    public void anAttachedTextureIsThePassTargetAndARespecifiedOneANewPass() {
        int tex = texture(8);
        fboWith(tex);
        gl.glClearColor(0, 1, 0, 1);
        gl.glClear(CgGL.GL_COLOR_BUFFER_BIT);
        gl.endFrame();
        assertEquals("texture " + tex, lastPass().colors().get(0).view().texture().label());
        assertEquals(CgPassDesc.LoadOp.CLEAR, lastPass().colors().get(0).load());

        gl.glBindTexture(CgGL.GL_TEXTURE_2D, tex);
        gl.glTexImage2D(CgGL.GL_TEXTURE_2D, 0, CgGL.GL_RGBA8, 4, 4, 0, CgGL.GL_RGBA, CgGL.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        gl.glClear(CgGL.GL_COLOR_BUFFER_BIT);
        gl.endFrame();
        assertEquals("the new image, at its new size", 4, lastPass().width());

        gl.bindFramebuffer(CgGL.GL_FRAMEBUFFER, 0);
        gl.glClear(CgGL.GL_COLOR_BUFFER_BIT);
        gl.endFrame();
        assertSame(device.surfaceColor(), lastPass().colors().get(0).view().texture());
    }

    @Test
    public void aMultisampledSourceResolvesWhereAPlainOneBlits() {
        int ms = gl.genFramebuffers();
        gl.bindFramebuffer(CgGL.GL_FRAMEBUFFER, ms);
        int rb = gl.glGenRenderbuffers();
        gl.glBindRenderbuffer(CgGL.GL_RENDERBUFFER, rb);
        gl.glRenderbufferStorageMultisample(CgGL.GL_RENDERBUFFER, 4, CgGL.GL_RGBA8, 8, 8);
        gl.glFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_RENDERBUFFER, rb);
        int plain = fboWith(texture(8));

        gl.bindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, ms);
        gl.bindFramebuffer(CgGL.GL_DRAW_FRAMEBUFFER, plain);
        gl.blitFramebuffer(0, 0, 8, 8, 0, 0, 8, 8, CgGL.GL_COLOR_BUFFER_BIT, CgGL.GL_NEAREST);
        assertTrue(device.log().get(device.log().size() - 1).startsWith("resolve"));

        gl.bindFramebuffer(CgGL.GL_READ_FRAMEBUFFER, plain);
        gl.bindFramebuffer(CgGL.GL_DRAW_FRAMEBUFFER, 0);
        gl.blitFramebuffer(0, 0, 8, 8, 0, 16, 16, 0, CgGL.GL_COLOR_BUFFER_BIT, CgGL.GL_LINEAR);
        assertTrue(device.log().get(device.log().size() - 1).endsWith("0,16,16,0 LINEAR"));
    }

    @Test
    public void statusAndAttachmentQueriesAnswerAsGlDoes() {
        int f = gl.genFramebuffers();
        gl.bindFramebuffer(CgGL.GL_FRAMEBUFFER, f);
        assertEquals(0x8CD7, gl.checkFramebufferStatus(CgGL.GL_FRAMEBUFFER));   // missing attachment
        gl.framebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, texture(8), 0);
        int rb = gl.glGenRenderbuffers();
        gl.glBindRenderbuffer(CgGL.GL_RENDERBUFFER, rb);
        gl.glRenderbufferStorage(CgGL.GL_RENDERBUFFER, CgGL.GL_DEPTH24_STENCIL8, 8, 8);
        gl.glFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER, CgGL.GL_DEPTH_STENCIL_ATTACHMENT, CgGL.GL_RENDERBUFFER, rb);
        assertEquals(CgGL.GL_FRAMEBUFFER_COMPLETE, gl.checkFramebufferStatus(CgGL.GL_FRAMEBUFFER));
        assertEquals(24, gl.getFramebufferAttachmentParameteriv(CgGL.GL_FRAMEBUFFER, CgGL.GL_DEPTH_ATTACHMENT, 0x8216));
        assertEquals(CgGL.GL_RENDERBUFFER, gl.getFramebufferAttachmentParameteriv(CgGL.GL_FRAMEBUFFER,
                CgGL.GL_STENCIL_ATTACHMENT, CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE));
    }

    @Test
    public void aFramebufferWithNothingAttachedBindsAndRefusesToDraw() {
        int f = gl.genFramebuffers();
        gl.bindFramebuffer(CgGL.GL_FRAMEBUFFER, f);
        assertEquals(CgGL.GL_NO_ERROR, gl.glGetError());
        gl.glClear(CgGL.GL_COLOR_BUFFER_BIT);
        assertEquals(CgGL.GL_INVALID_FRAMEBUFFER_OPERATION, gl.glGetError());
    }
}
