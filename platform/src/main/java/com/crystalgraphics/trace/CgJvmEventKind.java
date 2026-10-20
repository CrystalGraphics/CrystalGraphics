package com.crystalgraphics.trace;

/**
 * What the JVM did that a frame can be blamed on, as {@link CgTrace#jvmEventsIn} reports it.
 *
 * <pre>{@code
 * CgTrace.startJvmEvents();
 * // ... frames run ...
 * long deopts = CgTrace.jvmEventCountIn(frame, CgJvmEventKind.DEOPTIMIZATION);   // ABSENT where unseen
 * }</pre>
 */
public enum CgJvmEventKind {
    /** A method compiled; the subject is {@code Type.method}, the detail {@code level N}. */
    COMPILATION,
    /** Compiled code abandoned for the interpreter; the detail is {@code reason/action}. */
    DEOPTIMIZATION,
    /** All threads stopped; the duration is the whole stop, the detail {@code id N}. */
    SAFEPOINT,
    /** A class loaded; the subject is its binary name. */
    CLASS_LOAD
}
