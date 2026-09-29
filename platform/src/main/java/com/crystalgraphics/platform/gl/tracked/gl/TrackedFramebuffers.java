package com.crystalgraphics.platform.gl.tracked.gl;

import com.crystalgraphics.platform.device.CgDevice;
import com.crystalgraphics.platform.device.command.CgCommandEncoder;
import com.crystalgraphics.platform.device.format.CgFormat;
import com.crystalgraphics.platform.device.resource.CgGpuSampler;
import com.crystalgraphics.platform.device.resource.CgGpuTexture;
import com.crystalgraphics.platform.device.resource.CgTextureRegion;
import com.crystalgraphics.platform.device.resource.CgTextureView;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.platform.gl.tracked.memory.CgAllocation;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTarget;
import com.crystalgraphics.platform.gl.tracked.tracker.CgTracker;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Framebuffer and renderbuffer objects. A framebuffer is attachments and a mapping of fragment outputs to them;
 * the draw framebuffer becomes the tracker's target, rebuilt only when an attached image was replaced. Name 0 is
 * the device's surface.
 */
public final class TrackedFramebuffers {

    static final int COLORS = 8;
    private static final int GL_RENDERBUFFER_BINDING = 0x8CA7, GL_READ_BUFFER = 0x0C02, GL_DRAW_BUFFER0 = 0x8825;
    private static final int GL_BACK = 0x0405, GL_BACK_LEFT = 0x0402;
    private static final int GL_INCOMPLETE_ATTACHMENT = 0x8CD6, GL_INCOMPLETE_MISSING_ATTACHMENT = 0x8CD7;
    private static final int GL_INCOMPLETE_DRAW_BUFFER = 0x8CDB, GL_INCOMPLETE_MULTISAMPLE = 0x8D56;
    private static final int GL_OBJECT_NAME = 0x8CD1, GL_TEXTURE_LEVEL = 0x8CD2, GL_CUBE_MAP_FACE = 0x8CD3;
    private static final int GL_TEXTURE_LAYER = 0x8CD4, GL_FRAMEBUFFER_DEFAULT = 0x8218, GL_TEXTURE = 0x1702;
    private static final int GL_RED_SIZE = 0x8212, GL_ALPHA_SIZE = 0x8215, GL_DEPTH_SIZE = 0x8216, GL_STENCIL_SIZE = 0x8217;

    static final class Renderbuffer {
        final int name;
        CgGpuTexture image;

        Renderbuffer(int name) { this.name = name; }
    }

    static final class Attachment {
        boolean renderbuffer;
        int name, level, layer;

        void set(boolean renderbuffer, int name, int level, int layer) {
            this.renderbuffer = renderbuffer;
            this.name = name;
            this.level = level;
            this.layer = layer;
        }
    }

    static final class Framebuffer {
        final int name;
        final Attachment[] colors = new Attachment[COLORS];
        final Attachment depth = new Attachment(), stencil = new Attachment();
        int[] drawBuffers = {0};
        int readBuffer = 0;
        CgTarget target;
        CgGpuTexture[] targetImages;

        Framebuffer(int name) {
            this.name = name;
            for (int i = 0; i < COLORS; i++) colors[i] = new Attachment();
        }

        void changed() { target = null; }
    }

    private final CgTracker tracker;
    private final CgDevice device;
    private final TrackedGlErrors errors;
    private final TrackedTextures textures;
    private final TrackedBuffers buffers;
    private final GlNames<Framebuffer> fbos = new GlNames<>("Framebuffer");
    private final GlNames<Renderbuffer> rbos = new GlNames<>("Renderbuffer");
    private int draw, read, renderbuffer;
    private int surfaceReadBuffer = 0;
    private CgTarget surface;
    private CgGpuTexture surfaceImage;

    public TrackedFramebuffers(CgTracker tracker, TrackedGlErrors errors, TrackedTextures textures, TrackedBuffers buffers) {
        this.tracker = tracker;
        this.device = tracker.device();
        this.errors = errors;
        this.textures = textures;
        this.buffers = buffers;
    }

    public int drawName() { return draw; }

    public int readName() { return read; }

    // ── objects ────────────────────────────────────────────────────────────────

    public int gen() {
        int name = fbos.next();
        return fbos.add(new Framebuffer(name));
    }

