package com.crystalgraphics.platform.gl.command;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.gl.format.GLFormats;
import com.crystalgraphics.platform.gl.resource.GLTexture;
import com.crystalgraphics.platform.rhi.resource.CgTextureView;

import java.util.ArrayList;
import java.util.List;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * Framebuffer objects cached by their attachments, as Unreal's {@code FOpenGLFramebufferCache} keys them by render
 * targets, mips and array indices (`OpenGLRenderTarget.cpp`): made on a miss, dropped with a texture in them.
 *
 * <pre>{@code
 * int fb = framebuffers.get(colors, depth);   // colors: views of mip and layer ranges, depth: null for none
 * }</pre>
 */
public final class GLFramebuffers {

    private static final class Entry {
        final CgTextureView[] colors;
        final CgTextureView depth;
        final int name;

        Entry(CgTextureView[] colors, CgTextureView depth, int name) {
            this.colors = colors;
            this.depth = depth;
            this.name = name;
        }

        boolean matches(CgTextureView[] c, int count, CgTextureView d) {
            if (colors.length != count || !same(depth, d)) return false;
            for (int i = 0; i < count; i++) if (!same(colors[i], c[i])) return false;
            return true;
        }

        boolean holds(GLTexture t) {
            if (depth != null && depth.texture() == t) return true;
            for (CgTextureView v : colors) if (v.texture() == t) return true;
            return false;
        }
    }

    private final CgGLDriver gl;
    private final List<Entry> entries = new ArrayList<>();

    public GLFramebuffers(CgGLDriver gl) {
        this.gl = gl;
    }

    private static boolean same(CgTextureView a, CgTextureView b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        return a.texture() == b.texture() && a.baseMip() == b.baseMip() && a.baseLayer() == b.baseLayer()
                && a.layers() == b.layers();
    }

    /** The framebuffer of {@code count} colour views and {@code depth}, made on the first ask. */
    public int get(CgTextureView[] colors, int count, CgTextureView depth) {
        if (backBuffer(colors, count, depth)) return 0;
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.matches(colors, count, depth)) return e.name;
        }
        int fb = gl.glCreateFramebuffers();
        int[] drawBuffers = new int[count];
        for (int i = 0; i < count; i++) {
            attach(fb, GL_COLOR_ATTACHMENT0 + i, colors[i]);
            drawBuffers[i] = GL_COLOR_ATTACHMENT0 + i;
        }
        if (depth != null) attach(fb, GLFormats.attachment(depth.texture().desc().format(), 0), depth);
        gl.glNamedFramebufferDrawBuffers(fb, drawBuffers);
        int status = gl.glCheckNamedFramebufferStatus(fb, GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            gl.glDeleteFramebuffers(fb);
            throw new IllegalStateException("framebuffer incomplete (0x" + Integer.toHexString(status) + ")");
        }
        CgTextureView[] key = new CgTextureView[count];
        System.arraycopy(colors, 0, key, 0, count);
        entries.add(new Entry(key, depth, fb));
        return fb;
    }

    /** Whether the attachments are the default framebuffer's, which framebuffer 0 alone holds (Unreal's back buffer). */
    private static boolean backBuffer(CgTextureView[] colors, int count, CgTextureView depth) {
        boolean color = count > 0 && ((GLTexture) colors[0].texture()).name == 0;
        boolean z = depth != null && ((GLTexture) depth.texture()).name == 0;
        if (!color && !z) return false;
        if (count > 1 || (count == 1 && !color) || (depth != null && !z)) {
            throw new IllegalArgumentException("the default framebuffer's buffers beside other attachments");
        }
        return true;
    }

    private void attach(int fb, int attachment, CgTextureView v) {
        GLTexture t = (GLTexture) v.texture();
        if (t.target == GL_RENDERBUFFER) {
            gl.glNamedFramebufferRenderbuffer(fb, attachment, t.name);
            return;
        }
        boolean layeredTarget = t.target != GL_TEXTURE_2D && t.target != GL_TEXTURE_2D_MULTISAMPLE;
        if (layeredTarget && v.layers() == 1) gl.glNamedFramebufferTextureLayer(fb, attachment, t.name, v.baseMip(), v.baseLayer());
        else gl.glNamedFramebufferTexture(fb, attachment, t.name, v.baseMip());
    }

    /** Drops every framebuffer {@code texture} is attached to. */
    public void forget(GLTexture texture) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            Entry e = entries.get(i);
            if (!e.holds(texture)) continue;
            gl.glDeleteFramebuffers(e.name);
            entries.remove(i);
        }
    }
}
