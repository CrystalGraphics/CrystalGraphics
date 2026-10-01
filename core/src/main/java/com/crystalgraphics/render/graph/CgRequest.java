package com.crystalgraphics.render.graph;

import javax.annotation.Nullable;

/**
 * The outcome of graph work someone waits on — an upload, a compile, a callback: pending until the frame that runs
 * it, then done or failed. Readable from any thread, so a document can decide whether a frame that needs it may show.
 *
 * <pre>{@code
 * CgRequest compiled = recording.compile(pipeline);
 * // a later frame, on the document's thread:
 * if (compiled.failed()) report(compiled.failure());
 * else if (!compiled.done()) recordAnotherCompile();   // a compile still running asks again next frame
 * }</pre>
 */
public final class CgRequest {

    public enum Status { PENDING, DONE, FAILED }

    private final String name;
    private volatile Status status = Status.PENDING;
    @Nullable
    private volatile String failure;

    CgRequest(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public Status status() {
        return status;
    }

    public boolean done() {
        return status == Status.DONE;
    }

    public boolean failed() {
        return status == Status.FAILED;
    }

    /** Why it failed, or null. */
    @Nullable
    public String failure() {
        return failure;
    }

    void complete() {
        status = Status.DONE;
    }

    void fail(String reason) {
        failure = reason;
        status = Status.FAILED;
    }

    @Override
    public String toString() {
        return "CgRequest(" + name + " " + status + ")";
    }
}
