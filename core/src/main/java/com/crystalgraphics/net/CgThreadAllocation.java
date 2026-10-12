package com.crystalgraphics.net;

import java.lang.management.ManagementFactory;

import com.sun.management.ThreadMXBean;

/** Bytes the current thread has allocated, where the JVM counts them (HotSpot's {@code com.sun.management}). */
final class CgThreadAllocation {

    private static final ThreadMXBean BEAN = bean();

    private CgThreadAllocation() {
    }

    private static ThreadMXBean bean() {
        try {
            Object bean = ManagementFactory.getThreadMXBean();
            if (!(bean instanceof ThreadMXBean)) return null;
            ThreadMXBean counting = (ThreadMXBean) bean;
            return counting.isThreadAllocatedMemorySupported() && counting.isThreadAllocatedMemoryEnabled() ? counting : null;
        } catch (Throwable missing) {
            // A JVM without jdk.management, or a module layer that cannot read it.
            return null;
        }
    }

    static boolean available() {
        return BEAN != null;
    }

    static long current() {
        return BEAN == null ? 0 : BEAN.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
}
