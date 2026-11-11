package com.crystalgraphics.compute.program;

import com.crystalgraphics.compute.emit.CgKernelEmitter;
import com.crystalgraphics.compute.source.CgBufferAccessor;
import com.crystalgraphics.compute.source.CgBufferDecl;
import com.crystalgraphics.compute.source.CgComputeSource;
import com.crystalgraphics.compute.source.CgImageAccessor;
import com.crystalgraphics.compute.source.CgImageDecl;
import com.crystalgraphics.compute.source.CgImageDimension;
import com.crystalgraphics.gpu.resource.CgReadback;
import com.crystalgraphics.platform.rhi.command.CgAccess;
import com.crystalgraphics.platform.gl.CgCapabilities;
import com.crystalgraphics.platform.rhi.CgDeviceSection;
import com.crystalgraphics.platform.rhi.CgRHI;
import com.crystalgraphics.platform.rhi.command.CgCommandList;
import com.crystalgraphics.platform.rhi.resource.CgGpuBuffer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Checked mode ({@code -Dcrystalgraphics.compute.checked=true}): every kernel run as compute bounds-checks each buffer
 * and image access and skips one out of range, and the first of each dispatch is logged with the file, the kernel, the
 * line, the accessor and the index, once a place.
 *
 * <pre>{@code
 * // -Dcrystalgraphics.compute.checked=true; a kernel adding one past its buffer logs, a frame or two later:
 * // [crystalgraphics] compute check: mymod:shaders/bins.compute, kernel Bin, line 31: BINS_ADD at 64, past BINS's 64
 * //     elements (12 times in one dispatch)
 * List<String> seen = CgComputeCheck.reported();   // every report so far: what a check asserts on
 * }</pre>
 *
 * <ul>
 *   <li>Where kernels run as compute (V, G43). A lowered kernel writes its own element only, and a Java body's
 *       buffers throw on their own.</li>
 *   <li>Not checked: {@code NAME_DATA[i]}, and appends, which drop what is past the end by design.</li>
 *   <li>Every access tests its index and every dispatch binds a slot: a switch for debugging, never left on.</li>
 *   <li>A failed compile's log names the {@code .compute}'s lines in checked mode, not the numbered source's.</li>
 * </ul>
 */
public final class CgComputeCheck {

    private static final Logger LOGGER = LogManager.getLogger("CgComputeCheck");
    /** A slot's words: how many, then the first's site, line, index xyz and size xyz. */
    private static final int WORDS = 9;
    /** Dispatches a frame checks apart; any past them share the last slot. */
    private static final int SLOTS = 4096;

    private static final CgKernelProgram[] PROGRAMS = new CgKernelProgram[SLOTS];
    private static final Set<String> SEEN = new HashSet<>();
    private static final List<String> REPORTED = new CopyOnWriteArrayList<>();
    private static CgGpuBuffer buffer;
    private static int stride, used;
    private static boolean overflowWarned;

    private CgComputeCheck() {}

    /** Every report so far this process, oldest first. Any thread. */
    public static List<String> reported() {
        return Collections.unmodifiableList(REPORTED);
    }

    /**
     * Makes the report slots, if not yet made, before async work opens. Render thread. Their zeroing is a fill, and made
     * inside async work it runs on the compute queue, where the frame's queue writing its own slots never waits for it.
     */
    public static void prepare() {
        if (buffer == null) create();
    }

    /** A slot's bytes: what binds at a checked kernel's report point. */
    public static final long SLOT_BYTES = WORDS * 4L;

    /**
     * The next slot for a dispatch of {@code program}: its byte offset in {@link #buffer}. Render thread, after
     * {@link #prepare}.
     */
    public static long claim(CgKernelProgram program) {
        if (buffer == null) create();
        int slot;
        if (used < SLOTS) {
            slot = used++;
        } else {
            slot = SLOTS - 1;
            if (!overflowWarned) {
                overflowWarned = true;
                LOGGER.warn("[crystalgraphics] compute check: over {} dispatches in a frame; the rest share one slot, "
                        + "and its report names the last of them", SLOTS);
            }
        }
        PROGRAMS[slot] = program;
        return (long) slot * stride;
    }

    /** The buffer the report slots are in; null before {@link #prepare}. */
    public static CgGpuBuffer buffer() {
        return buffer;
    }

