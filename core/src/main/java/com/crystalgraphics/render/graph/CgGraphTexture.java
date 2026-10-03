package com.crystalgraphics.render.graph;

import com.crystalgraphics.api.texture.CgTexture;
import com.crystalgraphics.gl.framebuffer.CgFrameBuffer;

import javax.annotation.Nullable;

/**
 * A texture a frame graph orders work on: a pass writes it, a draw or a copy reads it. A handle at once, on any
 * thread; its storage is the executor's to resolve on the render thread. It is a {@link CgTexture}, so a binding
 * snapshot takes it like any other.
 *
 * <pre>{@code
 * CgGraphTexture main  = CgGraphTexture.imported("mc_main", mainTarget);          // the host's, as it is
 * CgGraphTexture layer = CgGraphTexture.transientTexture("layer", new CgTextureDesc(w, h, CgTextureDesc.RGBA8));
 * CgGraphTexture view  = CgGraphTexture.requested("preview", desc);               // kept across frames
 * CgGraphTexture here  = CgGraphTexture.current();                                // what is bound when execution begins
 * }</pre>
 *
 * <ul>
 *   <li><b>Transient</b>: storage from a pool for the passes between its first and last use in one frame; its
 *       contents do not survive the frame. Two that never live at once may share storage.</li>
 *   <li><b>Requested</b>: storage made on first use and kept until {@code recording.release(texture)}.</li>
 *   <li>A kernel binds one as a storage image (a {@code CgDispatch}'s {@code image}) where its format is the image's.</li>
 *   <li>Binding one outside the passes that resolve it binds nothing. {@link #delete()} refuses: the graph owns it.</li>
 * </ul>
 */
public final class CgGraphTexture extends CgGraphResource implements CgTexture {

    /** Where its storage comes from. */
    public enum Kind { IMPORTED, CURRENT, TRANSIENT, REQUESTED }

    private static final CgGraphTexture CURRENT = new CgGraphTexture(Kind.CURRENT, "current", null, null);

    private final Kind kind;
    @Nullable
    private final CgTextureDesc desc;

    /** Render thread only: the storage while resolved. */
    @Nullable
    private CgFrameBuffer framebuffer;

    private CgGraphTexture(Kind kind, String name, @Nullable CgTextureDesc desc, @Nullable CgFrameBuffer framebuffer) {
        super(name);
        this.kind = kind;
        this.desc = desc;
        this.framebuffer = framebuffer;
    }

    /** A framebuffer someone else owns, used as it is: the host's main target, a retained surface. */
    public static CgGraphTexture imported(String name, CgFrameBuffer framebuffer) {
        return new CgGraphTexture(Kind.IMPORTED, name, null, framebuffer);
    }

    /** The framebuffer and viewport bound when the frame's execution began, kept across passes into other targets. */
    public static CgGraphTexture current() {
        return CURRENT;
    }

    /** Storage for this frame only, from the pool. */
    public static CgGraphTexture transientTexture(String name, CgTextureDesc desc) {
        return new CgGraphTexture(Kind.TRANSIENT, name, desc, null);
    }

    /** Storage made on first use and kept until released. */
    public static CgGraphTexture requested(String name, CgTextureDesc desc) {
        return new CgGraphTexture(Kind.REQUESTED, name, desc, null);
    }

    public Kind kind() {
        return kind;
    }

    /** What the graph allocates for it; null for an imported or current texture. */
    @Nullable
    public CgTextureDesc desc() {
        return desc;
    }

    @Override
    boolean isTransient() {
        return kind == Kind.TRANSIENT;
    }

    @Override
    boolean outlivesFrame() {
        return kind != Kind.TRANSIENT;
    }

    /** Render thread: its storage now, or null outside the passes that resolve it. */
    @Nullable
    public CgFrameBuffer framebuffer() {
        return framebuffer;
    }

    void resolve(@Nullable CgFrameBuffer storage) {
        this.framebuffer = storage;
    }

    @Nullable
    private CgTexture color() {
        return framebuffer == null ? null : framebuffer.getColorTexture(0);
    }

    @Override
    public void bind() {
        CgTexture color = color();
        if (color != null) color.bind();
    }

    @Override
    public void bind(int unit) {
        CgTexture color = color();
        if (color != null) color.bind(unit);
    }

    @Override
    public int getId() {
        CgTexture color = color();
        return color == null ? 0 : color.getId();
    }

    @Override
    public int getWidth() {
        return framebuffer != null ? framebuffer.getWidth() : desc != null ? desc.width() : 0;
    }

    @Override
    public int getHeight() {
        return framebuffer != null ? framebuffer.getHeight() : desc != null ? desc.height() : 0;
    }

    /** Its colour's mip levels: the description's, or an imported framebuffer's. */
    @Override
    public int getLevels() {
        return desc != null ? desc.levels() : framebuffer != null ? framebuffer.getColorLevels() : 1;
    }

    @Override
    public int getTarget() {
        CgTexture color = color();
        return color == null ? 0 : color.getTarget();
    }

    @Override
    public boolean isDeleted() {
        return false;
    }

    /** Refused: a graph texture is the graph's. A requested one is released through its recording. */
    @Override
    public void delete() {
        throw new UnsupportedOperationException("'" + name + "' belongs to the frame graph: release it through a recording");
    }

    @Override
    public String toString() {
        return "CgGraphTexture(" + kind + " " + name + ")";
    }
}
