package com.crystalgraphics.text.richtext;

/**
 * Thrown by a strict-mode {@link CgMarkupParser} when input violates its grammar —
 * unmatched or unclosed tags/codes, unknown tag/code names, or malformed attribute values.
 * Lenient-mode parsers never throw this; they recover instead (see each parser's javadoc).
 */
public class CgMarkupParseException extends RuntimeException {

    public CgMarkupParseException(String message) {
        super(message);
    }
}