    public void bind(int target, int name) {
        if (name != 0 && !fbos.exists(name)) { errors.invalidOperation("glBindFramebuffer: " + name + " was never generated"); return; }
        if (target == CgGL.GL_FRAMEBUFFER || target == CgGL.GL_DRAW_FRAMEBUFFER) draw = name;
        if (target == CgGL.GL_FRAMEBUFFER || target == CgGL.GL_READ_FRAMEBUFFER) read = name;
        if (target != CgGL.GL_FRAMEBUFFER && target != CgGL.GL_DRAW_FRAMEBUFFER && target != CgGL.GL_READ_FRAMEBUFFER)
            errors.invalidEnum("glBindFramebuffer", target);
        applyDraw();
    }

    public void delete(int name) {
        if (fbos.remove(name) == null) return;
        if (draw == name) draw = 0;
        if (read == name) read = 0;
        applyDraw();
    }

    public int genRenderbuffer() {
        int name = rbos.next();
        return rbos.add(new Renderbuffer(name));
    }

    public void bindRenderbuffer(int name) {
        if (name != 0 && !rbos.exists(name)) { errors.invalidOperation("glBindRenderbuffer: " + name + " was never generated"); return; }
        renderbuffer = name;
    }

    public void deleteRenderbuffer(int name) {
        Renderbuffer r = rbos.remove(name);
        if (r == null) return;
        if (r.image != null) tracker.release(r.image);
        if (renderbuffer == name) renderbuffer = 0;
        detach(true, name);
    }

    public void renderbufferStorage(int samples, int internalFormat, int width, int height) {
        if (renderbuffer == 0) { errors.invalidOperation("glRenderbufferStorage with no renderbuffer bound"); return; }
        Renderbuffer r = rbos.get(renderbuffer);
        if (r.image != null) tracker.release(r.image);
        int s = Math.max(1, Math.min(samples, device.info().limits().maxSamples()));
        r.image = device.createTexture(new CgGpuTexture.Desc("renderbuffer " + r.name, CgGpuTexture.Kind.D2,
                GlPixels.device(internalFormat), width, height, 1, 1, s,
                EnumSet.of(CgGpuTexture.Usage.ATTACHMENT, CgGpuTexture.Usage.COPY_SRC, CgGpuTexture.Usage.COPY_DST)));
    }

    public CgGpuTexture renderbufferImage(int name) {
        Renderbuffer r = rbos.exists(name) ? rbos.get(name) : null;
        return r == null ? null : r.image;
    }

    // ── attachments ────────────────────────────────────────────────────────────

    public void texture(int target, int attachment, int texTarget, int texture, int level) {
        int layer = texTarget >= CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_X && texTarget <= CgGL.GL_TEXTURE_CUBE_MAP_NEGATIVE_Z
                ? texTarget - CgGL.GL_TEXTURE_CUBE_MAP_POSITIVE_X : 0;
        attach(target, attachment, false, texture, level, layer);
    }

    public void textureLayer(int target, int attachment, int texture, int level, int layer) {
        attach(target, attachment, false, texture, level, layer);
    }

    public void renderbufferAttachment(int target, int attachment, int rb) {
        attach(target, attachment, true, rb, 0, 0);
    }

    private void attach(int target, int attachment, boolean rb, int name, int level, int layer) {
        Framebuffer f = bound(target, "glFramebufferTexture");
        if (f == null) return;
        if (attachment == CgGL.GL_DEPTH_STENCIL_ATTACHMENT) {
            f.depth.set(rb, name, level, layer);
            f.stencil.set(rb, name, level, layer);
        } else if (attachment == CgGL.GL_DEPTH_ATTACHMENT) {
            f.depth.set(rb, name, level, layer);
        } else if (attachment == CgGL.GL_STENCIL_ATTACHMENT) {
            f.stencil.set(rb, name, level, layer);
        } else if (attachment >= CgGL.GL_COLOR_ATTACHMENT0 && attachment < CgGL.GL_COLOR_ATTACHMENT0 + COLORS) {
            f.colors[attachment - CgGL.GL_COLOR_ATTACHMENT0].set(rb, name, level, layer);
        } else {
            errors.invalidEnum("glFramebufferTexture attachment", attachment);
            return;
        }
        f.changed();
        applyDraw();
    }

    /** A deleted texture or renderbuffer leaves the bound framebuffers, as GL detaches it. */
    public void detach(boolean rb, int name) {
        for (int n : new int[] {draw, read}) {
            if (n == 0) continue;
            Framebuffer f = fbos.get(n);
            for (Attachment a : f.colors) if (a.name == name && a.renderbuffer == rb) a.set(false, 0, 0, 0);
            if (f.depth.name == name && f.depth.renderbuffer == rb) f.depth.set(false, 0, 0, 0);
            if (f.stencil.name == name && f.stencil.renderbuffer == rb) f.stencil.set(false, 0, 0, 0);
            f.changed();
        }
        applyDraw();
    }

