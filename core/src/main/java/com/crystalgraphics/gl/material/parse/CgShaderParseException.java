package com.crystalgraphics.gl.material.parse;

/**
 * Thrown by {@link CgShaderParser} when a {@code .shader} source file
 * violates the CrystalShader format specification.
 *
 * <p>This is a {@link RuntimeException} so callers are not forced to catch
 * it in render-path code, but format errors should be treated as fatal
 * during asset loading.</p>
 *
 * <h3>The position, and why it was added</h3>
 *
 * <p>Until now this named the <b>file</b> and nothing else:
 * {@code "[" + resourcePath + "] 'void fragment(' function not found"}. That is enough for a build log
 * and not enough for an editor — a squiggle needs a line, and an adapter written against a message alone
 * can only report everything at line 1, which points at innocent text. That is worse than no diagnostic,
 * and it is the exact failure mode an editor-facing adapter exists to avoid.</p>
 *
 * <p>So the position is carried, and {@link #line()} answers {@code 0} when nothing located it. Zero is
 * the honest answer rather than a default: a consumer that cannot place a problem should say so — a
 * whole-file problem is a real thing, and pretending it belongs to the first line is a claim about text
 * that did nothing wrong.</p>
 *
 * <p><b>Locating is the caller's, through {@link #locate}.</b> The alternative was a line cursor threaded
 * through six parsers and sixty-odd throw sites, most of which work on the whole source with no notion of
 * where they are. Every message that quotes the offending text can find it, which is nearly all of them,
 * and the ones that cannot say {@code 0} and are no worse off than before.</p>
 */
public class CgShaderParseException extends RuntimeException {

    /** 1-based, or {@code 0} when nothing located this. */
    private final int line;

    /** 1-based, or {@code 0}. */
    private final int column;

    public CgShaderParseException(String message) {
        this(message, 0, 0);
    }

    public CgShaderParseException(String message, Throwable cause) {
        super(message, cause);
        this.line = 0;
        this.column = 0;
    }

    public CgShaderParseException(String message, int line, int column) {
        super(message);
        this.line = Math.max(0, line);
        this.column = Math.max(0, column);
    }

    /** 1-based line, or {@code 0} when the problem could not be placed. */
    public int line() {
        return line;
    }

    /** 1-based column, or {@code 0}. */
    public int column() {
        return column;
    }

    public boolean hasPosition() {
        return line > 0;
    }

    /**
     * The same exception, placed at the first occurrence of {@code offending} in {@code source}.
     *
     * <p>Returns {@code this} unchanged when the text is not found or is already placed, so a call site
     * can wrap unconditionally: an unplaced exception is exactly what was thrown before this existed.</p>
     *
     * <p>The <b>first</b> occurrence, deliberately. A parser that has failed cannot say which of several
     * identical tokens it was looking at — it has no cursor — and the first is the one a reader checks
     * first. Guessing further would be inventing precision.</p>
     */
    public CgShaderParseException locate(String source, String offending) {
        if (hasPosition() || source == null || offending == null || offending.isEmpty()) return this;
        int at = source.indexOf(offending);
        if (at < 0) return this;
        return at(getMessage(), source, at);
    }

    /** The same exception, placed at a character offset into the source. */
    public static CgShaderParseException at(String message, String source, int offset) {
        int line = 1;
        int lineStart = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new CgShaderParseException(message, line, offset - lineStart + 1);
    }
}
