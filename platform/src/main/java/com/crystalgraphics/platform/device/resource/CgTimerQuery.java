package com.crystalgraphics.platform.device.resource;

import com.crystalgraphics.platform.device.CgDeviceObject;
import com.crystalgraphics.platform.device.command.CgCommandEncoder;

/** GPU time between {@link CgCommandEncoder#beginTimer} and {@link CgCommandEncoder#endTimer}. */
public interface CgTimerQuery extends CgDeviceObject {

    /** The elapsed nanoseconds, or -1 until the GPU has finished the frame that measured it. Never waits. */
    long resultNanos();
}
