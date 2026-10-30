package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.CgGlNames;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A framebuffer of ours holding part of a target we do not own, its depth and stencil and, in the form with the host's
 * colour, its colour too, beside a colour attachment of ours. With the host's colour, ours is slot 1: what a pass with
 * {@link CgRasterPass#attachment} draws through. Without it, ours is slot 0: what a pass into a
 * {@link CgGraphTexture#besideCurrentDepth} texture draws through. The host's framebuffer is only read, never changed.
 * Render thread.
 */
final class CgComposedTargets {

    private static final int GL_OBJECT_NAME = 0x8CD1, GL_TEXTURE_LEVEL = 0x8CD2, GL_TEXTURE = 0x1702;
    private static final int READ = CgTrace.name("graph.composed.read"), CHECK = CgTrace.name("graph.composed.check");

    /** Framebuffers that took no attachment of ours, read by recorders on any thread. */
    private static final Set<Integer> REFUSED = ConcurrentHashMap.newKeySet();

    private final boolean hostColor;
    private int fbo;
    /**
     * Our formats checked complete beside the host's attachments as read, by ordinal; cleared with each read. Completeness
     * follows formats and sizes, not names, so a pooled texture handed back under another name needs no check.
     */
    private final boolean[] checked = new boolean[CgTextureType.values().length];
    private final IntBuffer drawBuffers = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asIntBuffer();
    /**
     * The host framebuffer and size whose attachments {@link #hostAttachments} holds, 0 for none. Kept until either
     * changes or the window resizes: a glGet waits for a threaded driver to drain its queue, which held the frame's
     * CPU to the GPU's pace.
     */
    private int readHost, readWidth, readHeight;
    /** Its colour's type, name and level, then its depth's and its stencil's type and name. */
    private final int[] hostAttachments = new int[7];

    /** With {@code hostColor}, the host's colour at slot 0 and ours at 1; without, ours at 0 and no host colour. */
    CgComposedTargets(boolean hostColor) {
        this.hostColor = hostColor;
        drawBuffers.put(0, CgGL.GL_COLOR_ATTACHMENT0).put(1, CgGL.GL_COLOR_ATTACHMENT1);
    }

    static boolean refused(int framebuffer) {
        return REFUSED.contains(framebuffer);
    }

    static void refuse(int framebuffer) {
        REFUSED.add(framebuffer);
    }

    /**
     * Binds our framebuffer with {@code host}'s depth and stencil, its colour in the form that takes it, and
     * {@code texture} (of {@code type}) as colour attachment 1 (or 0), and answers null; or, binding nothing of ours,
     * why {@code host} takes none. {@code host} must be bound for drawing when called.
     */
    String bind(int host, int width, int height, int texture, CgTextureType type) {
        if (host == 0) return "the default framebuffer lends no attachment to a framebuffer of ours";
        if (readHost != host || readWidth != width || readHeight != height) {
            try (CgTrace.Zone z = CgTrace.zone(CgChannels.GL_DETAIL, READ)) {
                readAttachments(host);
            }
            readWidth = width;
            readHeight = height;
            Arrays.fill(checked, false);
        }
        int[] a = hostAttachments;
        int colorType = GL_TEXTURE, colorName = texture, colorLevel = 0;
        if (hostColor) {
            colorType = a[0];
            if (colorType != GL_TEXTURE && colorType != CgGL.GL_RENDERBUFFER) {
                return "framebuffer " + host + " has no colour attachment 0 to draw beside";
            }
            colorName = a[1];
            colorLevel = a[2];
        }
        int depthType = a[3], depthName = a[4], stencilType = a[5], stencilName = a[6];
        if (!hostColor && depthName == 0) return "framebuffer " + host + " has no depth to draw beside";
        boolean made = fbo == 0;
        if (made) fbo = CgGlNames.framebuffer();
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo);
        // Attached at every bind and detached after (detach): a host that deletes its target and makes another gets
        // the same names back, and a framebuffer still holding the deleted objects would draw into them.
        if (colorType == GL_TEXTURE) {
            CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, colorName, colorLevel);
        } else {
            CgGL.glFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_RENDERBUFFER, colorName);
        }
        boolean depthStencil = depthName != 0 && stencilName == depthName;
        attach(depthStencil ? CgGL.GL_DEPTH_STENCIL_ATTACHMENT : CgGL.GL_DEPTH_ATTACHMENT, depthType, depthName);
        if (!depthStencil) attach(CgGL.GL_STENCIL_ATTACHMENT, stencilType, stencilName);
        if (hostColor) {
            CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT1, CgGL.GL_TEXTURE_2D, texture, 0);
            if (made) CgGL.glDrawBuffers(drawBuffers);
        }
        if (checked[type.ordinal()]) return null;
        int status;
        try (CgTrace.Zone z = CgTrace.zone(CgChannels.GL_DETAIL, CHECK)) {
            status = CgGL.glCheckFramebufferStatus(CgGL.GL_FRAMEBUFFER);
        }
        if (status != CgGL.GL_FRAMEBUFFER_COMPLETE) {
            detach();
            CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, host);
            return "framebuffer " + host + "'s attachments beside ours are incomplete (0x"
                    + Integer.toHexString(status) + "): a multisampled host target lends none";
        }
        checked[type.ordinal()] = true;
        return null;
    }

    /**
     * Detaches everything from the framebuffer, which is left bound: at the end of each pass, so it never holds the
     * host's images past it.
     */
    void detach() {
        if (fbo == 0) return;
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo);
        // Slot 1 first: a draw buffer left after an empty one is refused on a device.
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT1, CgGL.GL_TEXTURE_2D, 0, 0);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, 0, 0);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_DEPTH_ATTACHMENT, CgGL.GL_TEXTURE_2D, 0, 0);
        CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_STENCIL_ATTACHMENT, CgGL.GL_TEXTURE_2D, 0, 0);
    }

    /** {@code name} of {@code type} at {@code point}, or nothing there. */
    private static void attach(int point, int type, int name) {
        if (type == CgGL.GL_RENDERBUFFER) CgGL.glFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER, point, CgGL.GL_RENDERBUFFER, name);
        else CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, point, CgGL.GL_TEXTURE_2D, type == GL_TEXTURE ? name : 0, 0);
    }

    /** {@code host}'s attachments, read from the driver; {@code host} is bound for drawing. */
    private void readAttachments(int host) {
        int[] a = hostAttachments;
        if (hostColor) {
            a[0] = query(CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            boolean color = a[0] == GL_TEXTURE || a[0] == CgGL.GL_RENDERBUFFER;
            a[1] = color ? query(CgGL.GL_COLOR_ATTACHMENT0, GL_OBJECT_NAME) : 0;
            a[2] = a[0] == GL_TEXTURE ? query(CgGL.GL_COLOR_ATTACHMENT0, GL_TEXTURE_LEVEL) : 0;
        }
        a[3] = query(CgGL.GL_DEPTH_ATTACHMENT, CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        a[4] = a[3] == CgGL.GL_NONE ? 0 : query(CgGL.GL_DEPTH_ATTACHMENT, GL_OBJECT_NAME);
        a[5] = query(CgGL.GL_STENCIL_ATTACHMENT, CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        a[6] = a[5] == CgGL.GL_NONE ? 0 : query(CgGL.GL_STENCIL_ATTACHMENT, GL_OBJECT_NAME);
        readHost = host;
    }

    /**
     * Reads the host's attachments again at their next bind, and checks them again: at a window resize, where a host
     * may remake its target at the same size and name.
     */
    void forgetHost() {
        readHost = 0;   // the next read clears what was checked
    }

    private static int query(int attachment, int pname) {
        return CgGL.glGetFramebufferAttachmentParameteriv(CgGL.GL_FRAMEBUFFER, attachment, pname);
    }

    /** Deletes the framebuffer. At context teardown. */
    void delete() {
        if (fbo != 0) CgGL.glDeleteFramebuffers(fbo);
        fbo = 0;
        readHost = 0;
        REFUSED.clear();
    }
}
