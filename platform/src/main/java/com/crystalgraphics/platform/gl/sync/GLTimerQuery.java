package com.crystalgraphics.platform.gl.sync;

import com.crystalgraphics.platform.gl.driver.CgGLDriver;
import com.crystalgraphics.platform.rhi.sync.CgTimerQuery;

import java.util.function.LongSupplier;

import static com.crystalgraphics.platform.gl.driver.GLC.*;

/**
 * Two {@code GL_TIMESTAMP} queries (Unreal's `OpenGLQuery.cpp`): a span is end minus begin, a lone timestamp the end's
 * value. Read once the frame that wrote them has retired, so reading never waits.
 */
public final class GLTimerQuery implements CgTimerQuery {

    private final CgGLDriver gl;
    private final LongSupplier retired;
    private final String label;
    private final int begin, end;
    private long frame = Long.MAX_VALUE;
    private boolean span;
    private long result = -1;

    public GLTimerQuery(CgGLDriver gl, LongSupplier retired, String label) {
        this.gl = gl;
        this.retired = retired;
        this.label = label;
        this.begin = gl.glCreateQueries(GL_TIMESTAMP);
        this.end = gl.glCreateQueries(GL_TIMESTAMP);
    }

    public void begin() {
        gl.glQueryCounter(begin, GL_TIMESTAMP);
        span = true;
        result = -1;
    }

    /** Ends a span, or writes a lone timestamp when none began; {@code frame} is the frame recording it. */
    public void end(long frame, boolean lone) {
        gl.glQueryCounter(end, GL_TIMESTAMP);
        if (lone) span = false;
        this.frame = frame;
        result = -1;
    }

    @Override
    public long resultNanos() {
        if (result >= 0) return result;
        if (frame > retired.getAsLong()) return -1;
        long e = gl.glGetQueryObjecti64(end, GL_QUERY_RESULT);
        result = span ? Math.max(0, e - gl.glGetQueryObjecti64(begin, GL_QUERY_RESULT)) : e;
        return result;
    }

    @Override public String label() { return label; }

    public void delete() {
        gl.glDeleteQueries(begin);
        gl.glDeleteQueries(end);
    }
}
