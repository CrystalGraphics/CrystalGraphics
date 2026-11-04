package com.crystalgraphics.platform.rhi.memory;

import java.util.ArrayDeque;

/**
 * Memory for one frame's uploads, a draw's or a dispatch's constants: bump-allocated from persistently mapped pages,
 * each page taken again once the last frame that wrote it has retired. Once warm, an upload allocates nothing and
 * looks nothing up; the bytes are written once, straight into the memory the GPU reads.
 *
 * <pre>{@code
 * CgFrameArena arena = new CgFrameArena(new CgSlabAllocator(rhi, true, false, 1 << 22, 256), 256);
 * long at = arena.allocate(64, rhi.frameIndex(), rhi.retiredFrame());
 * CgAllocation page = arena.page();               // valid until the next allocate
 * ByteBuffer mapped = page.buffer().mapped();
 * int base = (int) (page.offset() + at);
 * mapped.putFloat(base, time).putInt(base + 4, frame);   // in place: no staging copy
 * bindings.buffer(0, CgBindingLayout.Type.UNIFORM_BUFFER, page.buffer(), page.offset() + at, 64);
 * }</pre>
 *
 * <p>Bytes already in a buffer of their own go in with {@code page.put(at, bytes)}.</p>
 *
 * <ul>
 *   <li>At most {@link #PAGE} bytes a request: a larger one belongs in an allocation of its own.</li>
 *   <li>One thread: the one recording the frame.</li>
 * </ul>
 */
public final class CgFrameArena {

    public static final long PAGE = 256L << 10;

    private final CgSlabAllocator host;
    private final long alignment;
    /** Pages filled, oldest first, each with the newest frame that wrote it. */
    private final ArrayDeque<CgAllocation> filled = new ArrayDeque<>();
    private CgAllocation page;
    private long cursor;

    public CgFrameArena(CgSlabAllocator host, long alignment) {
        this.host = host;
        this.alignment = alignment;
    }

    /** Reserves {@code size} bytes for {@code frame}, and answers where they start in {@link #page()}. */
    public long allocate(long size, long frame, long retired) {
        if (size > PAGE) throw new IllegalArgumentException(size + " bytes is more than a page of " + PAGE);
        long at = (cursor + alignment - 1) / alignment * alignment;
        if (page == null || at + size > PAGE) {
            if (page != null) filled.addLast(page);
            CgAllocation oldest = filled.peekFirst();
            page = oldest != null && oldest.lastUse <= retired ? filled.pollFirst() : host.allocate(PAGE, "frame uploads");
            at = 0;
        }
        page.lastUse = frame;
        cursor = at + size;
        return at;
    }

    /** The page the last {@link #allocate} answered an offset in. */
    public CgAllocation page() {
        return page;
    }
}