    /** Reads back the slots this frame's dispatches wrote, and clears them. Render thread, between frames. */
    public static void endFrame() {
        if (used == 0) return;
        int n = used;
        used = 0;
        CgKernelProgram[] by = Arrays.copyOf(PROGRAMS, n);
        Arrays.fill(PROGRAMS, 0, n, null);
        long bytes = (long) n * stride;
        CgDeviceSection.begin();
        try {
            CgRHI.get().encoder().bufferBarrier(buffer, CgAccess.COMPUTE_WRITE, CgAccess.COPY_READ);
        } finally {
            CgDeviceSection.end();
        }
        CgReadback.buffer(buffer, 0, bytes, data -> read(data, by));
        zero(bytes, CgAccess.COPY_READ);
    }

    /** Zeroes the first {@code bytes}, last used as {@code from}, for kernels to write. */
    private static void zero(long bytes, int from) {
        CgDeviceSection.begin();
        try {
            CgCommandList list = CgRHI.get().encoder();
            list.bufferBarrier(buffer, from, CgAccess.COPY_WRITE);
            list.fillBuffer(buffer, 0, bytes, 0);
            list.bufferBarrier(buffer, CgAccess.COPY_WRITE, CgAccess.COMPUTE_READ | CgAccess.COMPUTE_WRITE);
        } finally {
            CgDeviceSection.end();
        }
    }

    /** At the device's teardown. */
    public static void release() {
        if (buffer != null) CgRHI.get().release(buffer);
        buffer = null;
        used = 0;
        Arrays.fill(PROGRAMS, null);
    }

    private static void create() {
        int align = Math.max(4, CgCapabilities.detect().storageOffsetAlignment());
        stride = (WORDS * 4 + align - 1) / align * align;
        long bytes = (long) SLOTS * stride;
        buffer = CgRHI.get().createBuffer(new CgGpuBuffer.Desc("cg_compute_check", bytes, CgGpuBuffer.Usage.ALL, false));
        zero(bytes, CgAccess.COPY_WRITE);
    }

    private static void read(ByteBuffer data, CgKernelProgram[] by) {
        for (int s = 0; s < by.length; s++) {
            int at = s * stride, count = data.getInt(at);
            if (count == 0) continue;
            CgKernelProgram p = by[s];
            int site = data.getInt(at + 4), line = data.getInt(at + 8);
            String where = p.source().path() + ", kernel " + p.kernel().name()
                    + (p.keywords().isEmpty() ? "" : " " + p.keywords()) + ", line " + line;
            if (!SEEN.add(where + "#" + site)) continue;
            String report = where + ": " + access(p.source(), site, data, at)
                    + (count > 1 ? " (" + Integer.toUnsignedString(count) + " times in one dispatch)" : "");
            REPORTED.add(report);
            LOGGER.warn("[crystalgraphics] compute check: {}", report);
        }
    }

    /** What went out of range: {@code BINS_ADD at 64, past BINS's 64 elements}, {@code HEAT_STORE at (256, 3), ...}. */
    private static String access(CgComputeSource source, int site, ByteBuffer data, int at) {
        int x = data.getInt(at + 12), y = data.getInt(at + 16), z = data.getInt(at + 20);
        int w = data.getInt(at + 24), h = data.getInt(at + 28), d = data.getInt(at + 32);
        if (site < CgKernelEmitter.IMAGE_SITES) {
            CgBufferDecl b = null;
            for (CgBufferDecl candidate : source.buffers()) if (candidate.index() == site >> 4) b = candidate;
            String name = b == null ? "buffer " + (site >> 4) : b.name();
            return name + CgBufferAccessor.values()[site & 15].suffix + " at " + x + ", past " + name + "'s "
                    + Integer.toUnsignedString(w) + " elements";
        }
        int s = site - CgKernelEmitter.IMAGE_SITES;
        CgImageDecl image = null;
        for (CgImageDecl candidate : source.images()) if (candidate.index() == s >> 4) image = candidate;
        String name = image == null ? "image " + (s >> 4) : image.name();
        boolean flat = image != null && image.dimension() == CgImageDimension.D2;
        return name + CgImageAccessor.values()[s & 15].suffix + " at (" + x + ", " + y + (flat ? "" : ", " + z)
                + "), outside " + name + "'s " + w + "x" + h + (flat ? "" : "x" + d);
    }
}