    public void drawBuffers(IntBuffer bufs) {
        int n = bufs.remaining();
        int[] map = new int[n];
        for (int i = 0; i < n; i++) map[i] = bufferIndex(bufs.get(bufs.position() + i));
        setDrawBuffers(map);
    }

    public void drawBuffer(int mode) {
        setDrawBuffers(new int[] {bufferIndex(mode)});
    }

    public void readBuffer(int mode) {
        if (read == 0) surfaceReadBuffer = bufferIndex(mode);
        else fbos.get(read).readBuffer = bufferIndex(mode);
    }

    private void setDrawBuffers(int[] map) {
        if (draw == 0) return;              // the surface has one colour buffer, which stays the output
        Framebuffer f = fbos.get(draw);
        f.drawBuffers = map;
        f.changed();
        applyDraw();
    }

    private static int bufferIndex(int mode) {
        if (mode == CgGL.GL_NONE) return -1;
        if (mode == GL_BACK || mode == GL_BACK_LEFT) return 0;
        return mode - CgGL.GL_COLOR_ATTACHMENT0;
    }

    // ── the target ─────────────────────────────────────────────────────────────

    /** Binds the draw framebuffer's attachments as the tracker's target; false, binding nothing, when it has none. */
    public boolean applyDraw() {
        CgTarget t = target(draw);
        if (t != null) tracker.bindTarget(t);
        return t != null;
    }

    /** The framebuffer's target, or {@code null} while nothing is attached. */
    CgTarget target(int name) {
        if (name == 0) {
            if (surface == null || surfaceImage != device.surfaceColor()) {
                surface = CgTarget.surface(device);
                surfaceImage = device.surfaceColor();
            }
            return surface;
        }
        Framebuffer f = fbos.get(name);
        if (f.target != null && sameImages(f)) return f.target;
        List<CgTextureView> colors = new ArrayList<>();
        boolean gap = false;
        for (int out : f.drawBuffers) {
            CgTextureView v = out < 0 ? null : view(f.colors[out]);
            if (v == null) { gap = true; continue; }
            if (gap) throw new UnsupportedOperationException("Framebuffer " + name + ": a draw buffer after GL_NONE");
            colors.add(v);
        }
        CgTextureView depth = view(f.depth);
        CgTextureView stencil = view(f.stencil);
        if (depth != null && stencil != null && depth.texture() != stencil.texture())
            throw new UnsupportedOperationException("Framebuffer " + name + ": separate depth and stencil images");
        if (depth == null) depth = stencil;
        if (colors.isEmpty() && depth == null) return null;
        f.target = new CgTarget(List.copyOf(colors), depth);
        f.targetImages = images(f);
        return f.target;
    }

    private boolean sameImages(Framebuffer f) {
        CgGpuTexture[] now = images(f);
        for (int i = 0; i < now.length; i++) if (now[i] != f.targetImages[i]) return false;
        return true;
    }

    private CgGpuTexture[] images(Framebuffer f) {
        CgGpuTexture[] out = new CgGpuTexture[COLORS + 2];
        for (int i = 0; i < COLORS; i++) out[i] = image(f.colors[i]);
        out[COLORS] = image(f.depth);
        out[COLORS + 1] = image(f.stencil);
        return out;
    }

    private CgGpuTexture image(Attachment a) {
        if (a.name == 0) return null;
        if (a.renderbuffer) return renderbufferImage(a.name);
        TrackedTextures.GlTexture t = textures.get(a.name);
        return t == null ? null : t.image;
    }

    private CgTextureView view(Attachment a) {
        CgGpuTexture image = image(a);
        return image == null ? null : CgTextureView.attachment(image, a.level, a.layer);
    }

    // ── status and queries ─────────────────────────────────────────────────────

    public int status(int target) {
        int name = target == CgGL.GL_READ_FRAMEBUFFER ? read : draw;
        if (name == 0) return CgGL.GL_FRAMEBUFFER_COMPLETE;
        Framebuffer f = fbos.get(name);
        boolean any = false;
        int samples = -1;
        for (Attachment a : attachments(f)) {
            if (a.name == 0) continue;
            CgGpuTexture image = image(a);
            if (image == null) return GL_INCOMPLETE_ATTACHMENT;
            if (samples >= 0 && image.desc().samples() != samples) return GL_INCOMPLETE_MULTISAMPLE;
            samples = image.desc().samples();
            any = true;
        }
        if (!any) return GL_INCOMPLETE_MISSING_ATTACHMENT;
        for (int out : f.drawBuffers) if (out >= 0 && f.colors[out].name == 0) return GL_INCOMPLETE_DRAW_BUFFER;
        return CgGL.GL_FRAMEBUFFER_COMPLETE;
    }

