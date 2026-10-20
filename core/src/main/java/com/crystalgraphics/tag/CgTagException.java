package com.crystalgraphics.tag;

/**
 * A tag that is malformed, unknown, declared in the wrong namespace, or sent as an index nobody agreed on. Thrown at
 * load by name, never defaulted: a misspelt tag in a file fails the file.
 */
public final class CgTagException extends RuntimeException {

    public CgTagException(String message) {
        super(message);
    }
}
