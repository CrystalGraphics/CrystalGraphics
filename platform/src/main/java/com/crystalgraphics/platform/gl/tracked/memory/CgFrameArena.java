package com.crystalgraphics.platform.gl.tracked.memory;

import java.util.ArrayDeque;

/**
 * Memory for one frame's uploads, a draw's or a dispatch's uniforms: bump-allocated from host-visible pages, each page
 * taken again once the last frame that wrote it has retired. An upload allocates nothing and looks nothing up.
 *
 * <pre>{@code
 * CgFrameArena arena = new CgFrameArena(host, alignment);
 * long at = arena.allocate(size, device.frameIndex(), device.retiredFrame());
 * arena.page().put(at, bytes);                     // the range is page() at at, until the next allocate
 * }</pre>
 *
 * <ul>
 *   <li>At most {@link #PAGE} bytes a request: a larger one belongs in an allocation of its own.</li>
 *   <li>Render thread only, like the tracker that owns it.</li>
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