    public int attachmentParameter(int target, int attachment, int pname) {
        int name = target == CgGL.GL_READ_FRAMEBUFFER ? read : draw;
        Attachment a;
        CgGpuTexture image;
        if (name == 0) {
            if (pname == CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE) return GL_FRAMEBUFFER_DEFAULT;
            image = attachment == CgGL.GL_DEPTH || attachment == CgGL.GL_STENCIL ? device.surfaceDepth() : device.surfaceColor();
            a = null;
        } else {
            Framebuffer f = fbos.get(name);
            a = attachment == CgGL.GL_DEPTH_ATTACHMENT || attachment == CgGL.GL_DEPTH_STENCIL_ATTACHMENT ? f.depth
                    : attachment == CgGL.GL_STENCIL_ATTACHMENT ? f.stencil : f.colors[attachment - CgGL.GL_COLOR_ATTACHMENT0];
            image = image(a);
        }
        switch (pname) {
            case CgGL.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE:
                return a.name == 0 ? CgGL.GL_NONE : a.renderbuffer ? CgGL.GL_RENDERBUFFER : GL_TEXTURE;
            case GL_OBJECT_NAME: return a == null ? 0 : a.name;
            case GL_TEXTURE_LEVEL: return a == null ? 0 : a.level;
            case GL_TEXTURE_LAYER: case GL_CUBE_MAP_FACE: return a == null ? 0 : a.layer;
            case GL_DEPTH_SIZE: return image == null ? 0 : depthBits(image.desc().format());
            case GL_STENCIL_SIZE: return image == null || !image.desc().format().hasStencil() ? 0 : 8;
            default:
                if (pname >= GL_RED_SIZE && pname <= GL_ALPHA_SIZE) return image == null ? 0 : colorBits(image.desc().format());
                errors.invalidEnum("glGetFramebufferAttachmentParameteriv", pname);
                return 0;
        }
    }

    private static int depthBits(CgFormat f) {
        switch (f) {
            case DEPTH16_UNORM: return 16;
            case DEPTH24_PLUS: case DEPTH24_PLUS_STENCIL8: return 24;
            case DEPTH32_FLOAT: case DEPTH32_FLOAT_STENCIL8: return 32;
            default: return 0;
        }
    }

    private static int colorBits(CgFormat f) {
        if (f.hasDepth() || f.hasStencil()) return 0;
        int components = f.name().startsWith("RGBA") || f.name().startsWith("BGRA") ? 4 : f.name().startsWith("RG") ? 2 : 1;
        return f.bytes() * 8 / components;
    }

    private static Attachment[] attachments(Framebuffer f) {
        Attachment[] all = new Attachment[COLORS + 2];
        System.arraycopy(f.colors, 0, all, 0, COLORS);
        all[COLORS] = f.depth;
        all[COLORS + 1] = f.stencil;
        return all;
    }

    // ── transfers ──────────────────────────────────────────────────────────────

    /** {@code glBlitFramebuffer}: every draw buffer from the read buffer, and depth or stencil when asked. */
    public void blit(int sx0, int sy0, int sx1, int sy1, int dx0, int dy0, int dx1, int dy1, int mask, int filter) {
        CgGpuSampler.Filter f = filter == CgGL.GL_LINEAR ? CgGpuSampler.Filter.LINEAR : CgGpuSampler.Filter.NEAREST;
        CgTarget src = target(read), dst = target(draw);
        if (src == null || dst == null) { errors.invalidFramebufferOperation("glBlitFramebuffer with nothing attached"); return; }
        CgCommandEncoder enc = tracker.transfer();
        if ((mask & CgGL.GL_COLOR_BUFFER_BIT) != 0) {
            CgTextureView from = readView(src);
            for (CgTextureView to : dst.colors()) blitOne(enc, from, sx0, sy0, sx1, sy1, to, dx0, dy0, dx1, dy1, f);
        }
        if ((mask & (CgGL.GL_DEPTH_BUFFER_BIT | CgGL.GL_STENCIL_BUFFER_BIT)) != 0 && src.depth() != null && dst.depth() != null) {
            blitOne(enc, src.depth(), sx0, sy0, sx1, sy1, dst.depth(), dx0, dy0, dx1, dy1, CgGpuSampler.Filter.NEAREST);
        }
    }

