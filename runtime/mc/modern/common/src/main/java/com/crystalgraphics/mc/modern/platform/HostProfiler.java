package com.crystalgraphics.mc.modern.platform;

import com.crystalgraphics.trace.CgTrace;
import com.crystalgraphics.trace.CgTraceChannel;

//? if >=26.2 <26.3 {
/*import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.util.profiling.metrics.MetricCategory;

import java.util.function.Supplier;
*///?}

/**
 * Minecraft's own profiler sections as trace zones on {@link #CHANNEL}, so a report shows where Minecraft's frame
 * goes around the engine's. While on, it also frames the trace at the top of Minecraft's frame (its tick) rather
 * than at the engine's frame end, so every section of a frame lands in one trace frame.
 *
 * <pre>{@code
 * -Dcrystalgraphics.trace.channels=crystalgraphics.host,crystalgraphics,gpu
 * }</pre>
 *
 * <p>Installed by each loader's {@code ProfilerHook} beside whatever profiler Minecraft built for the frame, only
 * while {@link #CHANNEL} records. Minecraft 26.2 alone, on NeoForge; on every other node the channel records
 * nothing.</p>
 */
//? if >=26.2 <26.3 {
/*public final class HostProfiler implements ProfilerFiller {
*///?} else {
public final class HostProfiler {
//?}

    public static final CgTraceChannel CHANNEL = CgTrace.channel("crystalgraphics.host");

    static {
        // Minecraft's frame-rate cap sleeps; a report would otherwise rank it as the frame's biggest cost.
        CgTrace.waitName("frameLimiter");
    }

    //? if >=26.2 <26.3 {
    /*public static final HostProfiler INSTANCE = new HostProfiler();

    // What begin returned per open section, innermost last; a section past the array opens no zone.
    private final long[] open = new long[64];
    private int depth;

    private HostProfiler() {}

    // Ahead of Minecraft's event poll, so nothing of its frame falls before the trace frame opens.
    @Override
    public void startTick() {
        depth = 0;
        CgTrace.frameBegin();
    }

    @Override
    public void endTick() {
        CgTrace.frameEnd();
    }

    @Override
    public void push(String name) {
        if (depth < open.length) open[depth] = CgTrace.begin(CHANNEL, name);
        depth++;
    }

    @Override
    public void push(Supplier<String> name) {
        if (depth < open.length) open[depth] = CgTrace.isEnabled(CHANNEL) ? CgTrace.begin(CHANNEL, name.get()) : 0L;
        depth++;
    }

    @Override
    public void pop() {
        if (depth == 0) return;
        depth--;
        if (depth < open.length) CgTrace.end(open[depth]);
    }

    @Override
    public void popPush(String name) {
        pop();
        push(name);
    }

    @Override
    public void popPush(Supplier<String> name) {
        pop();
        push(name);
    }

    @Override
    public void markForCharting(MetricCategory category) {}

    @Override
    public void incrementCounter(String name, int amount) {
        CgTrace.add(CHANNEL, name, amount);
    }

    @Override
    public void incrementCounter(Supplier<String> name, int amount) {
        if (CgTrace.isEnabled(CHANNEL)) CgTrace.add(CHANNEL, name.get(), amount);
    }
    *///?}
}
