package com.crystalgraphics.render.mesh;

import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgStreamBuffer;
import com.crystalgraphics.platform.gl.CgGL;
import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.util.trace.CgChannels;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;

/**
 * Where {@code FRAME} meshes' bytes go: pages of frame-ring storage, each mapped once a frame and written straight
 * from the mesh, with a new page when one is full -- Unreal's dynamic vertex buffer pool. A page never grows, so
 * what a mesh placed early in a frame wrote is still there when it draws.
 *
 * <pre>{@code
 * ByteBuffer out = ring.reserve(vertexBytes, stride, indexBytes);   // at placement
 * mesh.readVertices(0, vertexCount, out);
 * out.position(ring.indexPosition);
 * mesh.readIndices(0, indexCount, out);
 * // ring.page, ring.baseVertex and ring.firstIndex say where it landed
 * ring.commit();                                                   // once, before the frame's first pass
 * }</pre>
 *
 * <ul>
 *   <li>A page committed this frame takes nothing more until the next: a later placement opens another page.</li>
 *   <li>Pages open in order, so the ones a frame no longer needs are at the end, and are freed after
 *       {@link #IDLE_FRAMES} frames unopened.</li>
 *   <li>Render thread only.</li>
 * </ul>
 */
final class CgMeshRing {

    private static final int FIRST_PAGE = 256 << 10;
    private static final int MAX_PAGE = 16 << 20;
    static final int IDLE_FRAMES = 600;
    private static final int PAGES = CgTrace.name("mesh.ring-pages");

    static final class Page {
        final CgStreamBuffer buffer;
        final int bytes;
        ByteBuffer out;
        /** Where the mapped region starts in the buffer, and how much of it this frame wrote. */
        int base, used;
        long openFrame = -1, committedFrame = -1;

        Page(int bytes) {
            this.bytes = bytes;
            this.buffer = CgStreamBuffer.create(CgGL.GL_ARRAY_BUFFER, bytes);
        }
    }

    private final ArrayList<Page> pages = new ArrayList<>();
    /** Every format's pool, whose vertex array over a page goes with the page. */
    private final Collection<CgMeshPool> pools;
    private int open = -1;
    private long frame = Long.MIN_VALUE;

    CgMeshRing(Collection<CgMeshPool> pools) {
        this.pools = pools;
    }

    /** Set by {@link #reserve}: the page written, the base vertex and first index, where the indices go in the map. */
    int page, baseVertex, firstIndex, indexPosition;

    /**
     * Room for one mesh this frame: its vertices at a multiple of {@code stride} in the buffer, its indices at a
     * multiple of four. Answers the mapped bytes positioned for the vertices.
     */
    ByteBuffer reserve(int vertexBytes, int stride, int indexBytes) {
        long now = CgFrameRing.frame();
        if (now != frame) beginFrame(now);
        if (open < 0 || !fits(pages.get(open), vertexBytes, stride, indexBytes)) {
            open = nextPage(vertexBytes + indexBytes + stride + 3);
        }
        Page p = pages.get(open);
        int vertexAt = vertexBytes > 0 ? align(p.base + p.used, stride) : p.base + p.used;
        int indexAt = align(vertexAt + vertexBytes, 4);
        page = open;
        baseVertex = vertexBytes > 0 ? vertexAt / stride : 0;
        firstIndex = indexAt / 4;
        indexPosition = indexAt - p.base;
        p.used = indexAt + indexBytes - p.base;
        p.out.position(vertexAt - p.base);
        return p.out;
    }

    private static boolean fits(Page p, int vertexBytes, int stride, int indexBytes) {
        int vertexAt = vertexBytes > 0 ? align(p.base + p.used, stride) : p.base + p.used;
        return align(vertexAt + vertexBytes, 4) + indexBytes - p.base <= p.bytes;
    }

    /** The first page not yet opened this frame with room for {@code bytes}, mapped; a new one if none. */
    private int nextPage(int bytes) {
        for (int i = open + 1; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p.openFrame != frame && p.bytes >= bytes) return openPage(i);
        }
        int size = pages.isEmpty() ? FIRST_PAGE : Math.min(pages.get(pages.size() - 1).bytes * 2, MAX_PAGE);
        pages.add(new Page(Math.max(size, bytes)));
        CgTrace.marker(CgChannels.GL, "mesh.ringPage", "new, " + (Math.max(size, bytes) >> 10) + " KB");
        return openPage(pages.size() - 1);
    }

    private int openPage(int i) {
        Page p = pages.get(i);
        p.openFrame = frame;
        p.out = p.buffer.map(p.bytes);
        p.base = p.buffer.mappedOffset();
        p.used = 0;
        return i;
    }

    /** Ends this frame's writes: what a mapped tier holds is flushed and unmapped. Before any draw reads a page. */
    void commit() {
        for (int i = 0; i < pages.size(); i++) {
            Page p = pages.get(i);
            if (p.openFrame != frame || p.committedFrame == frame) continue;
            p.buffer.bind();   // another stream may have bound its own since the map
            p.buffer.commit(p.used);
            p.committedFrame = frame;
            p.out = null;
        }
        open = -1;
    }

    Page page(int i) {
        return pages.get(i);
    }

    /** Bytes written this frame, over every page. */
    int usedBytes() {
        int used = 0;
        for (Page p : pages) if (p.openFrame == frame) used += p.used;
        return used;
    }

    private void beginFrame(long now) {
        commit();   // a frame that ended without its upload still unmaps
        frame = now;
        while (!pages.isEmpty()) {
            int last = pages.size() - 1;
            Page p = pages.get(last);
            if (now - p.openFrame < IDLE_FRAMES) break;
            p.buffer.delete();
            pages.remove(last);
            CgTrace.marker(CgChannels.GL, "mesh.ringPage", "freed, " + (p.bytes >> 10) + " KB");
            for (CgMeshPool pool : pools) pool.forgetRingPage(last);
        }
        CgTrace.counter(CgChannels.GL, PAGES, pages.size());
    }

    void delete() {
        for (Page p : pages) p.buffer.delete();
        pages.clear();
        open = -1;
    }

    private static int align(int at, int to) {
        return (at + to - 1) / to * to;
    }
}
