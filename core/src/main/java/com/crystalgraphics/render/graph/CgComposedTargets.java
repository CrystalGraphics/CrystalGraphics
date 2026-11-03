package com.crystalgraphics.render.graph;

import com.crystalgraphics.gl.CgGlNames;
import com.crystalgraphics.api.texture.CgTextureType;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;
import com.crystalgraphics.variable.CgVar;

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
    private static final int TYPES = CgTextureType.values().length;
    /** By our slot 0's ordinal and our slot 1's (0 for none, else its ordinal plus 1). */
    private final boolean[] checked = new boolean[TYPES * (TYPES + 1)];
    private final IntBuffer drawBuffers = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()).asIntBuffer();
    /** In the form without the host's colour, how many draw buffers its framebuffer was given: 1, GL's default. */
    private int drawn = 1;
    /**
     * The host framebuffer and size whose attachments {@link #hostAttachments} holds, 0 for none. Kept until either
     * changes or the window resizes: a glGet waits for a threaded driver to drain its queue, which held the frame's
     * CPU to the GPU's pace.
     */
    private int readHost, readWidth, readHeight;
    /** Its colour's type, name and level, then its depth's and its stencil's type and name. */
    private final int[] hostAttachments = new int[7];
    private static final int COLOR0 = 0, COLOR1 = 1, DEPTH = 2, STENCIL = 3;
    /**
     * What each point of our framebuffer holds, as {@link #key}; 0 for nothing. A bind attaches only what differs: on a
     * threaded driver an attach costs about 2 µs and a framebuffer bind 4 (T11 P0.3), and a frame made ~50 of them.
     */
    private final long[] attached = new long[4];
    /** Off: every bind attaches everything and each pass detaches, as before T11, to compare. */
    static final boolean KEEP = CgVar.bool("crystalgraphics.graph.keepComposed", true, CgVar.DEV | CgVar.RESTART,
            "Keeps a composed framebuffer's attachments across an execution's passes; off, attached each pass.").get();

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
     * {@code texture} (of {@code type}) as colour attachment 1 (or 0), and answers null; or, with {@code host} bound and
     * nothing of ours, why {@code host} takes none. Binds {@code host} itself where it must read its attachments.
     */
    String bind(int host, int width, int height, int texture, CgTextureType type) {
        return bind(host, width, height, texture, type, 0, null);
    }

    /**
     * As {@link #bind(int, int, int, int, CgTextureType)}; in the form without the host's colour, {@code second} (of
     * {@code secondType}) at colour attachment 1 where it is not 0: a pass into a texture beside the host's depth that
     * takes a second attachment too.
     */
    String bind(int host, int width, int height, int texture, CgTextureType type, int second, CgTextureType secondType) {
        if (host == 0) return "the default framebuffer lends no attachment to a framebuffer of ours";
        if (!KEEP) CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, host);
        if (readHost != host || readWidth != width || readHeight != height) {
            CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, host);
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
                return refusal(host, "framebuffer " + host + " has no colour attachment 0 to draw beside");
            }
            colorName = a[1];
            colorLevel = a[2];
        }
        int depthType = a[3], depthName = a[4], stencilType = a[5], stencilName = a[6];
        if (!hostColor && depthName == 0) return refusal(host, "framebuffer " + host + " has no depth to draw beside");
        boolean made = fbo == 0;
        if (made) fbo = CgGlNames.framebuffer();
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo);
        // Attached where they differ, and detached at the execution's end (detach): between executions a host that
        // deletes its target and makes another gets the same names back, and a framebuffer still holding the deleted
        // objects would draw into them. Within one execution the host draws nothing.
        point(COLOR0, CgGL.GL_COLOR_ATTACHMENT0, colorType, colorName, colorLevel, false);
        boolean depthStencil = depthName != 0 && stencilName == depthName;
        if (depthStencil) {
            point(DEPTH, CgGL.GL_DEPTH_STENCIL_ATTACHMENT, depthType, depthName, 0, true);
            attached[STENCIL] = attached[DEPTH];
        } else {
            point(DEPTH, CgGL.GL_DEPTH_ATTACHMENT, depthType, depthName, 0, false);
            point(STENCIL, CgGL.GL_STENCIL_ATTACHMENT, stencilType, stencilName, 0, false);
        }
        if (hostColor) {
            point(COLOR1, CgGL.GL_COLOR_ATTACHMENT1, GL_TEXTURE, texture, 0, false);
            if (made) CgGL.glDrawBuffers(drawBuffers);
        } else {
            point(COLOR1, CgGL.GL_COLOR_ATTACHMENT1, second != 0 ? GL_TEXTURE : CgGL.GL_NONE, second, 0, false);
            int buffers = second != 0 ? 2 : 1;
            if (buffers != drawn) {
                drawBuffers.limit(buffers);
                CgGL.glDrawBuffers(drawBuffers);
                drawBuffers.limit(2);
                drawn = buffers;
            }
        }
        int check = type.ordinal() * (TYPES + 1) + (second != 0 ? secondType.ordinal() + 1 : 0);
        if (checked[check]) return null;
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
        checked[check] = true;
        return null;
    }

    /**
     * Detaches everything from the framebuffer, which is left bound, if anything is attached: at the end of each
     * execution (and a callback's), so it never holds the host's images past it. Whether it bound it.
     */
    boolean detach() {
        if (fbo == 0 || (attached[COLOR0] | attached[COLOR1] | attached[DEPTH] | attached[STENCIL]) == 0) return false;
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, fbo);
        // Slot 1 first: a draw buffer left after an empty one is refused on a device.
        if (attached[COLOR1] != 0) CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT1, CgGL.GL_TEXTURE_2D, 0, 0);
        if (attached[COLOR0] != 0) CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_COLOR_ATTACHMENT0, CgGL.GL_TEXTURE_2D, 0, 0);
        if (attached[DEPTH] != 0) CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_DEPTH_ATTACHMENT, CgGL.GL_TEXTURE_2D, 0, 0);
        if (attached[STENCIL] != 0) CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, CgGL.GL_STENCIL_ATTACHMENT, CgGL.GL_TEXTURE_2D, 0, 0);
        Arrays.fill(attached, 0L);
        return true;
    }

    /** {@code why}, with {@code host} bound: a refusal leaves the host's framebuffer bound, as it was asked with. */
    private static String refusal(int host, String why) {
        CgGL.glBindFramebuffer(CgGL.GL_FRAMEBUFFER, host);
        return why;
    }

    /** {@code name} (level {@code level}) of {@code type} at {@code point}, or nothing there, unless it holds it already. */
    private void point(int slot, int point, int type, int name, int level, boolean depthStencil) {
        long key = type == GL_TEXTURE || type == CgGL.GL_RENDERBUFFER
                ? (long) type << 40 | (long) level << 32 | name & 0xFFFFFFFFL | (depthStencil ? 1L << 62 : 0) : 0;
        if (KEEP && attached[slot] == key) return;
        attached[slot] = key;
        if (type == CgGL.GL_RENDERBUFFER) CgGL.glFramebufferRenderbuffer(CgGL.GL_FRAMEBUFFER, point, CgGL.GL_RENDERBUFFER, name);
        else CgGL.glFramebufferTexture2D(CgGL.GL_FRAMEBUFFER, point, CgGL.GL_TEXTURE_2D, type == GL_TEXTURE ? name : 0, level);
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
        drawn = 1;
        Arrays.fill(attached, 0L);
        readHost = 0;
        REFUSED.clear();
    }
}
