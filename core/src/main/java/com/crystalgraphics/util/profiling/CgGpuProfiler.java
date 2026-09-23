package com.crystalgraphics.util.profiling;

import com.crystalgraphics.trace.CgGpuTrace;
import com.crystalgraphics.trace.CgTrace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GPU time per named zone, for a scene that prints its own summary. A facade over {@link CgGpuTrace},
 * where each zone also lands in the frame it was issued in — read that for per-frame figures.
 *
 * <pre>{@code
 * CgGpuProfiler.enable();              // the "gpu" channel on, if the context has timer queries
 * CgGpuProfiler.begin("textDraw");
 * drawText();
 * CgGpuProfiler.end();
 * CgGpuProfiler.endFrame();            // collect what the GPU has finished; never waits
 *
 * CgGpuProfiler.report().get("textDraw").avgMillis();
 * }</pre>
 *
 * <p>Zones nest by pausing the outer one; see {@link CgGpuTrace}. GL thread only.</p>
 */
public final class CgGpuProfiler {

    private CgGpuProfiler() {
    }

    /** Total GPU nanoseconds and sample count for one zone name. */
    public static final class Accum {
        final long totalNanos;
        final long samples;

        Accum(long totalNanos, long samples) {
            this.totalNanos = totalNanos;
            this.samples = samples;
        }

        public long totalNanos() {
            return totalNanos;
        }

        public long samples() {
            return samples;
        }

        public double avgMillis() {
            return samples == 0 ? 0 : totalNanos / 1_000_000.0 / samples;
        }
    }

    /** Switches the {@code gpu} channel on. GL thread: asks the context whether it can time. */
    public static void enable() {
        CgTrace.setEnabled(CgGpuTrace.GPU, true);
        CgGpuTrace.probe();
    }

    public static void disable() {
        CgTrace.setEnabled(CgGpuTrace.GPU, false);
    }

    public static boolean isEnabled() {
        return CgGpuTrace.isMeasuring() && CgGpuTrace.support() == CgGpuTrace.Support.SUPPORTED;
    }

    public static boolean isAvailable() {
        return CgGpuTrace.support() == CgGpuTrace.Support.SUPPORTED;
    }

    /** Opens a zone; a leading {@code "gpu."} is dropped, since every GPU name carries its own prefix. */
    public static void begin(String name) {
        CgGpuTrace.begin(name.startsWith("gpu.") ? name.substring(4) : name);
    }

    public static void end() {
        CgGpuTrace.end();
    }

    /** Collects finished results without waiting — for a scene with no host frame loop. */
    public static void endFrame() {
        CgGpuTrace.collect();
    }

    /** Per zone name since {@link #reset()}, in first-seen order. */
    public static Map<String, Accum> report() {
        Map<String, Accum> out = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> e : CgGpuTrace.totals().entrySet()) {
            out.put(e.getKey(), new Accum(e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    public static void reset() {
        CgGpuTrace.resetTotals();
    }

    /** Releases every query object. GL thread only. */
    public static void dispose() {
        CgGpuTrace.dispose();
        disable();
    }
}
