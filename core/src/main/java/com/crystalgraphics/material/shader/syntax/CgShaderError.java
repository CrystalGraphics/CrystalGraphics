package com.crystalgraphics.material.shader.syntax;

import javax.annotation.Nullable;

/**
 * A shader source that breaks its format: a {@code .shader}, {@code .factory}, {@code .primitive} or {@code .compute}
 * file, placed at the line and column it names where it can be.
 *
 * <pre>{@code
 * try {
 *     CgShaderSpec spec = CgShaderSpec.read(source, "mymod:shaders/glass.shader");
 * } catch (CgShaderError e) {
 *     LOGGER.error(e.getMessage());              // mymod:shaders/glass.shader:4:13: 'BlendMode Glassy' is one of [...]
 *     editor.squiggle(e.line(), e.column(), e.why());   // 1-based; 0 where it is the whole file's
 * }
 * }</pre>
 *
 * <ul>
 *   <li>A placed message reads {@code path:line:column: why}; a file-wide one {@code path: why}.</li>
 *   <li>{@link #line()} is 0 where nothing places it: a problem of the whole file is not the first line's.</li>
 *   <li>A {@code RuntimeException}: a format error is fatal at load, never caught on a render path.</li>
 * </ul>
 */
public class CgShaderError extends RuntimeException {

    private final String why;
    @Nullable private final String path;
    private final int line, column;

    /** A message already naming its file, unplaced: a parser with no position to give. */
    public CgShaderError(String message) {
        this(message, null, message, 0, 0);
    }

    public CgShaderError(String message, Throwable cause) {
        super(message, cause);
        this.why = message;
        this.path = null;
        this.line = 0;
        this.column = 0;
    }

    private CgShaderError(String message, @Nullable String path, String why, int line, int column) {
        super(message);
        this.why = why;
        this.path = path;
        this.line = line;
        this.column = column;
    }

    /** {@code why}, of the whole file at {@code path}. */
    public static CgShaderError of(String path, String why) {
        return new CgShaderError(path + ": " + why, path, why, 0, 0);
    }

    /** {@code why}, placed at {@code offset} into {@code text}. */
    public static CgShaderError at(CgShaderText text, int offset, String why) {
        int line = text.line(offset), column = text.column(offset);
        return new CgShaderError(text.path() + ":" + line + ":" + column + ": " + why, text.path(), why, line, column);
    }

    /** This error placed at {@code offset} into {@code text}; itself where it is placed already. */
    public CgShaderError placed(CgShaderText text, int offset) {
        return placed() ? this : at(text, offset, why);
    }

    /** What is wrong, without where. */
    public String why() {
        return why;
    }

    /** 1-based; 0 where it is the whole file's. */
    public int line() {
        return line;
    }

    /** 1-based; 0 where it is the whole file's. */
    public int column() {
        return column;
    }

    public boolean placed() {
        return line > 0;
    }
}
