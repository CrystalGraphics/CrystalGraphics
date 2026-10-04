package com.crystalgraphics.render.graph;

import com.crystalgraphics.compute.source.CgBufferAccess;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgElementField;
import com.crystalgraphics.gl.buffer.CgFrameRing;
import com.crystalgraphics.gl.buffer.CgReadback;
import com.crystalgraphics.platform.device.command.CgAccess;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * A graph buffer's contents on demand, decoded through the layout of the kernel that binds it: a debugger's buffer
 * view. While watching, every compute pass notes the buffers its dispatches bind, each a {@link Site}; a read of one
 * is served right after its pass next runs, and arrives on the render thread a frame or two later, never stalling.
 *
 * <pre>{@code
 * CgBufferInspector.watch(true);                     // from now on, compute passes note what they bind
 * // ... frames later
 * for (CgBufferInspector.Site site : CgBufferInspector.sites()) System.out.println(site);
 * // sparks after particles.step: 4096 x Spark, 32 bytes each (mymod:shaders/particles.compute Step, STATE)
 *
 * CgBufferInspector.read(site, 0, 64, read -> {
 *     for (int i = 0; i < read.count(); i++) {
 *         StringBuilder row = new StringBuilder("#" + (read.first() + i));
 *         for (CgElementField f : read.site().decl().fields()) row.append("  ").append(f.name()).append(' ').append(read.value(i, f));
 *         System.out.println(row);   // #0  positionLife 1.5, 0.25, -3.0, 0.8  velocitySeed 0.0, 2.0, 0.0, 117.0
 *     }
 * });
 * }</pre>
 *
 * <ul>
 *   <li>What a site shows is the buffer as it stands after its pass: a history's newest version, a lowered kernel's held
 *       output landed first. A buffer several dispatches of the pass bind is shown as the last one writing it binds it.</li>
 *   <li>A read waits for its pass to run again: one that no longer runs never serves it, and it fails once the site
 *       is dropped, {@value #FORGET_AFTER} frames after it was last seen.</li>
 *   <li>An append buffer's count is a site of its own: its buffer as {@code uint} words.</li>
 *   <li>Not watching costs one check per compute pass. Watching notes every binding a pass makes, and a served read
 *       waits for an async pass to finish.</li>
 * </ul>
 */
public final class CgBufferInspector {

    /** Frames a site may go unseen before it is dropped, and the reads waiting on it fail. */
    public static final int FORGET_AFTER = 600;

    private static final Object LOCK = new Object();
    private static volatile boolean watching;
    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static final List<Request> PENDING = new ArrayList<>();
    /** An append buffer's declaration, per buffer, made into its count's: {@code uint} words. */
    private static final Map<CgBufferDecl, CgBufferDecl> COUNTS = new IdentityHashMap<>();
    private static long executions, pruned = -1;

    private CgBufferInspector() {
    }

    /** Where a buffer was seen: the pass that bound it, and the declaration it was bound as. */
    public record Site(String buffer, String pass, String file, String kernel, CgBufferDecl decl, long offset, long bytes,
                       long frame) {

        /** Whole elements in the bound range. */
        public int elements() {
            return (int) Math.min(Integer.MAX_VALUE, bytes / decl.stride());
        }

        @Override
        public String toString() {
            return buffer + " after " + pass + ": " + elements() + " x " + decl.element() + ", " + decl.stride()
                    + " bytes each (" + file + " " + kernel + ", " + decl.name() + ")";
        }
    }

    /** What a read delivers. */
    public interface Sink {

        void accept(Read read);

        /** It will never arrive: the site was dropped, watching stopped, the context went, or the read failed. */
        default void failed(String reason) {
        }
    }

    /** Elements {@code first} to {@code first + count} of a site, as read after its pass ran. */
    public static final class Read {
        private final Site site;
        private final int first, count;
        private final ByteBuffer data;

        Read(Site site, int first, int count, ByteBuffer data) {
            this.site = site;
            this.first = first;
            this.count = count;
            this.data = data;
        }

        /** The site as it stood when the read was served. */
        public Site site() {
            return site;
        }

        public int first() {
            return first;
        }

        /** Elements read: fewer than asked where the site holds fewer. */
        public int count() {
            return count;
        }

        /** The bytes, {@code count() * stride}, native order. */
        public ByteBuffer data() {
            return data.duplicate().order(ByteOrder.nativeOrder());
        }

        /**
         * Field {@code field} of the {@code i}th element read (element {@code first() + i}), each component by its
         * GLSL type, comma-separated: {@code "1.5, 0.25"}. A nested struct's words are hex.
         */
        public String value(int i, CgElementField field) {
            if (i < 0 || i >= count) throw new IndexOutOfBoundsException(i + " of " + count + " elements read");
            return decode(data, i * site.decl.stride(), field);
        }
    }

    /** Starts or stops noting what compute passes bind. Stopping drops every site and fails every read waiting. */
    public static void watch(boolean on) {
        if (on) {
            watching = true;
            return;
        }
        List<Request> failed;
        synchronized (LOCK) {
            watching = false;
            ENTRIES.clear();
            failed = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Request r : failed) r.sink.failed("stopped watching");
    }

    public static boolean watching() {
        return watching;
    }

    /** Every site seen since watching began, in the order first seen. Any thread. */
    public static List<Site> sites() {
        synchronized (LOCK) {
            List<Site> out = new ArrayList<>(ENTRIES.size());
            for (Entry e : ENTRIES) out.add(e.site());
            return out;
        }
    }

    /**
     * Reads {@code count} elements of {@code site} from element {@code first}, the next time its pass runs; the sink
     * runs on the render thread once the GPU has copied them. Any thread.
     *
     * @throws IllegalStateException when not watching, or the site has been dropped
     */
    public static void read(Site site, int first, int count, Sink sink) {
        if (first < 0 || count < 0) throw new IllegalArgumentException("elements " + first + " +" + count);
        synchronized (LOCK) {
            if (!watching) throw new IllegalStateException("not watching: CgBufferInspector.watch(true) first");
            Entry entry = find(site.buffer, site.pass);
            if (entry == null) throw new IllegalStateException(site.buffer + " after " + site.pass + " is no longer seen");
            PENDING.add(new Request(entry, first, count, sink));
        }
    }

    // ── The executor's half ──────────────────────────────────────────────────

    /** A read due now: what the executor copies, and where the bytes go. */
    static final class Request implements CgReadback.Sink {
        final Entry entry;
        final int first, count;
        final Sink sink;
        /** As served: the site then, and the elements it held of those asked for. */
        Site site;
        int at, served;

        Request(Entry entry, int first, int count, Sink sink) {
            this.entry = entry;
            this.first = first;
            this.count = count;
            this.sink = sink;
        }

        CgGraphBuffer view() {
            return entry.view;
        }

        long offset() {
            return site.offset + (long) at * site.decl.stride();
        }

        long size() {
            return (long) served * site.decl.stride();
        }

        @Override
        public void accept(ByteBuffer data) {
            ByteBuffer copy = ByteBuffer.allocate(data.remaining()).order(ByteOrder.nativeOrder());
            copy.put(data.duplicate()).flip();
            sink.accept(new Read(site, at, served, copy));
        }

        @Override
        public void failed(String reason) {
            sink.failed(reason);
        }
    }

    /** One buffer seen after one pass: updated in place each time the pass runs. */
    static final class Entry {
        final String buffer, pass;
        CgGraphBuffer view;
        CgBufferDecl decl;
        String file, kernel;
        long offset, bytes, frame, execution;
        boolean writes;

        Entry(String buffer, String pass) {
            this.buffer = buffer;
            this.pass = pass;
        }

        Site site() {
            return new Site(buffer, pass, file, kernel, decl, offset, bytes, frame);
        }
    }

    /** Render thread, after {@code pass} ran: what its dispatches bound, noted. */
    static void note(CgComputePass pass) {
        long frame = CgFrameRing.frame();
        List<Request> dropped = null;
        synchronized (LOCK) {
            if (!watching) return;
            long execution = ++executions;
            if (frame != pruned) {
                pruned = frame;
                dropped = prune(frame);
            }
            List<CgDispatch> dispatches = pass.dispatches();
            for (int d = 0; d < dispatches.size(); d++) {
                CgDispatch dispatch = dispatches.get(d);
                List<CgBufferDecl> decls = dispatch.source.buffers();
                for (int i = 0; i < dispatch.buffers.length; i++) {
                    CgGraphBuffer view = dispatch.buffers[i];
                    if (view != null) {
                        long bytes = dispatch.sizes[i] == 0 ? view.size() - dispatch.offsets[i] : dispatch.sizes[i];
                        note(pass, dispatch, view, decls.get(i), dispatch.offsets[i], bytes,
                                (dispatch.bufferAccess[i] & CgAccess.COMPUTE_WRITE) != 0, frame, execution);
                    }
                    CgGraphBuffer counter = dispatch.counters[i];
                    if (counter != null) {
                        note(pass, dispatch, counter, count(decls.get(i)), 0, counter.size(),
                                (dispatch.counterAccess[i] & CgAccess.COMPUTE_WRITE) != 0, frame, execution);
                    }
                }
            }
        }
        if (dropped != null) for (Request r : dropped) r.sink.failed(r.entry.buffer + " after " + r.entry.pass + " stopped running");
    }

    /** Render thread, after {@link #note}: a read armed for a site the pass just noted, taken; null when none is left. */
    static Request due() {
        synchronized (LOCK) {
            for (int i = 0; i < PENDING.size(); i++) {
                Request r = PENDING.get(i);
                if (r.entry.execution != executions) continue;
                PENDING.remove(i);
                r.site = r.entry.site();
                r.at = Math.min(r.first, r.site.elements());
                r.served = Math.min(r.count, r.site.elements() - r.at);
                return r;
            }
            return null;
        }
    }

    /** At context teardown: sites dropped, reads waiting failed. Watching stays as it was. */
    static void reset() {
        List<Request> failed;
        synchronized (LOCK) {
            ENTRIES.clear();
            failed = new ArrayList<>(PENDING);
            PENDING.clear();
        }
        for (Request r : failed) r.sink.failed("the context was torn down");
    }

    private static void note(CgComputePass pass, CgDispatch d, CgGraphBuffer view, CgBufferDecl decl, long offset,
                             long bytes, boolean writes, long frame, long execution) {
        Entry e = find(view.name(), pass.name());
        if (e == null) ENTRIES.add(e = new Entry(view.name(), pass.name()));
        // The last writer in the pass shows what it left; a buffer only read shows as its first binding.
        if (e.execution == execution && !writes) return;
        e.view = view;
        e.decl = decl;
        e.file = d.source.path();
        e.kernel = d.decl.name();
        e.offset = offset;
        e.bytes = Math.max(0, bytes);
        e.frame = frame;
        e.execution = execution;
        e.writes = writes;
    }

    private static Entry find(String buffer, String pass) {
        for (int i = 0; i < ENTRIES.size(); i++) {
            Entry e = ENTRIES.get(i);
            if (e.buffer.equals(buffer) && e.pass.equals(pass)) return e;
        }
        return null;
    }

    /** Sites unseen for {@link #FORGET_AFTER} frames dropped; the reads waiting on them, answered. */
    private static List<Request> prune(long frame) {
        List<Request> dropped = null;
        for (int i = ENTRIES.size() - 1; i >= 0; i--) {
            Entry e = ENTRIES.get(i);
            if (frame - e.frame <= FORGET_AFTER) continue;
            ENTRIES.remove(i);
            for (int r = PENDING.size() - 1; r >= 0; r--) {
                if (PENDING.get(r).entry != e) continue;
                if (dropped == null) dropped = new ArrayList<>();
                dropped.add(PENDING.remove(r));
            }
        }
        return dropped;
    }

    private static CgBufferDecl count(CgBufferDecl append) {
        CgBufferDecl count = COUNTS.get(append);
        if (count == null) {
            count = new CgBufferDecl(append.name() + "_COUNT", append.display() + " (count)", "uint", CgBufferAccess.COUNTER,
                    4, true, true, append.index(), Collections.singletonList(new CgElementField("", "uint", 0, 4)));
            COUNTS.put(append, count);
        }
        return count;
    }

    // ── Decoding ─────────────────────────────────────────────────────────────

    private enum Base { FLOAT, INT, UINT, DOUBLE, WORDS }

    /** {@code field} of the element at byte {@code at}, each component by its type. */
    static String decode(ByteBuffer data, int at, CgElementField field) {
        Base base = base(field.type());
        int step = base == Base.DOUBLE ? 8 : 4;
        StringBuilder out = new StringBuilder();
        for (int o = 0; o + step <= field.size(); o += step) {
            if (o > 0) out.append(", ");
            int p = at + field.offset() + o;
            switch (base) {
                case FLOAT -> out.append(data.getFloat(p));
                case INT -> out.append(data.getInt(p));
                case UINT -> out.append(Integer.toUnsignedString(data.getInt(p)));
                case DOUBLE -> out.append(data.getDouble(p));
                case WORDS -> out.append("0x").append(Integer.toHexString(data.getInt(p)));
            }
        }
        return out.toString();
    }

    /** What a GLSL type's components are: {@code uvec4} is UINT, {@code mat3} FLOAT, a struct's name WORDS. */
    private static Base base(String type) {
        if (type.equals("float") || type.startsWith("vec") || type.startsWith("mat")) return Base.FLOAT;
        if (type.equals("int") || type.startsWith("ivec")) return Base.INT;
        if (type.equals("uint") || type.startsWith("uvec") || type.equals("bool") || type.startsWith("bvec")) return Base.UINT;
        if (type.equals("double") || type.startsWith("dvec") || type.startsWith("dmat")) return Base.DOUBLE;
        return Base.WORDS;
    }
}
