package com.crystalgraphics.serialization;

/** Thrown by {@link CgCodec}/{@link CgDynamicOps} on malformed or unexpected input during decode, or
 * when a {@link CgDynamicOps} implementation is asked to read a value as the wrong type. Unchecked —
 * matches this codebase's existing convention for parse-time failures (e.g. Gson's own
 * {@code JsonSyntaxException}), so callers aren't forced to handle it at every call site. */
public class CgCodecException extends RuntimeException {
    public CgCodecException(String message) {
        super(message);
    }

    public CgCodecException(String message, Throwable cause) {
        super(message, cause);
    }
}
