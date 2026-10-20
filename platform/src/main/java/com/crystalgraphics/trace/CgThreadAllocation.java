package com.crystalgraphics.trace;

import com.sun.management.ThreadMXBean;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;

/**
 * Bytes the current thread has allocated, where the JVM counts them (HotSpot's {@code com.sun.management}).
 *
 * <pre>{@code
 * if (CgThreadAllocation.available()) {
 *     long before = CgThreadAllocation.current();
 *     doTheWork();
 *     long bytes = CgThreadAllocation.current() - before;   // 0 proves the work allocated nothing on this thread
 * }
 * }</pre>
 *
 * <p>Reads through {@code getCurrentThreadAllocatedBytes()} on JDK 14+, which needs no thread lookup, and through the
 * by-id call on older JVMs (hundreds of nanoseconds). Easy to get wrong: where the JVM does not count, {@link #current}
 * returns 0, which reads as "allocated nothing"; ask {@link #available} first, and report <em>unavailable</em> rather
 * than a pass. Reading allocates nothing.</p>
 */
public final class CgThreadAllocation {

    private static final ThreadMXBean BEAN = bean();
    /** {@code (ThreadMXBean) long}, or null before JDK 14. */
    private static final MethodHandle CURRENT = currentHandle();

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

    private static MethodHandle currentHandle() {
        if (BEAN == null) return null;
        try {
            return MethodHandles.lookup().findVirtual(ThreadMXBean.class, "getCurrentThreadAllocatedBytes",
                    MethodType.methodType(long.class));
        } catch (ReflectiveOperationException | RuntimeException absent) {
            return null;
        }
    }

    /** Whether this JVM counts a thread's allocation. */
    public static boolean available() {
        return BEAN != null;
    }

    /** Bytes the calling thread has allocated since it started; 0 where {@link #available} is false. */
    public static long current() {
        if (BEAN == null) return 0L;
        if (CURRENT != null) {
            try {
                return (long) CURRENT.invokeExact(BEAN);
            } catch (Throwable broken) {
                // fall through to the by-id call
            }
        }
        return BEAN.getThreadAllocatedBytes(Thread.currentThread().getId());
    }
}
