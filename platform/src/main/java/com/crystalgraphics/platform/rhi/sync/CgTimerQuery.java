package com.crystalgraphics.platform.rhi.sync;

import com.crystalgraphics.platform.rhi.resource.CgRHIResource;
import com.crystalgraphics.platform.rhi.command.CgCommandList;

/** GPU time between {@link CgCommandList#beginTimer} and {@link CgCommandList#endTimer}. */
public interface CgTimerQuery extends CgRHIResource {

    /** The elapsed nanoseconds, or -1 until the GPU has finished the frame that measured it. Never waits. */
    long resultNanos();
}