    private static void blitOne(CgCommandEncoder enc, CgTextureView from, int sx0, int sy0, int sx1, int sy1,
                                CgTextureView to, int dx0, int dy0, int dx1, int dy1, CgGpuSampler.Filter filter) {
        if (from.texture().desc().samples() > 1) enc.resolve(from, to);
        else enc.blit(from, sx0, sy0, sx1, sy1, to, dx0, dy0, dx1, dy1, filter);
    }

    private CgTextureView readView(CgTarget src) {
        int index = read == 0 ? surfaceReadBuffer : fbos.get(read).readBuffer;
        if (read == 0) return src.colors().get(0);
        CgTextureView v = view(fbos.get(read).colors[Math.max(0, index)]);
        if (v == null) throw new IllegalStateException("Framebuffer " + read + " has no read buffer attached");
        return v;
    }

    /** {@code glReadPixels} into client memory: read back, then GL's pack. */
    public void readPixels(int x, int y, int width, int height, int format, int type, GlPixels.Store pack, ByteBuffer out) {
        CgTarget src = target(read);
        if (src == null) { errors.invalidFramebufferOperation("glReadPixels with nothing attached"); return; }
        CgTextureView v = format == CgGL.GL_DEPTH_COMPONENT ? src.depth() : readView(src);
        CgFormat f = v.texture().desc().format();
        ByteBuffer texels = ByteBuffer.allocateDirect(width * height * f.bytes()).order(ByteOrder.nativeOrder());
        tracker.transfer().readTexture(v.texture(), new CgTextureRegion(v.baseMip(), x, y, v.baseLayer(), width, height, 1), texels);
        GlPixels.pack(texels, f, width, height, format, type, pack, out);
    }

    /** {@code glReadPixels} into the pixel-pack buffer, without waiting: only where the layouts already agree. */
    public void readPixels(int x, int y, int width, int height, int format, int type, long offset) {
        TrackedBuffers.GlBuffer pbo = buffers.get(buffers.pixelPack);
        if (pbo == null) { errors.invalidOperation("glReadPixels to an offset with no pixel-pack buffer"); return; }
        CgTarget src = target(read);
        if (src == null) { errors.invalidFramebufferOperation("glReadPixels with nothing attached"); return; }
        CgTextureView v = readView(src);
        CgFormat f = v.texture().desc().format();
        boolean direct = (f == CgFormat.RGBA8_UNORM || f == CgFormat.RGBA8_SRGB) && format == CgGL.GL_RGBA && type == CgGL.GL_UNSIGNED_BYTE
                || f == CgFormat.RGBA32_FLOAT && format == CgGL.GL_RGBA && type == CgGL.GL_FLOAT;
        if (!direct) throw new UnsupportedOperationException("glReadPixels of " + f + " into a buffer as 0x"
                + Integer.toHexString(format) + "/0x" + Integer.toHexString(type));
        CgAllocation a = pbo.storage.allocation();
        tracker.transfer().copyTextureToBuffer(v.texture(), new CgTextureRegion(v.baseMip(), x, y, v.baseLayer(), width, height, 1),
                a.buffer(), a.offset() + offset);
        tracker.markUsed(a);
    }

    public int query(int pname, double[] out) {
        switch (pname) {
            case CgGL.GL_DRAW_FRAMEBUFFER_BINDING: return TrackedRenderState.one(out, draw);
            case CgGL.GL_READ_FRAMEBUFFER_BINDING: return TrackedRenderState.one(out, read);
            case GL_RENDERBUFFER_BINDING: return TrackedRenderState.one(out, renderbuffer);
            case GL_READ_BUFFER: {
                int index = read == 0 ? surfaceReadBuffer : fbos.get(read).readBuffer;
                return TrackedRenderState.one(out, index < 0 ? CgGL.GL_NONE : read == 0 ? GL_BACK : CgGL.GL_COLOR_ATTACHMENT0 + index);
            }
            default:
                if (pname >= GL_DRAW_BUFFER0 && pname < GL_DRAW_BUFFER0 + COLORS) {
                    int i = pname - GL_DRAW_BUFFER0;
                    if (draw == 0) return TrackedRenderState.one(out, i == 0 ? GL_BACK : CgGL.GL_NONE);
                    int[] map = fbos.get(draw).drawBuffers;
                    int index = i < map.length ? map[i] : -1;
                    return TrackedRenderState.one(out, index < 0 ? CgGL.GL_NONE : CgGL.GL_COLOR_ATTACHMENT0 + index);
                }
                return -1;
        }
    }

    private Framebuffer bound(int target, String call) {
        int name = target == CgGL.GL_READ_FRAMEBUFFER ? read : draw;
        if (name == 0) { errors.invalidOperation(call + " on the default framebuffer"); return null; }
        return fbos.get(name);
    }
}
