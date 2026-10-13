package com.crystalgraphics.job.reply;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Why a {@link CgReply} did not answer: a code to branch on, a detail to show, and the throwable behind it if any.
 *
 * <pre>{@code
 * reply.onError(e -> {
 *     if (e.is(CgReplyError.CANCELLED)) return;     // nobody is waiting: nothing to say
 *     status.show("Load failed: " + e.detail());
 * });
 * }</pre>
 */
public record CgReplyError(String code, String detail, @Nullable Throwable cause) {

    /** The caller stopped caring: {@link CgReply#cancel()}, or the work's world ended. */
    public static final String CANCELLED = "CANCELLED";

    /** The work threw; {@link #cause()} is what it threw. */
    public static final String FAILED = "FAILED";

    /** No answer in time. */
    public static final String TIMEOUT = "TIMEOUT";

    public CgReplyError(String code, String detail) {
        this(code, detail, null);
    }

    public CgReplyError(String code, String detail, @Nullable Throwable cause) {
        this.code = Objects.requireNonNull(code, "code");
        this.detail = detail == null ? "" : detail;
        this.cause = cause;
    }

    public static CgReplyError cancelled() {
        return new CgReplyError(CANCELLED, "cancelled");
    }

    public static CgReplyError failed(Throwable thrown) {
        return new CgReplyError(FAILED, String.valueOf(thrown), thrown);
    }

    public boolean is(String code) {
        return this.code.equals(code);
    }

    @Override
    public String toString() {
        return detail.isEmpty() ? code : code + ": " + detail;
    }
}
